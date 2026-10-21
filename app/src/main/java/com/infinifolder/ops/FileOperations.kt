package com.infinifolder.ops

import android.net.Uri
import com.infinifolder.apps.AppCategories
import com.infinifolder.apps.AppsUri
import com.infinifolder.model.ProviderResult
import com.infinifolder.model.ResourceEntry
import com.infinifolder.model.ResourceRef
import com.infinifolder.shortcut.ShortcutInfo
import com.infinifolder.storage.StorageProvider
import java.io.InputStream
import java.io.OutputStream

data class FolderStats(
    val totalBytes: Long,
    val fileCount: Int,
    val directoryCount: Int
)

object FileOperations {

    fun calculateFolderStats(
        entries: List<ResourceEntry>,
        provider: StorageProvider,
        isCancelled: () -> Boolean = { false },
        onProgress: ((FolderStats) -> Unit)? = null
    ): FolderStats {
        var bytes = 0L
        var files = 0
        var subdirs = 0
        var lastUpdate = System.currentTimeMillis()

        fun countChildren(parentRef: ResourceRef) {
            if (isCancelled()) return
            val list = provider.list(parentRef)
            if (list is ProviderResult.Success) {
                for (child in list.value) {
                    if (isCancelled()) return
                    if (child.metadata.isDirectory) {
                        subdirs++
                        countChildren(child.ref)
                    } else {
                        files++
                        bytes += child.metadata.sizeBytes ?: 0L
                    }
                    val now = System.currentTimeMillis()
                    if (now - lastUpdate >= 250) {
                        lastUpdate = now
                        onProgress?.invoke(FolderStats(bytes, files, subdirs))
                    }
                }
            }
        }

        for (entry in entries) {
            if (isCancelled()) break
            if (entry.metadata.isDirectory) {
                countChildren(entry.ref)
            } else {
                files++
                bytes += entry.metadata.sizeBytes ?: 0L
            }
            val now = System.currentTimeMillis()
            if (now - lastUpdate >= 250) {
                lastUpdate = now
                onProgress?.invoke(FolderStats(bytes, files, subdirs))
            }
        }
        return FolderStats(bytes, files, subdirs)
    }

    fun calculateTotalBytes(
        entries: List<ResourceEntry>,
        provider: StorageProvider,
        isCancelled: () -> Boolean = { false }
    ): Long {
        var total = 0L
        fun count(entry: ResourceEntry) {
            if (isCancelled()) return
            if (entry.metadata.isDirectory) {
                if (entry.ref.providerId == "apps" || AppsUri.isCategoryUri(entry.uri)) {
                    // Copying an app category creates lightweight .lnk shortcut files, not full APKs
                    return
                }
                val list = provider.list(entry.ref)
                if (list is ProviderResult.Success) {
                    for (child in list.value) count(child)
                }
            } else {
                total += entry.metadata.sizeBytes ?: 0L
            }
        }
        for (entry in entries) count(entry)
        return total
    }

    fun batchTransfer(
        entries: List<ResourceEntry>,
        destinationFolderUri: Uri,
        move: Boolean,
        provider: StorageProvider,
        resourceRef: (Uri) -> ResourceRef,
        update: (OperationUpdate) -> Unit,
        isCancelled: () -> Boolean
    ) {
        val destRef = resourceRef(destinationFolderUri)
        val totalBytes = calculateTotalBytes(entries, provider, isCancelled).takeIf { it > 0 }
        var transferredBytes = 0L
        var itemsDone = 0
        val totalItems = entries.size
        var lastRateTime = System.currentTimeMillis()
        var bytesSinceLast = 0L

        fun reportProgress(currentItem: String, currentRate: Long) {
            update(
                OperationUpdate(
                    bytes = transferredBytes,
                    totalBytes = totalBytes,
                    itemsProcessed = itemsDone,
                    totalItems = totalItems,
                    currentItem = currentItem,
                    bytesPerSecond = currentRate,
                    message = "${if (move) "Moving" else "Copying"} $currentItem"
                )
            )
        }

        fun copyFileWithProgress(
            source: ResourceEntry,
            destParentRef: ResourceRef
        ): ResourceEntry {
            if (isCancelled()) throw InterruptedException("Transfer cancelled")
            val targetName = if (source.ref.providerId == "apps" && !source.metadata.name.endsWith(".apk", ignoreCase = true)) {
                "${source.metadata.name}.apk"
            } else {
                source.metadata.name
            }
            reportProgress(targetName, 0L)

            val createResult = provider.createFile(destParentRef, targetName, source.metadata.mimeType)
            if (createResult !is ProviderResult.Success) {
                throw IllegalStateException((createResult as? ProviderResult.Failure)?.message ?: "Failed to create $targetName")
            }
            val createdEntry = createResult.value

            val readResult = provider.openRead(source.ref)
            if (readResult !is ProviderResult.Success) {
                try { provider.delete(createdEntry.ref) } catch (_: Exception) {}
                throw IllegalStateException((readResult as? ProviderResult.Failure)?.message ?: "Failed to open $targetName")
            }

            val writeResult = provider.openWrite(createdEntry.ref)
            if (writeResult !is ProviderResult.Success) {
                try { provider.delete(createdEntry.ref) } catch (_: Exception) {}
                throw IllegalStateException((writeResult as? ProviderResult.Failure)?.message ?: "Failed to open destination for $targetName")
            }

            val buffer = ByteArray(128 * 1024)
            try {
                readResult.value.use { input ->
                    writeResult.value.use { output ->
                        var r: Int
                        while (input.read(buffer).also { r = it } >= 0) {
                            if (isCancelled()) throw InterruptedException("Transfer cancelled")
                            output.write(buffer, 0, r)
                            transferredBytes += r
                            bytesSinceLast += r
                            val now = System.currentTimeMillis()
                            if (now - lastRateTime >= 300) {
                                val speed = (bytesSinceLast * 1000) / (now - lastRateTime).coerceAtLeast(1)
                                lastRateTime = now
                                bytesSinceLast = 0L
                                reportProgress(targetName, speed)
                            }
                        }
                        output.flush()
                    }
                }
            } catch (e: Exception) {
                // CLEANUP: delete partial file on cancellation or failure
                try { provider.delete(createdEntry.ref) } catch (_: Exception) {}
                throw e
            }
            return createdEntry
        }

        fun copyAppCategoryAsLinks(
            sourceDir: ResourceEntry,
            destParentRef: ResourceRef
        ): ResourceEntry {
            if (isCancelled()) throw InterruptedException("Transfer cancelled")
            val createDir = provider.createDirectory(destParentRef, sourceDir.metadata.name)
            val createdDir = if (createDir is ProviderResult.Success) {
                createDir.value
            } else {
                val existing = (provider.list(destParentRef) as? ProviderResult.Success)?.value
                    ?.firstOrNull { it.metadata.isDirectory && it.metadata.name == sourceDir.metadata.name }
                existing ?: throw IllegalStateException((createDir as? ProviderResult.Failure)?.message ?: "Failed to create folder ${sourceDir.metadata.name}")
            }
            val createdFresh = createDir is ProviderResult.Success
            try {
                val categoryName = AppsUri.getCategoryName(sourceDir.uri) ?: sourceDir.metadata.name
                // 1. Write the category icon to .icon.png inside the created directory
                if (AppsUri.isCategoryUri(sourceDir.uri)) {
                    val iconBytes = AppCategories.renderCategoryIconPng(categoryName)
                    val iconCreate = provider.createFile(createdDir.ref, ".icon.png", "image/png")
                    if (iconCreate is ProviderResult.Success) {
                        val iconWrite = provider.openWrite(iconCreate.value.ref)
                        if (iconWrite is ProviderResult.Success) {
                            iconWrite.value.use { output ->
                                output.write(iconBytes)
                                output.flush()
                            }
                            transferredBytes += iconBytes.size
                        }
                    }
                }

                // 2. Populate directory with .lnk application shortcuts for every app in the category
                val listResult = provider.list(sourceDir.ref)
                if (listResult is ProviderResult.Success) {
                    val usedNames = mutableSetOf<String>()
                    for (child in listResult.value) {
                        if (isCancelled()) throw InterruptedException("Transfer cancelled")
                        if (child.metadata.isDirectory) {
                            copyAppCategoryAsLinks(child, createdDir.ref)
                        } else {
                            val baseName = child.metadata.name.removeSuffix(".apk").removeSuffix(".lnk").trim()
                                .ifEmpty { AppsUri.getPackageName(child.uri) ?: "Application" }
                            var linkFileName = "$baseName${ShortcutInfo.SHORTCUT_EXTENSION}"
                            var suffix = 2
                            while (!usedNames.add(linkFileName.lowercase())) {
                                linkFileName = "$baseName ($suffix)${ShortcutInfo.SHORTCUT_EXTENSION}"
                                suffix++
                            }
                            reportProgress(linkFileName, 0L)
                            val shortcutInfo = ShortcutInfo(
                                targetUri = child.uri.toString(),
                                targetPath = "Installed Applications / $categoryName / $baseName",
                                displayNameOverride = baseName,
                                targetMimeType = child.metadata.mimeType,
                                targetIsDirectory = false
                            )
                            val jsonBytes = shortcutInfo.toJson().toByteArray(Charsets.UTF_8)
                            val createLink = provider.createFile(createdDir.ref, linkFileName, ShortcutInfo.SHORTCUT_MIME)
                            if (createLink !is ProviderResult.Success) {
                                throw IllegalStateException((createLink as? ProviderResult.Failure)?.message ?: "Failed to create $linkFileName")
                            }
                            val writeLink = provider.openWrite(createLink.value.ref)
                            if (writeLink !is ProviderResult.Success) {
                                try { provider.delete(createLink.value.ref) } catch (_: Exception) {}
                                throw IllegalStateException((writeLink as? ProviderResult.Failure)?.message ?: "Failed to write $linkFileName")
                            }
                            writeLink.value.use { output ->
                                output.write(jsonBytes)
                                output.flush()
                            }
                            transferredBytes += jsonBytes.size
                        }
                    }
                }
            } catch (e: Exception) {
                if (createdFresh) {
                    try { provider.delete(createdDir.ref) } catch (_: Exception) {}
                }
                throw e
            }
            return createdDir
        }

        fun copyTreeRecursively(
            sourceDir: ResourceEntry,
            destParentRef: ResourceRef
        ): ResourceEntry {
            if (isCancelled()) throw InterruptedException("Transfer cancelled")
            if (sourceDir.ref.providerId == "apps" || AppsUri.isCategoryUri(sourceDir.uri)) {
                return copyAppCategoryAsLinks(sourceDir, destParentRef)
            }
            val createDir = provider.createDirectory(destParentRef, sourceDir.metadata.name)
            if (createDir !is ProviderResult.Success) {
                throw IllegalStateException((createDir as? ProviderResult.Failure)?.message ?: "Failed to create folder ${sourceDir.metadata.name}")
            }
            val createdDir = createDir.value
            try {
                val listResult = provider.list(sourceDir.ref)
                if (listResult is ProviderResult.Success) {
                    for (child in listResult.value) {
                        if (isCancelled()) throw InterruptedException("Transfer cancelled")
                        if (child.metadata.isDirectory) {
                            copyTreeRecursively(child, createdDir.ref)
                        } else {
                            copyFileWithProgress(child, createdDir.ref)
                        }
                    }
                }
            } catch (e: Exception) {
                // CLEANUP: delete partial directory on cancellation or failure
                try { provider.delete(createdDir.ref) } catch (_: Exception) {}
                throw e
            }
            return createdDir
        }

        for (entry in entries) {
            if (isCancelled()) throw InterruptedException("Transfer cancelled")
            reportProgress(entry.metadata.name, 0L)
            if (entry.metadata.isDirectory) {
                copyTreeRecursively(entry, destRef)
            } else {
                copyFileWithProgress(entry, destRef)
            }
            if (move) {
                // Delete original ONLY after full success
                provider.delete(entry.ref)
            }
            itemsDone++
            reportProgress(entry.metadata.name, 0L)
        }
    }

    fun batchDelete(
        entries: List<ResourceEntry>,
        provider: StorageProvider,
        update: (OperationUpdate) -> Unit,
        isCancelled: () -> Boolean
    ) {
        val total = entries.size
        val startTime = System.currentTimeMillis()
        for ((idx, entry) in entries.withIndex()) {
            if (isCancelled()) throw InterruptedException("Delete cancelled")
            val now = System.currentTimeMillis()
            val speed = if (now > startTime) ((idx + 1) * 1000L) / (now - startTime).coerceAtLeast(1) else 0L
            update(
                OperationUpdate(
                    itemsProcessed = idx + 1,
                    totalItems = total,
                    currentItem = entry.metadata.name,
                    bytesPerSecond = 0L,
                    message = "Deleted ${idx + 1} of $total (${speed} items/s)"
                )
            )
            val result = provider.delete(entry.ref)
            if (result !is ProviderResult.Success) {
                throw IllegalStateException((result as? ProviderResult.Failure)?.message ?: "Failed to delete ${entry.metadata.name}")
            }
        }
    }
}
