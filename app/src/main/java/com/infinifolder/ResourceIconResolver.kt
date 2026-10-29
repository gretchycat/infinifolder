package com.infinifolder

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.infinifolder.apps.AppsUri
import com.infinifolder.model.*
import com.infinifolder.preview.*
import com.infinifolder.shortcut.ShortcutInfo
import com.infinifolder.storage.*
import com.infinifolder.theme.IconThemeManager

/** Resolves real resource artwork off the UI thread for floating cards and launcher shortcuts. */
class ResourceIconResolver(context: Context) {
    private val appContext = context.applicationContext
    private val artworkCache = ResourceArtworkCache(context)
    private val runtime = StorageRuntime.get(context)
    private val icons = CustomIconStore(context.filesDir)
    private val theme = IconThemeManager(context.applicationContext)
    private val decoder = ThumbnailDecoder(context.contentResolver) { uri ->
        runtime.provider.refForUri(uri)?.let { (runtime.provider.openRead(it) as? ProviderResult.Success)?.value }
    }
    data class Icon(val bitmap: Bitmap?, val fallback: String, val label: String, val directory: Boolean, val program: Boolean = false)
    private val linkInfo = android.util.LruCache<String, ShortcutInfo>(128)
    private val apkIcons = android.util.LruCache<String, Bitmap>(64)
    private val failedApkIcons = android.util.LruCache<String, Boolean>(128)
    private fun linkKey(entry: ResourceEntry) = "${entry.uri}:${entry.metadata.modifiedAtMillis}:${entry.metadata.sizeBytes}"
    fun peekShortcut(entry: ResourceEntry): ShortcutInfo? = linkInfo.get(linkKey(entry))
    fun sidecar(entry: ResourceEntry): ResourceEntry? = artworkCache.sidecar(entry)
    fun sidecar(uri: Uri, name: String = ""): ResourceEntry? = artworkCache.sidecar(uri, name)

    private fun fallbackLabel(entry: ResourceEntry): String =
        if (ShortcutInfo.isShortcut(entry.metadata.name, entry.metadata.mimeType))
            entry.metadata.name.substringBeforeLast('.').ifBlank { entry.metadata.name } else entry.metadata.name

    private fun linkLabel(entry: ResourceEntry, info: ShortcutInfo): String =
        info.displayNameOverride?.takeIf { it.isNotBlank() }
            ?: runCatching {
                val target = Uri.parse(info.targetUri)
                if (AppsUri.isAppUri(target)) AppsUri.getPackageName(target)?.let(runtime.apps::getAppLabel) else null
            }.getOrNull()
            ?: fallbackLabel(entry)

    private fun isArchiveShortcutTarget(info: ShortcutInfo): Boolean {
        val rawTarget = runCatching { Uri.parse(info.targetUri) }.getOrNull() ?: return false
        if (com.infinifolder.archive.ArchiveUri.isArchiveUri(rawTarget)) {
            return com.infinifolder.archive.ArchiveUri.getEntryPath(rawTarget).isEmpty()
        }
        if (rawTarget.path.orEmpty().endsWith('/')) return false
        val targetName = info.targetPath?.substringAfterLast('/')?.ifBlank { null }
            ?: rawTarget.lastPathSegment?.substringAfterLast('/').orEmpty()
        return com.infinifolder.archive.ArchiveFormats.isDefaultBrowsableArchive(targetName, info.targetMimeType.orEmpty())
    }

    private fun withLinkLabel(entry: ResourceEntry, icon: Icon): Icon {
        if (!ShortcutInfo.isShortcut(entry.metadata.name, entry.metadata.mimeType)) return icon
        val info = readShortcut(entry) ?: return icon
        return icon.copy(label = linkLabel(entry, info), directory = info.targetIsDirectory || isArchiveShortcutTarget(info))
    }

    fun cached(entry: ResourceEntry, size: Int): Icon? = artworkCache.load(entry, size)?.let {
        val shortcut = peekShortcut(entry)
        val isDir = (shortcut?.targetIsDirectory ?: entry.metadata.isDirectory) ||
            (shortcut != null && isArchiveShortcutTarget(shortcut))
        Icon(it.bitmap, FileIconView.fallback(entry.metadata),
            shortcut?.displayNameOverride?.takeIf { name -> name.isNotBlank() } ?: fallbackLabel(entry),
            isDir, program = it.program)
    }

    fun resolve(entry: ResourceEntry, size: Int, deferCacheWrite: Boolean = false): Icon {
        val started = android.os.SystemClock.uptimeMillis()
        try {
            return resolveCurrent(entry, size, deferCacheWrite)
        } finally {
            val elapsed = android.os.SystemClock.uptimeMillis() - started
            if (elapsed >= 500) DiagnosticLog.record("artwork.resolve.slow",
                "provider=${entry.ref.providerId} name=${entry.metadata.name} size=$size elapsedMs=$elapsed")
        }
    }

    private fun resolveCurrent(entry: ResourceEntry, size: Int, deferCacheWrite: Boolean): Icon {
        val saved = artworkCache.load(entry, size)
        val signature = signature(entry)
        if (saved != null && (!saved.generated || ResourceArtworkCache.fresh(saved, signature, size)))
            return withLinkLabel(entry, Icon(saved.bitmap, FileIconView.fallback(entry.metadata),
                entry.metadata.name, entry.metadata.isDirectory, program = saved.program))
        val resolved = resolve(entry, size, mutableSetOf())
        if (!deferCacheWrite && resolved.bitmap != null && resolved.program)
            artworkCache.save(entry, size, resolved.bitmap, signature, resolved.program)
        if (resolved.bitmap == null && saved != null && saved.signature != signature) artworkCache.invalidate(entry, size)
        return if (resolved.bitmap == null && saved != null && saved.signature == signature) resolved.copy(bitmap = saved.bitmap) else resolved
    }

    private fun signature(entry: ResourceEntry): String {
        return "${entry.metadata.modifiedAtMillis}:${entry.metadata.sizeBytes}:${theme.getActiveThemeId()}"
    }

    /** Called after the host installs the image; PNG persistence stays off the display path. */
    fun cacheDisplayed(entry: ResourceEntry, size: Int, icon: Icon) {
        val bitmap = icon.bitmap ?: return
        if (!icon.program) return
        val signature = signature(entry)
        val saved = ResourceArtworkCache.peekSaved(entry, size)
        if (saved != null && !saved.generated) return
        if (!ResourceArtworkCache.fresh(saved, signature, size) || saved?.persisted != true)
            artworkCache.save(entry, size, bitmap, signature, program = true)
    }

    private fun findDirectoryIconEntry(dirUri: Uri, dirRef: ResourceRef?): ResourceEntry? {
        artworkCache.sidecar(dirUri)?.let { return it }
        val known = ResourceIconSidecars.directory(dirUri)
        if (known != null) return known.entry
        val ref = dirRef ?: runtime.provider.refForUri(dirUri) ?: return null
        val contents = (runtime.provider.list(ref) as? ProviderResult.Success)?.value ?: return null
        ResourceIconSidecars.observe(contents, dirUri)
        return DirectoryIconResolver.findBestIcon(contents)
    }

    private fun resolve(entry: ResourceEntry, size: Int, seen: MutableSet<ResourceRef>): Icon {
        val base = Icon(null, FileIconView.fallback(entry.metadata), entry.metadata.name, entry.metadata.isDirectory)
        if (!seen.add(entry.ref) || seen.size > 16) return base
        if (seen.size > 1) {
            val saved = artworkCache.load(entry, size)
            if (saved != null && (!saved.generated || ResourceArtworkCache.fresh(saved, signature(entry), size))) {
                return base.copy(bitmap = saved.bitmap, program = saved.program)
            }
        }
        if (entry.ref.providerId == PartialDownloadsProvider.SCHEME) {
            // Discovery aliases are private records. Rendering their icons must
            // never reconnect to or request permission for the original source.
            val info = readShortcut(entry)
            return base.copy(fallback = "⬇", label = info?.displayNameOverride ?: fallbackLabel(entry))
        }
        if (ShortcutInfo.isShortcut(entry.metadata.name, entry.metadata.mimeType)) {
            val info = readShortcut(entry) ?: return base
            val custom = info.iconBase64?.let(CustomIconStore::decodeBase64Bitmap)
            val label = linkLabel(entry, info)
            val isArchiveTarget = isArchiveShortcutTarget(info)
            val effectiveIsDir = info.targetIsDirectory || isArchiveTarget
            if (custom != null) return base.copy(bitmap = custom, label = label, directory = effectiveIsDir,
                program = AppsUri.isAppUri(Uri.parse(info.targetUri)))
            val rawTarget = Uri.parse(info.targetUri)
            if (TermuxCommandLink.owns(rawTarget)) return base.copy(
                bitmap = runtime.apps.getAppIconBitmap("com.termux", size, useDiskCache = false),
                fallback = "⌨", label = label, program = true)
            val boundTarget = ContainerUris.resolve(rawTarget, runtime.connections.list()) ?: rawTarget
            val targetName = info.targetPath?.substringAfterLast('/')?.ifBlank { null }
                ?: rawTarget.lastPathSegment?.substringAfterLast('/').orEmpty()
            val target = if (isArchiveTarget && !com.infinifolder.archive.ArchiveUri.isArchiveUri(boundTarget)) {
                com.infinifolder.archive.ArchiveUri.toArchiveUri(boundTarget)
            } else {
                boundTarget
            }
            val ref = runtime.provider.refForUri(target)
            val metadata = ResourceMetadata(
                name = targetName,
                mimeType = info.targetMimeType.orEmpty(),
                sizeBytes = null,
                modifiedAtMillis = null,
                isDirectory = effectiveIsDir
            )
            // A saved server link is a local shortcut. Drawing it must not connect
            // to the server or enumerate its contents before the user opens it.
            if (ContainerUris.networkType(rawTarget) != null) {
                val themed = if (effectiveIsDir && !isArchiveTarget) theme.resolveFolderIcon(targetName, size)
                    else theme.resolveFileIcon(metadata, size)
                return base.copy(bitmap = themed, fallback = if (isArchiveTarget) "📦" else FileIconView.fallback(metadata),
                    label = label, directory = effectiveIsDir)
            }
            val targetEntry = ref?.let { ResourceEntry(it, metadata, target) }
            val resolved = targetEntry?.let { resolve(it, size, seen) }
                ?: base.copy(fallback = if (isArchiveTarget) "📦" else FileIconView.fallback(metadata), directory = effectiveIsDir)
            return resolved.copy(label = label, directory = effectiveIsDir)
        }
        if (entry.metadata.isSymbolicLink) {
            val target = (LinkResolver.resolve(runtime.provider, entry) as? ProviderResult.Success)?.value?.entry
            if (target != null) return resolve(target, size, seen).copy(label = entry.metadata.name)
        }
        if (AppsUri.isAppUri(entry.uri)) {
            val pkg = AppsUri.getPackageName(entry.uri)
            return base.copy(bitmap = pkg?.let { runtime.apps.getAppIconBitmap(it, size, useDiskCache = false) }, fallback = "📱", program = true)
        }
        if (AppsUri.isCategoryUri(entry.uri)) {
            return base.copy(fallback = runtime.apps.getCategoryIcon(AppsUri.getCategoryName(entry.uri).orEmpty()), directory = true)
        }
        if (entry.metadata.isDirectory) {
            val isArchiveRoot = com.infinifolder.archive.ArchiveUri.isArchiveUri(entry.uri) &&
                com.infinifolder.archive.ArchiveUri.getEntryPath(entry.uri).isEmpty()
            val candidate = findDirectoryIconEntry(entry.uri, entry.ref)
            val artwork = candidate?.uri?.let { decoder.decode(it, size) }
            val fallbackBitmap = if (isArchiveRoot) {
                val sourceUri = com.infinifolder.archive.ArchiveUri.getSourceUri(entry.uri)
                val sourceName = sourceUri?.lastPathSegment?.substringAfterLast('/') ?: entry.metadata.name
                val sourceMeta = entry.metadata.copy(name = sourceName, isDirectory = false)
                val mime = CustomIconStore.resolveMimeKey(sourceMeta)
                icons.getMimeIconBitmap(mime) { decoder.decode(Uri.fromFile(it), size) }
                    ?: theme.resolveFileIcon(sourceMeta, size)
            } else {
                theme.resolveFolderIcon(entry.metadata.name, size)
            }
            val fallbackGlyph = if (isArchiveRoot) "📦" else base.fallback
            return base.copy(bitmap = artwork ?: fallbackBitmap, fallback = fallbackGlyph, directory = true)
        }
        if (!com.infinifolder.archive.ArchiveUri.isArchiveUri(entry.uri) &&
            com.infinifolder.archive.ArchiveFormats.isDefaultBrowsableArchive(entry.metadata.name, entry.metadata.mimeType)) {
            val archiveUri = com.infinifolder.archive.ArchiveUri.toArchiveUri(entry.uri)
            val archiveRef = runtime.provider.refForUri(archiveUri)
            val candidate = findDirectoryIconEntry(archiveUri, archiveRef)
            val artwork = candidate?.uri?.let { decoder.decode(it, size) }
            if (artwork != null) {
                return base.copy(bitmap = artwork, fallback = "📦", directory = true)
            }
        }
        val mime = CustomIconStore.resolveMimeKey(entry.metadata)
        val custom = icons.getMimeIconBitmap(mime) { decoder.decode(Uri.fromFile(it), size) }
        val image = if (custom == null && ImageFormats.isImage(entry.metadata)) decoder.decode(entry.uri, size) else null
        val apk = if (custom == null && image == null &&
            com.infinifolder.mime.MimeRegistry.effective(entry.metadata.name, entry.metadata.mimeType) == "application/vnd.android.package-archive")
            resolveApkIcon(entry, size) else null
        return base.copy(bitmap = custom ?: image ?: apk ?: theme.resolveFileIcon(entry.metadata, size), program = apk != null)
    }

    private fun resolveApkIcon(entry: ResourceEntry, size: Int): Bitmap? {
        if (runtime.local.owns(entry.uri)) {
            return runtime.apps.getApkFileIconBitmap(runtime.local.fileFor(entry.uri).absolutePath, size)
        }
        val cacheKey = "${entry.uri}:${entry.metadata.modifiedAtMillis}:${entry.metadata.sizeBytes}:$size"
        apkIcons.get(cacheKey)?.let { return it }
        if (failedApkIcons.get(cacheKey) == true) return null
        val maxApkStageBytes = 256L * 1024L * 1024L
        if ((entry.metadata.sizeBytes ?: 0L) > maxApkStageBytes) return null
        val stageDir = java.io.File(appContext.cacheDir, "apk_icon_stage").apply { mkdirs() }
        val tempFile = runCatching { java.io.File.createTempFile("apk_", ".apk", stageDir) }.getOrNull() ?: return null
        return try {
            val stream = (runtime.provider.openRead(entry.ref) as? ProviderResult.Success)?.value ?: return null
            var totalBytes = 0L
            stream.use { input ->
                tempFile.outputStream().use { output ->
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        totalBytes += read
                        if (totalBytes > maxApkStageBytes) return null
                        output.write(buffer, 0, read)
                    }
                    output.flush()
                }
            }
            val bitmap = runtime.apps.getApkFileIconBitmap(tempFile.absolutePath, size)
            if (bitmap != null) {
                apkIcons.put(cacheKey, bitmap)
            } else {
                failedApkIcons.put(cacheKey, true)
            }
            bitmap
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { tempFile.delete() }
        }
    }

    private fun readShortcut(entry: ResourceEntry): ShortcutInfo? {
        peekShortcut(entry)?.let { return it }
        return runCatching {
        val stream = (runtime.provider.openRead(entry.ref) as? ProviderResult.Success)?.value ?: return null
        stream.use { input ->
            val bytes = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (bytes.size() + count > 1024 * 1024) return null
                bytes.write(buffer, 0, count)
            }
            ShortcutInfo.fromJson(bytes.toString("UTF-8"))?.also { linkInfo.put(linkKey(entry), it) }
        }
        }.getOrNull()
    }
}
