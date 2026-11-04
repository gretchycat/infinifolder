package com.infinifolder

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import com.infinifolder.model.ProviderResult
import com.infinifolder.model.ResourceEntry
import com.infinifolder.model.ResourceRef
import com.infinifolder.shortcut.ShortcutInfo
import com.infinifolder.shortcut.WebMetadataResolver
import com.infinifolder.storage.ContentRevision
import com.infinifolder.storage.LocalStorageProvider
import com.infinifolder.storage.StorageProvider
import com.infinifolder.viewer.MediaSourceAdapter
import java.io.File
import java.io.IOException
import io.github.gretchycat.infinistream.readBytes as readStreamBytes

/** Public .iflink directory; existing storage/link APIs own all file operations. Call on a worker. */
internal class PinBoardDirectory(
    private val context: Context, private val preferences: SharedPreferences,
    private val local: LocalStorageProvider, private val provider: StorageProvider,
    private val shortcutReader: (ResourceEntry) -> ShortcutInfo?
) {
    val file = File(local.store.root, "InfiniFolder/Pin board")
    val uri: Uri = local.uriFor(file)
    fun isRoot(candidate: Uri?): Boolean = candidate != null && local.owns(candidate) && runCatching {
        local.fileFor(candidate).toPath().normalize() == file.toPath().normalize()
    }.getOrDefault(false)
    fun contains(uri: Uri): Boolean = local.owns(uri) && runCatching {
        local.fileFor(uri).toPath().normalize().parent == file.toPath().normalize()
    }.getOrDefault(false)
    fun migrated() = preferences.getBoolean("pin_board_directory_migrated", false)
    private fun <T> ProviderResult<T>.value(): T = when (this) {
        is ProviderResult.Success -> value
        is ProviderResult.Failure -> throw IOException(message, cause)
        else -> throw IOException("Pin board operation is unavailable")
    }
    private fun ref(uri: Uri): ResourceRef = provider.refForUri(uri) ?: throw IOException("Pin board storage is unavailable")

    private fun ensureDirectory() {
        var parent = ref(local.rootUri)
        for (name in listOf("InfiniFolder", "Pin board")) {
            val childFile = if (name == "InfiniFolder") file.parentFile!! else file
            val childRef = ref(local.uriFor(childFile))
            val existing = provider.stat(childRef)
            val entry = if (existing is ProviderResult.Success) existing.value
                else provider.createDirectory(parent, name).value()
            if (!entry.metadata.isDirectory) throw IOException("${entry.metadata.name} is not a directory")
            parent = entry.ref
        }
    }

    private fun shortcut(entry: ResourceEntry): ShortcutInfo? {
        if (entry.metadata.isDirectory || !ShortcutInfo.isShortcut(entry.metadata.name, entry.metadata.mimeType)) return null
        return runCatching { shortcutReader(entry) }.getOrNull()
    }

    private fun entries(): List<ResourceEntry> = provider.list(ref(uri)).value()
        .filterNot { it.metadata.isHidden || it.metadata.name.startsWith(".") }

    private fun pin(entry: ResourceEntry): PinnedContainer {
        val link = shortcut(entry)
        val target = link?.targetUri?.let { runCatching { Uri.parse(it) }.getOrNull() }
            ?.takeIf { !it.scheme.isNullOrBlank() } ?: entry.uri
        return PinnedContainer(entry, target, link?.displayNameOverride,
            entry.metadata.isDirectory || link?.targetIsDirectory == true || com.infinifolder.archive.ArchiveUri.isArchiveUri(target))
    }

    @Synchronized fun load(legacy: List<PinnedContainer>): List<PinnedContainer> {
        ensureDirectory()
        if (!migrated()) {
            val targets = entries().map { pin(it).destination.toString() }.toMutableSet()
            legacy.forEach { old ->
                if (targets.add(old.destination.toString())) addLink(old.entry, old.destination, old.name)
            }
            // Retain the original preference payload as a migration backup. Only the
            // directory is authoritative after every legacy link was saved successfully.
            preferences.edit().putBoolean("pin_board_directory_migrated", true).apply()
        }
        return entries().sortedWith(compareBy<ResourceEntry> { it.metadata.name.lowercase(java.util.Locale.ROOT) }
            .thenBy { it.metadata.name }).map(::pin)
    }

    @Synchronized fun add(entry: ResourceEntry, destination: Uri, label: String): Boolean {
        ensureDirectory()
        if (entries().any { pin(it).destination == destination }) return false
        addLink(entry, destination, label)
        return true
    }

    @Synchronized fun addDropped(entry: ResourceEntry): Boolean {
        val link = shortcut(entry)
        if (!entry.metadata.isDirectory && ShortcutInfo.isShortcut(entry.metadata.name, entry.metadata.mimeType) && link == null)
            throw IOException("Cannot read this link's target")
        val target = link?.targetUri?.let(Uri::parse) ?: entry.uri
        require(!target.scheme.isNullOrBlank()) { "The link target needs a URI" }
        val label = link?.displayNameOverride?.takeIf { it.isNotBlank() } ?: PinnedContainer(entry, target).name
        return add(entry, target, label)
    }

    private fun addLink(entry: ResourceEntry, destination: Uri, label: String) {
        require(!com.infinifolder.storage.ContainerUris.hasPassword(destination)) {
            "Use saved connection credentials before pinning this target"
        }
        val original = shortcut(entry)
        val info = (original ?: ShortcutInfo(targetUri = destination.toString(),
            targetMimeType = entry.metadata.mimeType,
            targetIsDirectory = entry.metadata.isDirectory || com.infinifolder.archive.ArchiveUri.isArchiveUri(destination))).copy(
            targetUri = destination.toString(), displayNameOverride = label)
        val stem = WebMetadataResolver.sanitizeFileName(label, "Link").trimStart('.').ifBlank { "Link" }
        val existing = entries().map { it.metadata.name.lowercase(java.util.Locale.ROOT) }.toSet()
        var name = stem + ShortcutInfo.SHORTCUT_EXTENSION
        var index = 2
        while (name.lowercase(java.util.Locale.ROOT) in existing) name = "$stem (${index++})${ShortcutInfo.SHORTCUT_EXTENSION}"
        val bytes = info.toJson().toByteArray(Charsets.UTF_8)
        require(bytes.size <= 1024 * 1024) { "The link exceeds the supported metadata size" }
        val created = provider.createFile(ref(uri), name, ShortcutInfo.SHORTCUT_MIME).value()
        try {
            MediaSourceAdapter.saveContent(context, created.uri, bytes, ContentRevision.of(ByteArray(0))).value()
        } catch (error: Exception) {
            provider.delete(created.ref)
            throw error
        }
    }

    @Synchronized fun remove(destination: Uri) {
        entries().filter { pin(it).destination == destination }.forEach { entry ->
            require(!entry.metadata.isDirectory && ShortcutInfo.isShortcut(entry.metadata.name, entry.metadata.mimeType)) {
                "Open the pin board directory to remove regular files or folders"
            }
            provider.delete(entry.ref).value()
        }
    }

    /** Keep the filename and the displayed link label in step for ordinary Rename. */
    fun renamed(entry: ResourceEntry) {
        if (!contains(entry.uri)) return
        val original = provider.openRead(entry.ref).value().use { readStreamBytes(it, 1024 * 1024) }
        val info = ShortcutInfo.fromJson(original.toString(Charsets.UTF_8)) ?: return
        val label = entry.metadata.name.substringBeforeLast('.').ifBlank { "Link" }
        MediaSourceAdapter.saveContent(context, entry.uri,
            info.copy(displayNameOverride = label).toJson().toByteArray(Charsets.UTF_8), ContentRevision.of(original)).value()
    }
}
