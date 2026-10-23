package com.infinifolder.ops

import android.net.Uri
import com.infinifolder.apps.AppCategories
import com.infinifolder.apps.AppsUri
import com.infinifolder.model.ProviderResult
import com.infinifolder.model.ResourceEntry
import com.infinifolder.model.ResourceRef
import com.infinifolder.shortcut.ShortcutInfo
import com.infinifolder.storage.StorageProvider
import com.infinifolder.storage.StorageSafety
import java.security.MessageDigest
import java.io.IOException
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
            val children = StorageSafety.complete(provider.list(parentRef))
            run {
                for (child in children) {
                    if (isCancelled()) return
                    if (child.metadata.isDirectory && !child.metadata.isSymbolicLink) {
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
            if (entry.metadata.isDirectory && !entry.metadata.isSymbolicLink) {
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
            if (entry.metadata.isDirectory && !entry.metadata.isSymbolicLink) {
                if (entry.ref.providerId == "apps" || AppsUri.isCategoryUri(entry.uri)) {
                    // Copying an app category creates lightweight shortcut files, not full APKs
                    return
                }
                for (child in StorageSafety.complete(provider.list(entry.ref))) count(child)
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
        isCancelled: () -> Boolean,
        createLinks: Boolean = false
    ): List<ResourceRef> {
        var activeProvider = provider
        if (move && createLinks) throw IOException("Creating shortcuts never removes source entries")
        val destRef = resourceRef(destinationFolderUri)
        var totalBytes: Long? = null
        var transferredBytes = 0L
        var itemsDone = 0
        val totalItems = entries.size
        fun phase(message: String, item: String? = null) {
            update(OperationUpdate(bytes = transferredBytes, totalBytes = totalBytes,
                itemsProcessed = itemsDone, totalItems = totalItems, currentItem = item,
                message = message, indeterminate = true))
        }
        fun <T> timed(label: String, item: String? = null, work: () -> T): T {
            phase(label, item)
            val start = System.nanoTime()
            var success = false
            try { return work().also { success = it !is ProviderResult.Failure && it !is ProviderResult.Unsupported } }
            finally {
                com.infinifolder.storage.DiagnosticLog.record("transfer.stage",
                    "stage=$label item=$item elapsedMs=${(System.nanoTime() - start) / 1_000_000} success=$success")
            }
        }
        val destinationContainers = mutableSetOf<ResourceRef>()
        var backing = activeProvider.backingResource(destRef)
        while (backing != null) {
            if (!destinationContainers.add(backing) || destinationContainers.size > 16)
                throw IOException("Invalid container dependency chain")
            backing = activeProvider.backingResource(backing)
        }
        val destinationBacking = activeProvider.backingResource(destRef)
        if (move && destinationContainers.isNotEmpty()) {
            val sameContainerSources = entries.all { source ->
                source.ref.providerId == "archive" && activeProvider.backingResource(source.ref) == destinationBacking
            }
            if (!sameContainerSources || destinationContainers.size != 1) throw IOException(
                "Moving into a container is supported only when every source is inside that same container. Use Copy for other sources; originals retained.")
        }
        val shortcutPayloads = mutableMapOf<ResourceRef, ByteArray>()
        val sourceChildren = mutableMapOf<ResourceRef, List<ResourceEntry>>()
        val scanning = mutableSetOf<ResourceRef>()
        fun scan(entry: ResourceEntry): ResourceEntry {
            if (isCancelled()) throw InterruptedException("Transfer cancelled")
            if (createLinks) {
                val resolved = if (entry.metadata.isSymbolicLink)
                    (com.infinifolder.storage.LinkResolver.resolve(activeProvider, entry) as? ProviderResult.Success)?.value else null
                val shortcut = ShortcutInfo(
                    targetUri = (resolved?.uri ?: entry.uri).toString(),
                    targetPath = entry.metadata.linkTarget,
                    displayNameOverride = entry.metadata.name,
                    targetMimeType = resolved?.mime ?: entry.metadata.mimeType,
                    targetIsDirectory = resolved?.entry?.metadata?.isDirectory ?: entry.metadata.isDirectory
                )
                shortcutPayloads[entry.ref] = shortcut.toJson().toByteArray(Charsets.UTF_8)
                com.infinifolder.storage.DiagnosticLog.record("transfer.link-shortcut",
                    "sourceProvider=${entry.ref.providerId} name=${entry.metadata.name} originalRetained=true")
                return entry.copy(metadata = entry.metadata.copy(isDirectory = false, isSymbolicLink = false))
            }
            if (entry.metadata.isSymbolicLink) {
                if (move) throw IOException("Moving folders containing symbolic links requires native link preservation; originals retained. Use Copy or Link to instead.")
                val resolved = when (val result = com.infinifolder.storage.LinkResolver.resolve(activeProvider, entry)) {
                    is ProviderResult.Success -> result.value.entry ?: throw IOException("The symbolic-link target is not a copyable storage resource")
                    is ProviderResult.Failure -> throw IOException("Cannot copy symbolic-link target '${entry.metadata.name}': ${result.message}", result.cause)
                    is ProviderResult.Unsupported -> throw IOException("Cannot resolve symbolic-link target '${entry.metadata.name}'")
                }
                // Keep the selected link's name, but use the actual target identity for reads,
                // ancestry/cycle checks, space accounting and copy verification.
                return scan(resolved.copy(metadata = resolved.metadata.copy(name = entry.metadata.name)))
            }
            if (entry.ref == destRef) throw IOException("Cannot transfer a folder into itself")
            if (entry.ref in destinationContainers) throw IOException("Cannot copy a container into its own mounted contents; original retained")
            if (entry.metadata.isDirectory) {
                if (!scanning.add(entry.ref)) throw IOException("Cyclic folder or symbolic-link reference")
                val children = StorageSafety.complete(activeProvider.list(entry.ref)).map(::scan)
                sourceChildren[entry.ref] = children
                scanning.remove(entry.ref)
            }
            return entry
        }
        val transferEntries = timed("Checking source folders") { entries.map(::scan) }
        if (move) {
            fun containsSource(ref: ResourceRef, target: ResourceRef, visited: MutableSet<ResourceRef>): Boolean {
                if (ref == target) return true
                if (!visited.add(ref)) return false
                return sourceChildren[ref].orEmpty().any { child ->
                    child.metadata.isDirectory && !child.metadata.isSymbolicLink &&
                        containsSource(child.ref, target, visited)
                }
            }
            if (transferEntries.any { source ->
                    source.metadata.isDirectory && containsSource(source.ref, destRef, mutableSetOf())
                }) throw IOException("Cannot move a folder into itself or one of its subfolders; sources retained")
        }
        var fileSystem = timed("Checking destination filesystem") {
            (activeProvider.fileSystemInfo(destRef) as? ProviderResult.Success)?.value
        }
        com.infinifolder.storage.DiagnosticLog.record("transfer.filesystem",
            "destination=${destRef.documentId} type=${fileSystem?.type ?: "unknown"} " +
                "maxFileSizeBytes=${fileSystem?.maxFileSizeBytes} availableBytes=${fileSystem?.availableBytes} " +
                "readOnly=${fileSystem?.readOnly} evidence=${fileSystem?.evidence}")
        fun requestedDestinationName(entry: ResourceEntry): String = when {
            shortcutPayloads.containsKey(entry.ref) -> entry.metadata.name + ShortcutInfo.SHORTCUT_EXTENSION
            entry.ref.providerId == "apps" && !entry.metadata.name.endsWith(".apk", ignoreCase = true) -> entry.metadata.name + ".apk"
            else -> entry.metadata.name
        }
        fun transferSize(entry: ResourceEntry): Long? = shortcutPayloads[entry.ref]?.size?.toLong() ?: entry.metadata.sizeBytes

        fun validateSize(entry: ResourceEntry) {
            val requested = requestedDestinationName(entry)
            val compatible = fileSystem?.compatibleName(requested) ?: requested
            StorageSafety.validateName(compatible)
            fileSystem?.requireName(compatible)
            if (entry.metadata.isDirectory && !entry.metadata.isSymbolicLink) sourceChildren.getValue(entry.ref).forEach(::validateSize)
            else fileSystem?.requireFileFits(requested, transferSize(entry))
        }
        // All known file sizes are checked before the first create, even when a later tree entry is oversized.
        transferEntries.forEach(::validateSize)
        if (fileSystem?.readOnly == true) throw IOException("The destination filesystem is mounted read-only. Source folders retained.")
        fun checkCapacity() {
            val allocationUnit = fileSystem?.allocationUnitBytes?.takeIf { it > 0 } ?: 1L
            fun requiredSpace(entry: ResourceEntry): Long {
                if (entry.metadata.isDirectory && !entry.metadata.isSymbolicLink) {
                    return sourceChildren.getValue(entry.ref).fold(if (allocationUnit > 1) allocationUnit else 0L) { sum, child ->
                        Math.addExact(sum, requiredSpace(child))
                    }
                }
                val bytes = transferSize(entry)?.coerceAtLeast(0L) ?: 0L
                if (bytes == 0L) return 0L
                return Math.multiplyExact(Math.addExact((bytes - 1) / allocationUnit, 1L), allocationUnit)
            }
            val requiredBytes = transferEntries.fold(0L) { sum, entry -> Math.addExact(sum, requiredSpace(entry)) }
            val availableBytes = fileSystem?.availableBytes
            if (availableBytes != null && requiredBytes > availableBytes) {
                val number = java.text.NumberFormat.getIntegerInstance()
                val message = "Not enough space on ${fileSystem?.displayName}: this copy needs at least " +
                    "${number.format(requiredBytes)} bytes, but only ${number.format(availableBytes)} bytes are free. " +
                    "No destination entries were created. Sources retained."
                com.infinifolder.storage.DiagnosticLog.record("transfer.capacity-rejected", message)
                throw IOException(message)
            }
        }
        checkCapacity()
        val batch = when (val result = timed("Preparing destination batch") { activeProvider.beginWriteBatch(destRef, isCancelled) }) {
            is ProviderResult.Success -> result.value
            is ProviderResult.Unsupported -> null
            is ProviderResult.Failure -> throw IOException(result.message, result.cause)
        }
        try {
            if (batch != null) {
                activeProvider = batch.provider
                val snapshotInfo = activeProvider.fileSystemInfo(destRef)
                when (snapshotInfo) {
                    is ProviderResult.Success -> fileSystem = snapshotInfo.value
                    is ProviderResult.Failure -> throw IOException("Cannot verify container limits: ${snapshotInfo.message}", snapshotInfo.cause)
                    is ProviderResult.Unsupported -> Unit
                }
                transferEntries.forEach(::validateSize)
                checkCapacity()
            }
            val destinationNames = mutableMapOf<ResourceRef, MutableSet<String>>()
            destinationNames[destRef] = timed("Checking destination") {
                StorageSafety.complete(activeProvider.list(destRef)).map { it.metadata.name.lowercase(java.util.Locale.ROOT) }.toMutableSet()
            }

            fun availableName(parent: ResourceRef, original: String): String {
                val compatible = fileSystem?.compatibleName(original) ?: original
                StorageSafety.validateName(compatible)
                fileSystem?.requireName(compatible)
                if (compatible != original) com.infinifolder.storage.DiagnosticLog.record(
                    "transfer.filename-converted", "filesystem=${fileSystem?.type} original=$original destination=$compatible")
                val names = destinationNames.getOrPut(parent) {
                    StorageSafety.complete(activeProvider.list(parent)).map { it.metadata.name.lowercase(java.util.Locale.ROOT) }.toMutableSet()
                }
                if (names.add(compatible.lowercase(java.util.Locale.ROOT))) return compatible
                val dot = compatible.lastIndexOf('.').takeIf { it > 0 } ?: compatible.length
                var index = 2
                var candidate: String
                do {
                    candidate = fileSystem?.collisionName(compatible, index)
                        ?: (compatible.substring(0, dot) + " ($index)" + compatible.substring(dot))
                    fileSystem?.requireName(candidate)
                    index++
                } while (candidate.lowercase(java.util.Locale.ROOT) in names)
                names.add(candidate.lowercase(java.util.Locale.ROOT))
                return candidate
            }

            val sourceDigests = mutableMapOf<ResourceRef, ByteArray>()
            fun digest(ref: ResourceRef): ByteArray {
                val opened = activeProvider.openRead(ref)
                if (opened !is ProviderResult.Success) throw IOException((opened as? ProviderResult.Failure)?.message ?: "Cannot verify file")
                return opened.value.use { input ->
                    val hash = MessageDigest.getInstance("SHA-256")
                    val buffer = ByteArray(128 * 1024)
                    while (true) {
                        if (isCancelled()) throw InterruptedException("Transfer cancelled")
                        val n = input.read(buffer)
                        if (n < 0) break
                        hash.update(buffer, 0, n)
                    }
                    hash.digest()
                }
            }

            fun verifySource(entry: ResourceEntry) {
                if (isCancelled()) throw InterruptedException("Transfer cancelled")
                if (entry.metadata.isDirectory && !entry.metadata.isSymbolicLink) {
                    val before = sourceChildren.getValue(entry.ref)
                    val after = StorageSafety.complete(activeProvider.list(entry.ref))
                    fun signature(items: List<ResourceEntry>) = items.associate { it.ref to it.metadata }
                    if (signature(before) != signature(after)) throw IOException("Source folder changed; original retained")
                    before.forEach(::verifySource)
                } else if (entry.ref.providerId != "apps") {
                    if (!digest(entry.ref).contentEquals(sourceDigests.getValue(entry.ref))) {
                        throw IOException("Source changed during transfer; original retained")
                    }
                }
            }
            fun plannedBytes(entry: ResourceEntry): Long = when {
                shortcutPayloads.containsKey(entry.ref) -> transferSize(entry) ?: 0L
                entry.metadata.isDirectory && (entry.ref.providerId == "apps" || AppsUri.isCategoryUri(entry.uri)) -> 0L
                entry.metadata.isDirectory -> sourceChildren.getValue(entry.ref).sumOf(::plannedBytes)
                else -> entry.metadata.sizeBytes ?: 0L
            }
            totalBytes = transferEntries.sumOf(::plannedBytes).takeIf { it > 0 }
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
                        message = "${if (move) "Moving" else "Copying"} $currentItem",
                        indeterminate = false
                    )
                )
            }

            fun copyFileWithProgress(
                source: ResourceEntry,
                destParentRef: ResourceRef
            ): ResourceEntry {
                if (isCancelled()) throw InterruptedException("Transfer cancelled")
                val requestedName = requestedDestinationName(source)
                val shortcutPayload = shortcutPayloads[source.ref]
                val targetName = availableName(destParentRef, requestedName)
                fileSystem?.requireName(targetName)
                reportProgress(targetName, 0L)

                val createResult = timed("Creating destination: $targetName", targetName) {
                    activeProvider.createFile(destParentRef, targetName,
                        if (shortcutPayload != null) ShortcutInfo.SHORTCUT_MIME else source.metadata.mimeType)
                }
                if (createResult !is ProviderResult.Success) {
                    throw IllegalStateException((createResult as? ProviderResult.Failure)?.message ?: "Failed to create $targetName")
                }
                val createdEntry = createResult.value
                destinationNames.getValue(destParentRef).add(createdEntry.metadata.name.lowercase(java.util.Locale.ROOT))

                val readResult = timed("Opening source: $targetName", targetName) {
                    if (shortcutPayload != null) ProviderResult.Success<InputStream>(shortcutPayload.inputStream())
                    else activeProvider.openRead(source.ref)
                }
                if (readResult !is ProviderResult.Success) {
                    try { activeProvider.delete(createdEntry.ref) } catch (_: Exception) {}
                    throw IllegalStateException((readResult as? ProviderResult.Failure)?.message ?: "Failed to open $targetName")
                }

                val writeResult = timed("Opening destination: $targetName", targetName) { activeProvider.openWrite(createdEntry.ref) }
                if (writeResult !is ProviderResult.Success) {
                    readResult.value.close()
                    try { activeProvider.delete(createdEntry.ref) } catch (_: Exception) {}
                    throw IllegalStateException((writeResult as? ProviderResult.Failure)?.message ?: "Failed to open destination for $targetName")
                }

                val buffer = ByteArray(128 * 1024)
                val hash = MessageDigest.getInstance("SHA-256")
                var copiedBytes = 0L
                var sourceBytesRead = 0L
                com.infinifolder.storage.DiagnosticLog.record("transfer.start",
                    "name=${source.metadata.name} sourceProvider=${source.ref.providerId} destinationProvider=${createdEntry.ref.providerId} " +
                        "destination=${createdEntry.ref.documentId} expectedBytes=${transferSize(source)}")
                var stage = "reading source"
                val ioTimings = linkedMapOf<String, Long>()
                var failedIoStage: String? = null
                fun <T> timeIo(label: String, work: () -> T): T {
                    val started = System.nanoTime()
                    try { return work() }
                    catch (e: Exception) { if (failedIoStage == null) failedIoStage = label; throw e }
                    finally { ioTimings[label] = (ioTimings[label] ?: 0L) + System.nanoTime() - started }
                }
                val inputStream = object : java.io.FilterInputStream(readResult.value) {
                    override fun close() = timeIo("source.close") { super.close() }
                }
                val outputStream = object : OutputStream() {
                    override fun write(value: Int) = timeIo("destination.write") { writeResult.value.write(value) }
                    override fun write(bytes: ByteArray, offset: Int, length: Int) =
                        timeIo("destination.write") { writeResult.value.write(bytes, offset, length) }
                    override fun flush() = timeIo("destination.flush") { writeResult.value.flush() }
                    override fun close() {
                        stage = "closing destination"
                        phase("Finishing destination: $targetName", targetName)
                        timeIo("destination.close") { writeResult.value.close() }
                    }
                }
                try {
                    phase("Writing destination: $targetName", targetName)
                    inputStream.use { input ->
                        outputStream.use { output ->
                            try {
                            var r: Int
                            while (timeIo("source.read") { input.read(buffer) }.also { r = it } >= 0) {
                                if (isCancelled()) throw InterruptedException("Transfer cancelled")
                                sourceBytesRead += r
                                stage = "checking destination file-size limit"
                                fileSystem?.requireFileFits(source.metadata.name, copiedBytes + r.toLong())
                                stage = "writing destination"
                                output.write(buffer, 0, r)
                                stage = "reading source"
                                hash.update(buffer, 0, r)
                                copiedBytes += r
                                transferredBytes += r
                                bytesSinceLast += r
                                val now = System.currentTimeMillis()
                                if (copiedBytes == r.toLong() || now - lastRateTime >= 300) {
                                    val speed = (bytesSinceLast * 1000) / (now - lastRateTime).coerceAtLeast(1)
                                    lastRateTime = now
                                    bytesSinceLast = 0L
                                    reportProgress(targetName, speed)
                                }
                            }
                            stage = "flushing destination"
                            phase("Flushing destination: $targetName", targetName)
                            output.flush()
                            stage = "closing destination"
                            } catch (e: Exception) {
                                (writeResult.value as? com.infinifolder.storage.PendingWrite)?.abort()
                                throw e
                            }
                        }
                        stage = "closing source"
                    }
                    stage = "verifying copy"
                    if (transferSize(source) != null && copiedBytes != transferSize(source)) {
                        throw IOException("Source size changed or read was incomplete; original retained")
                    }
                    val expected = hash.digest()
                    if (!timed("Verifying copy: $targetName", targetName) { digest(createdEntry.ref) }.contentEquals(expected))
                        throw IOException("Copy verification failed; original retained")
                    sourceDigests[source.ref] = expected
                } catch (e: Exception) {
                    val cleaned = try { activeProvider.delete(createdEntry.ref) } catch (cleanup: Exception) {
                        ProviderResult.Failure(cleanup.message ?: "Cleanup failed", cleanup)
                    }
                    val cleanupNote = if (cleaned is ProviderResult.Success) "Partial destination removed" else
                        "Partial destination may remain: ${(cleaned as? ProviderResult.Failure)?.message ?: "cleanup unsupported"}"
                    val largeUnknown = fileSystem?.type == null &&
                        maxOf(transferSize(source) ?: 0L, sourceBytesRead) > com.infinifolder.storage.FileSystemInfo.FAT_MAX_FILE_SIZE
                    val destinationFailure = failedIoStage?.startsWith("destination.") == true
                    val sizeHint = if (largeUnknown && destinationFailure) " Destination filesystem type was not reported. " +
                        "If it uses FAT/FAT32, its single-file limit is 4 GiB minus 1 byte (4,294,967,295 bytes)." else ""
                    val detail = "${failedIoStage ?: stage} for '${source.metadata.name}' after $copiedBytes bytes; " +
                        "${source.ref.providerId} → ${createdEntry.ref.providerId}. $cleanupNote. Source retained.$sizeHint"
                    com.infinifolder.storage.DiagnosticLog.record("transfer.failed",
                        "$detail sourceBytesRead=$sourceBytesRead destination=${createdEntry.ref.documentId}", e)
                    if (e is InterruptedException) throw e
                    throw IOException("$detail ${e.message}", e)
                } finally {
                    com.infinifolder.storage.DiagnosticLog.record("transfer.io",
                        "name=$targetName sourceBytesRead=$sourceBytesRead destinationBytesWritten=$copiedBytes " +
                            ioTimings.entries.joinToString(" ") { "${it.key}Ms=${it.value / 1_000_000}" })
                }
                return createdEntry
            }

            fun createDestinationDirectory(source: ResourceEntry, parent: ResourceRef): ResourceEntry {
                val name = availableName(parent, source.metadata.name)
                fileSystem?.requireName(name)
                val result = timed("Creating folder: $name", name) { activeProvider.createDirectory(parent, name) }
                if (result is ProviderResult.Success) {
                    destinationNames.getValue(parent).add(result.value.metadata.name.lowercase(java.util.Locale.ROOT))
                    // This directory was just created by this transfer, so it
                    // is known to be empty. Seeding the cache avoids a full
                    // archive-tree listing the first time we add its children.
                    destinationNames[result.value.ref] = mutableSetOf()
                    return result.value
                }
                val failure = result as? ProviderResult.Failure
                val message = "Cannot create destination folder '$name' (${parent.providerId}): " +
                    (failure?.message ?: "Folder creation is unsupported") +
                    ". File copying into this folder has not started; this source folder was retained."
                com.infinifolder.storage.DiagnosticLog.record("transfer.directory.failed",
                    "$message destination=${parent.documentId} filesystem=${fileSystem?.type} " +
                        "availableBytes=${fileSystem?.availableBytes} readOnly=${fileSystem?.readOnly}", failure?.cause)
                // A provider may fail after partially creating a directory. Never delete/retry by guessed name.
                throw IOException(message, failure?.cause)
            }

            fun copyAppCategoryAsLinks(
                sourceDir: ResourceEntry,
                destParentRef: ResourceRef
            ): ResourceEntry {
                if (isCancelled()) throw InterruptedException("Transfer cancelled")
                val createdDir = createDestinationDirectory(sourceDir, destParentRef)
                val createdFresh = true
                try {
                    val categoryName = AppsUri.getCategoryName(sourceDir.uri) ?: sourceDir.metadata.name
                    // 1. Write the category icon to .icon.png inside the created directory
                    if (AppsUri.isCategoryUri(sourceDir.uri)) {
                        val iconBytes = AppCategories.renderCategoryIconPng(categoryName)
                        val iconCreate = activeProvider.createFile(createdDir.ref, ".icon.png", "image/png")
                        if (iconCreate is ProviderResult.Success) {
                            val iconWrite = activeProvider.openWrite(iconCreate.value.ref)
                            if (iconWrite is ProviderResult.Success) {
                                iconWrite.value.use { output ->
                                    output.write(iconBytes)
                                    output.flush()
                                }
                                transferredBytes += iconBytes.size
                            }
                        }
                    }

                    // 2. Populate directory with application shortcuts for every app in the category
                    val children = sourceChildren.getValue(sourceDir.ref)
                    run {
                        val usedNames = mutableSetOf<String>()
                        for (child in children) {
                            if (isCancelled()) throw InterruptedException("Transfer cancelled")
                            if (child.metadata.isDirectory && !child.metadata.isSymbolicLink) {
                                copyAppCategoryAsLinks(child, createdDir.ref)
                            } else {
                                val baseName = child.metadata.name.removeSuffix(".apk").removeSuffix(".lnk").removeSuffix(ShortcutInfo.SHORTCUT_EXTENSION).trim()
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
                                val createLink = activeProvider.createFile(createdDir.ref, linkFileName, ShortcutInfo.SHORTCUT_MIME)
                                if (createLink !is ProviderResult.Success) {
                                    throw IllegalStateException((createLink as? ProviderResult.Failure)?.message ?: "Failed to create $linkFileName")
                                }
                                val writeLink = activeProvider.openWrite(createLink.value.ref)
                                if (writeLink !is ProviderResult.Success) {
                                    try { activeProvider.delete(createLink.value.ref) } catch (_: Exception) {}
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
                        try { activeProvider.delete(createdDir.ref) } catch (_: Exception) {}
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
                val createdDir = createDestinationDirectory(sourceDir, destParentRef)
                try {
                    val children = sourceChildren.getValue(sourceDir.ref)
                    run {
                        for (child in children) {
                            if (isCancelled()) throw InterruptedException("Transfer cancelled")
                            if (child.metadata.isDirectory && !child.metadata.isSymbolicLink) {
                                copyTreeRecursively(child, createdDir.ref)
                            } else {
                                copyFileWithProgress(child, createdDir.ref)
                            }
                        }
                    }
                } catch (e: Exception) {
                    // CLEANUP: delete partial directory on cancellation or failure
                    try { activeProvider.delete(createdDir.ref) } catch (_: Exception) {}
                    throw e
                }
                return createdDir
            }

            val destinations = mutableListOf<ResourceRef>()
            for (entry in transferEntries) {
                if (isCancelled()) throw InterruptedException("Transfer cancelled")
                reportProgress(entry.metadata.name, 0L)
                val copied = if (entry.metadata.isDirectory && !entry.metadata.isSymbolicLink) {
                    copyTreeRecursively(entry, destRef)
                } else {
                    copyFileWithProgress(entry, destRef)
                }
                destinations += copied.ref
                if (move) {
                    timed("Rechecking source: ${entry.metadata.name}", entry.metadata.name) { verifySource(entry) }
                    if (isCancelled()) throw InterruptedException("Transfer cancelled")
                    val removed = timed("Removing verified original: ${entry.metadata.name}", entry.metadata.name) { activeProvider.delete(entry.ref) }
                    if (removed !is ProviderResult.Success) {
                        throw IOException("Copy completed, but original removal failed: " +
                            ((removed as? ProviderResult.Failure)?.message ?: "Delete unsupported"))
                    }
                }
                itemsDone++
                reportProgress(entry.metadata.name, 0L)
            }
            if (isCancelled()) throw InterruptedException("Transfer cancelled before container publication")
            if (batch != null) timed("Publishing container batch") { batch.commit() }
            return destinations
        } finally { batch?.close() }
    }

    fun batchDelete(
        entries: List<ResourceEntry>,
        provider: StorageProvider,
        update: (OperationUpdate) -> Unit,
        isCancelled: () -> Boolean
    ) {
        // Resolve every selected tree before deleting anything. A listing failure
        // must not leave a directory half deleted merely because it was unreadable.
        val ordered = mutableListOf<ResourceEntry>()
        val visited = mutableSetOf<String>()
        fun collect(entry: ResourceEntry) {
            if (isCancelled()) throw InterruptedException("Delete cancelled")
            val key = "${entry.ref.providerId}:${entry.ref.documentId}"
            if (!visited.add(key)) return
            if (entry.metadata.isDirectory && !entry.metadata.isSymbolicLink) {
                for (child in StorageSafety.complete(provider.list(entry.ref))) collect(child)
            }
            ordered += entry
        }
        entries.forEach(::collect)

        val total = ordered.size
        val startTime = System.currentTimeMillis()
        for ((idx, entry) in ordered.withIndex()) {
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
                throw IllegalStateException(
                    "Failed to delete ${entry.metadata.name}: " +
                        ((result as? ProviderResult.Failure)?.message ?: "Delete unsupported") +
                        ". Some earlier items may already have been deleted."
                )
            }
        }
    }
}
