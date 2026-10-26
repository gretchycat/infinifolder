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
    private val artworkCache = ResourceArtworkCache(context)
    private val runtime = StorageRuntime.get(context)
    private val icons = CustomIconStore(context.filesDir)
    private val theme = IconThemeManager(context.applicationContext)
    private val decoder = ThumbnailDecoder(context.contentResolver) { uri ->
        runtime.provider.refForUri(uri)?.let { (runtime.provider.openRead(it) as? ProviderResult.Success)?.value }
    }
    data class Icon(val bitmap: Bitmap?, val fallback: String, val label: String, val directory: Boolean, val program: Boolean = false)
    private val linkInfo = android.util.LruCache<String, ShortcutInfo>(128)
    private fun linkKey(entry: ResourceEntry) = "${entry.uri}:${entry.metadata.modifiedAtMillis}:${entry.metadata.sizeBytes}"
    fun peekShortcut(entry: ResourceEntry): ShortcutInfo? = linkInfo.get(linkKey(entry))

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

    private fun withLinkLabel(entry: ResourceEntry, icon: Icon): Icon {
        if (!ShortcutInfo.isShortcut(entry.metadata.name, entry.metadata.mimeType)) return icon
        val info = readShortcut(entry) ?: return icon
        return icon.copy(label = linkLabel(entry, info), directory = info.targetIsDirectory)
    }

    fun cached(entry: ResourceEntry, size: Int): Icon? = artworkCache.load(entry, size)?.let {
        Icon(it.bitmap, FileIconView.fallback(entry.metadata),
            peekShortcut(entry)?.displayNameOverride?.takeIf { name -> name.isNotBlank() } ?: fallbackLabel(entry),
            peekShortcut(entry)?.targetIsDirectory ?: entry.metadata.isDirectory, program = it.program)
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
        if (!deferCacheWrite && resolved.program) resolved.bitmap?.let { artworkCache.save(entry, size, it, signature, program = true) }
        if (resolved.bitmap == null && saved != null && saved.signature != signature) artworkCache.invalidate(entry, size)
        return if (resolved.bitmap == null && saved != null && saved.signature == signature) resolved.copy(bitmap = saved.bitmap) else resolved
    }

    private fun signature(entry: ResourceEntry): String {
        return "${entry.metadata.modifiedAtMillis}:${entry.metadata.sizeBytes}:${theme.getActiveThemeId()}"
    }

    /** Called after the host installs the image; PNG persistence stays off the display path. */
    fun cacheDisplayed(entry: ResourceEntry, size: Int, icon: Icon) {
        if (!icon.program) return
        val bitmap = icon.bitmap ?: return
        val signature = signature(entry)
        val saved = ResourceArtworkCache.peekSaved(entry, size)
        if (saved != null && !saved.generated) return
        if (!ResourceArtworkCache.fresh(saved, signature, size) || saved?.persisted != true)
            artworkCache.save(entry, size, bitmap, signature, program = true)
    }

    private fun resolve(entry: ResourceEntry, size: Int, seen: MutableSet<ResourceRef>): Icon {
        val base = Icon(null, FileIconView.fallback(entry.metadata), entry.metadata.name, entry.metadata.isDirectory)
        if (!seen.add(entry.ref) || seen.size > 16) return base
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
            if (custom != null) return base.copy(bitmap = custom, label = label, directory = info.targetIsDirectory,
                program = AppsUri.isAppUri(Uri.parse(info.targetUri)))
            val target = Uri.parse(info.targetUri)
            val ref = runtime.provider.refForUri(target)
            val metadata = ResourceMetadata(info.targetPath?.substringAfterLast('/') ?: target.lastPathSegment.orEmpty(),
                info.targetMimeType.orEmpty(), null, null, info.targetIsDirectory)
            val targetEntry = ref?.let { ResourceEntry(it, metadata, target) }
            val resolved = targetEntry?.let { resolve(it, size, seen) } ?: base.copy(fallback = FileIconView.fallback(metadata))
            return resolved.copy(label = label)
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
            val known = ResourceIconSidecars.directory(entry)
            val candidate = if (known != null) known.entry else {
                val contents = (runtime.provider.list(entry.ref) as? ProviderResult.Success)?.value
                if (contents != null) ResourceIconSidecars.observe(contents, entry.uri)
                contents?.let(DirectoryIconResolver::findBestIcon)
            }
            val artwork = candidate?.uri?.let { decoder.decode(it, size) }
            return base.copy(bitmap = artwork ?: theme.resolveFolderIcon(entry.metadata.name, size))
        }
        val mime = CustomIconStore.resolveMimeKey(entry.metadata)
        val custom = icons.getMimeIconBitmap(mime) { decoder.decode(Uri.fromFile(it), size) }
        val image = if (custom == null && ImageFormats.isImage(entry.metadata)) decoder.decode(entry.uri, size) else null
        val apk = if (custom == null && image == null && runtime.local.owns(entry.uri) &&
            com.infinifolder.mime.MimeRegistry.effective(entry.metadata.name, entry.metadata.mimeType) == "application/vnd.android.package-archive")
            runtime.apps.getApkFileIconBitmap(runtime.local.fileFor(entry.uri).absolutePath, size) else null
        return base.copy(bitmap = custom ?: image ?: apk ?: theme.resolveFileIcon(entry.metadata, size), program = apk != null)
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
