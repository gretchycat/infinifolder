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
import java.util.Locale
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import io.github.gretchycat.infinistream.readBytes as readStreamBytes

/** Public .iflink directory; existing storage/link APIs own all file operations. Call on a worker. */
internal class PinBoardDirectory(
    private val context: Context, private val preferences: SharedPreferences,
    private val local: LocalStorageProvider, private val provider: StorageProvider,
    private val shortcutReader: (ResourceEntry) -> ShortcutInfo?,
    private val onLinksChanged: (Set<Uri>) -> Unit
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

    private fun sortedEntries() = entries().sortedWith(compareBy<ResourceEntry> { it.metadata.name.lowercase(Locale.ROOT) }
        .thenBy { it.metadata.name })

    fun isLink(entry: ResourceEntry) = !entry.metadata.isDirectory &&
        entry.metadata.name.endsWith(ShortcutInfo.SHORTCUT_EXTENSION, true)

    private fun orderNumber(entry: ResourceEntry) = ORDER_PREFIX.find(entry.metadata.name)?.groupValues?.get(1)?.toLongOrNull()
        ?.takeIf { it > 0 }

    private fun readableLinks() = sortedEntries().filter { isLink(it) && shortcut(it) != null }
        .sortedWith(compareBy<ResourceEntry> { orderNumber(it) ?: Long.MAX_VALUE }
            .thenBy { it.metadata.name.lowercase(Locale.ROOT) }.thenBy { it.metadata.name })

    private class Allocation(val names: MutableSet<String>, var last: Long)
    private fun allocation(entries: List<ResourceEntry>) = Allocation(
        entries.map { it.metadata.name.lowercase(Locale.ROOT) }.toMutableSet(),
        entries.filter(::isLink).mapNotNull(::orderNumber).maxOrNull() ?: 0L)

    data class DropResult(val created: Int, val existing: Int, val moved: Int, val errors: List<String>)

    private fun pin(entry: ResourceEntry): PinnedContainer {
        val link = shortcut(entry)
        val target = link?.targetUri?.let { runCatching { Uri.parse(it) }.getOrNull() }
            ?.takeIf { !it.scheme.isNullOrBlank() } ?: entry.uri
        return PinnedContainer(entry, target, link?.displayNameOverride,
            entry.metadata.isDirectory || link?.targetIsDirectory == true || com.infinifolder.archive.ArchiveUri.isArchiveUri(target))
    }

    @Synchronized fun load(legacy: List<PinnedContainer>): List<PinnedContainer> {
        ensureDirectory()
        recoverOrder()
        if (!migrated()) {
            val snapshot = entries()
            val targets = snapshot.map { pin(it).destination.toString() }.toMutableSet()
            val allocation = allocation(snapshot)
            legacy.forEach { old ->
                if (targets.add(old.destination.toString())) addLink(old.entry, old.destination, old.name, allocation)
            }
            // Retain the original preference payload as a migration backup. Only the
            // directory is authoritative after every legacy link was saved successfully.
            preferences.edit().putBoolean("pin_board_directory_migrated", true).apply()
        }
        // Filename order is the persisted order, including edits made outside the app.
        // Existing labels are preserved before adding prefixes to old link filenames.
        writeOrder(readableLinks())
        return sortedEntries().map(::pin)
    }

    @Synchronized fun add(entry: ResourceEntry, destination: Uri, label: String): Boolean {
        ensureDirectory()
        recoverOrder()
        if (entries().any { pin(it).destination == destination }) return false
        val order = readableLinks()
        val created = addLink(entry, destination, label)
        writeOrder(order + created)
        return true
    }

    /** Batch creation shares one directory snapshot and finishes with one order audit. */
    @Synchronized fun drop(items: List<ResourceEntry>, before: Uri?, swapWith: Uri?): DropResult {
        recoverOrder()
        val snapshot = entries()
        val byUri = snapshot.associateBy { it.uri }
        val allocation = allocation(snapshot)
        val targets = snapshot.map { pin(it).destination }.toMutableSet()
        val placed = mutableListOf<Uri>()
        val errors = mutableListOf<String>()
        var created = 0
        var existing = 0
        var moved = 0
        items.distinctBy { it.uri }.forEach { entry ->
            try {
                if (contains(entry.uri)) {
                    require(isLink(entry)) { "Only .iflink shortcuts can be reordered on the pin board" }
                    val current = byUri[entry.uri] ?: throw IOException("The dragged pin changed")
                    placed += current.uri
                    moved++
                } else {
                    val link = shortcut(entry)
                    if (!entry.metadata.isDirectory && ShortcutInfo.isShortcut(entry.metadata.name, entry.metadata.mimeType) && link == null)
                        throw IOException("Cannot read this link's target")
                    val target = link?.targetUri?.let(Uri::parse) ?: entry.uri
                    require(!target.scheme.isNullOrBlank()) { "The link target needs a URI" }
                    if (target in targets) existing++ else {
                        val label = link?.displayNameOverride?.takeIf { it.isNotBlank() } ?: PinnedContainer(entry, target).name
                        placed += addLink(entry, target, label, allocation).uri
                        targets += target
                        created++
                    }
                }
            } catch (error: Exception) {
                errors += "${entry.metadata.name}: ${error.message ?: "Cannot create link"}"
                com.infinifolder.storage.DiagnosticLog.record("browser.pin-board.drop-failed", entry.metadata.name, error)
            }
        }
        if (swapWith != null && moved == 1 && created == 0 && placed.size == 1) swap(placed.single(), swapWith)
        else reorder(placed, before)
        // Also audit a pure append: its visible order may already be correct but
        // a new maximum number can require wider padding on existing filenames.
        writeOrder(readableLinks())
        return DropResult(created, existing, moved, errors)
    }

    private fun addLink(entry: ResourceEntry, destination: Uri, label: String, allocation: Allocation = allocation(entries())): ResourceEntry {
        require(!com.infinifolder.storage.ContainerUris.hasPassword(destination)) {
            "Use saved connection credentials before pinning this target"
        }
        val original = shortcut(entry)
        val info = (original ?: ShortcutInfo(targetUri = destination.toString(),
            targetMimeType = entry.metadata.mimeType,
            targetIsDirectory = entry.metadata.isDirectory || com.infinifolder.archive.ArchiveUri.isArchiveUri(destination))).copy(
            targetUri = destination.toString(), displayNameOverride = label)
        val stem = WebMetadataResolver.sanitizeFileName(label, "Link").trimStart('.').take(48).ifBlank { "Link" }
        var name: String
        do {
            allocation.last = Math.addExact(allocation.last, 10L)
            name = "${allocation.last} $stem${ShortcutInfo.SHORTCUT_EXTENSION}"
        } while (!allocation.names.add(name.lowercase(Locale.ROOT)))
        val bytes = info.toJson().toByteArray(Charsets.UTF_8)
        require(bytes.size <= 1024 * 1024) { "The link exceeds the supported metadata size" }
        val created = provider.createFile(ref(uri), name, ShortcutInfo.SHORTCUT_MIME).value()
        try {
            MediaSourceAdapter.saveContent(context, created.uri, bytes, ContentRevision.of(ByteArray(0))).value()
        } catch (error: Exception) {
            provider.delete(created.ref)
            throw error
        }
        return provider.stat(created.ref).value()
    }

    @Synchronized fun remove(destination: Uri) {
        recoverOrder()
        entries().filter { pin(it).destination == destination }.forEach { entry ->
            require(!entry.metadata.isDirectory && ShortcutInfo.isShortcut(entry.metadata.name, entry.metadata.mimeType)) {
                "Open the pin board directory to remove regular files or folders"
            }
            provider.delete(entry.ref).value()
        }
    }

    /** Change only link metadata. Ordering filenames and unknown JSON fields stay intact. */
    @Synchronized fun displayName(entry: ResourceEntry): String {
        val bytes = provider.openRead(entry.ref).value().use { readStreamBytes(it, 1024 * 1024) }
        val link = ShortcutInfo.fromJson(bytes.toString(Charsets.UTF_8)) ?: throw IOException("Cannot read this link's target")
        return link.displayNameOverride?.takeIf { it.isNotBlank() }
            ?: entry.metadata.name.substringBeforeLast('.').replaceFirst(ORDER_PREFIX, "")
    }

    @Synchronized fun renameLabel(entry: ResourceEntry, label: String): ResourceEntry {
        require(contains(entry.uri) && isLink(entry)) { "Not a pin-board link" }
        require(label.isNotBlank()) { "A display name is required" }
        recoverOrder()
        val original = provider.openRead(entry.ref).value().use { readStreamBytes(it, 1024 * 1024) }
        val json = JSONObject(original.toString(Charsets.UTF_8))
        require(ShortcutInfo.fromJson(json.toString()) != null) { "Cannot read this link's target" }
        val bytes = json.put("displayNameOverride", label).toString(2).toByteArray(Charsets.UTF_8)
        require(bytes.size <= 1024 * 1024) { "The link exceeds the supported metadata size" }
        MediaSourceAdapter.saveContent(context, entry.uri,
            bytes, ContentRevision.of(original)).value()
        onLinksChanged(setOf(entry.uri))
        return provider.stat(entry.ref).value()
    }

    /** Move the selected links together, before an anchor; null appends at the end. */
    @Synchronized fun reorder(moving: List<Uri>, before: Uri?) {
        recoverOrder()
        val all = readableLinks()
        val byUri = all.associateBy { it.uri }
        val movingUris = moving.toSet()
        val selected = moving.distinct().map { uri -> byUri[uri]
            ?: throw IOException("A dragged link changed; refresh the pin board and try again") }
        if (selected.isEmpty()) return
        val remaining = all.filterNot { it.uri in movingUris }.toMutableList()
        // Dropping on the moving selection's own insertion boundary is a no-op.
        val anchor = if (before in moving) all.dropWhile { it.uri != before }.firstOrNull { it.uri !in moving }?.uri else before
        val index = anchor?.let { uri -> remaining.indexOfFirst { it.uri == uri }.takeIf { it >= 0 }
            ?: throw IOException("The insertion position changed; try again") } ?: remaining.size
        remaining.addAll(index, selected)
        if (remaining.map { it.uri } == all.map { it.uri }) return
        val changed = all.indices.filter { all[it].uri != remaining[it].uri }
        if (changed.size == 2) {
            writeOrder(remaining, all.map { orderNumber(it) ?: throw IOException("A pin-board filename needs auditing") })
            return
        }
        val lower = if (index == 0) 0L else orderNumber(remaining[index - 1])
        val upper = remaining.getOrNull(index + selected.size)?.let(::orderNumber)
        val step = if (lower != null && upper != null && upper > lower)
            minOf(10L, (upper - lower) / (selected.size + 1L)) else 10L
        if (lower == null || step < 1) {
            writeOrder(remaining, remaining.indices.map { (it + 1L) * 10L })
        } else {
            val assigned = remaining.mapIndexed { i, entry ->
                if (i in index until index + selected.size) Math.addExact(lower, Math.multiplyExact(i - index + 1L, step))
                else orderNumber(entry) ?: throw IOException("A pin-board filename needs auditing")
            }
            writeOrder(remaining, assigned)
        }
    }

    @Synchronized fun swap(first: Uri, second: Uri) {
        recoverOrder()
        if (first == second) return
        val order = readableLinks().toMutableList()
        val a = order.indexOfFirst { it.uri == first }
        val b = order.indexOfFirst { it.uri == second }
        require(a >= 0 && b >= 0) { "A pin changed; refresh the board and try again" }
        val numbers = order.map { orderNumber(it) ?: throw IOException("A pin-board filename needs auditing") }
        java.util.Collections.swap(order, a, b)
        writeOrder(order, numbers)
    }

    /** Audit filenames without closing valid gaps. A full reindex restores ten between every pin. */
    private fun writeOrder(order: List<ResourceEntry>, assigned: List<Long>? = null) {
        var previous = 0L
        var needsReindex = false
        val existing = order.map { entry ->
            val number = orderNumber(entry) ?: Math.addExact(previous, 10L)
            if (number <= previous) needsReindex = true
            previous = number
            number
        }
        val numbers = assigned ?: if (needsReindex) order.indices.map { (it + 1L) * 10L } else existing
        // Widen when necessary; keep existing padding to avoid renaming the board
        // merely because a high-numbered pin was removed.
        val width = maxOf((numbers.maxOrNull() ?: 0L).toString().length,
            order.maxOfOrNull { ORDER_PREFIX.find(it.metadata.name)?.groupValues?.get(1)?.length ?: 1 } ?: 1)
        val orderUris = order.map { it.uri }.toSet()
        val reserved = entries().filterNot { it.uri in orderUris }
            .map { it.metadata.name.lowercase(Locale.ROOT) }.toMutableSet()
        val id = UUID.randomUUID().toString()
        val moves = JSONArray()
        order.forEachIndexed { index, entry ->
            val fullStem = entry.metadata.name.substringBeforeLast('.').replaceFirst(ORDER_PREFIX, "").ifBlank { "Link" }
            val stem = fullStem.take(48)
            val info = shortcut(entry) ?: throw IOException("Cannot read ${entry.metadata.name}")
            if (info.displayNameOverride.isNullOrBlank()) renameLabel(entry, fullStem)
            val prefix = numbers[index].toString().padStart(width, '0')
            var name = "$prefix $stem${ShortcutInfo.SHORTCUT_EXTENSION}"
            var collision = 2
            while (!reserved.add(name.lowercase(Locale.ROOT))) name = "$prefix $stem (${collision++})${ShortcutInfo.SHORTCUT_EXTENSION}"
            if (name != entry.metadata.name) {
                moves.put(JSONObject().put("from", entry.metadata.name).put("to", name)
                    .put("temp", ".pin-order-$id-$index${ShortcutInfo.SHORTCUT_EXTENSION}"))
            }
        }
        if (moves.length() == 0) return
        // Persist the rename plan before touching names. Exclusive provider renames
        // never overwrite another entry; restart resumes an interrupted two-phase order.
        val journal = provider.createFile(ref(uri), ORDER_JOURNAL, "application/json").value()
        val plan = JSONObject().put("version", 1).put("staged", false).put("moves", moves)
        val bytes = plan.toString().toByteArray(Charsets.UTF_8)
        try {
            require(bytes.size <= 1024 * 1024) { "Too many links to reorder together" }
            MediaSourceAdapter.saveContent(context, journal.uri, bytes, ContentRevision.of(ByteArray(0))).value()
        } catch (error: Exception) {
            // No renames have started yet, so an incomplete record can be removed.
            provider.delete(journal.ref)
            throw error
        }
        recoverOrder()
    }

    private fun child(name: String): ResourceRef {
        com.infinifolder.storage.StorageSafety.validateName(name)
        return ref(local.uriFor(File(file, name)))
    }

    private fun present(name: String): ResourceEntry? = when (val result = provider.stat(child(name))) {
        is ProviderResult.Success -> result.value
        is ProviderResult.Failure -> if (result.cause is java.io.FileNotFoundException) null else throw IOException(result.message, result.cause)
        else -> throw IOException("Cannot inspect pin-board ordering state")
    }

    private fun recoverOrder() {
        val journal = present(ORDER_JOURNAL) ?: return
        val bytes = provider.openRead(journal.ref).value().use { readStreamBytes(it, 1024 * 1024) }
        if (bytes.isEmpty()) { provider.delete(journal.ref).value(); return }
        val plan = JSONObject(bytes.toString(Charsets.UTF_8))
        require(plan.getInt("version") == 1) { "Unsupported pin-board ordering record" }
        val moves = plan.getJSONArray("moves")
        for (i in 0 until moves.length()) {
            val move = moves.getJSONObject(i)
            for (key in listOf("from", "to", "temp")) {
                val name = move.getString(key)
                com.infinifolder.storage.StorageSafety.validateName(name)
                require(name.endsWith(ShortcutInfo.SHORTCUT_EXTENSION, true)) { "Invalid pin-board ordering record" }
            }
            require(move.getString("temp").startsWith(".pin-order-")) { "Invalid pin-board ordering record" }
        }
        if (!plan.getBoolean("staged")) {
            for (i in 0 until moves.length()) {
                val move = moves.getJSONObject(i)
                if (present(move.getString("temp")) == null)
                    provider.rename(child(move.getString("from")), move.getString("temp")).value()
            }
            MediaSourceAdapter.saveContent(context, journal.uri,
                plan.put("staged", true).toString().toByteArray(Charsets.UTF_8), ContentRevision.of(bytes)).value()
        }
        for (i in 0 until moves.length()) {
            val move = moves.getJSONObject(i)
            val temp = present(move.getString("temp"))
            if (temp != null) provider.rename(temp.ref, move.getString("to")).value()
            else if (present(move.getString("to")) == null) throw IOException("A pin-board link disappeared while reordering")
        }
        provider.delete(journal.ref).value()
        val changed = mutableSetOf<Uri>()
        for (i in 0 until moves.length()) {
            val move = moves.getJSONObject(i)
            for (key in listOf("from", "to")) changed += local.uriFor(File(file, move.getString(key)))
        }
        onLinksChanged(changed)
    }

    companion object {
        private val ORDER_PREFIX = Regex("^(\\d+)[ _-]+")
        private const val ORDER_JOURNAL = ".pin-board-order.json"
    }
}
