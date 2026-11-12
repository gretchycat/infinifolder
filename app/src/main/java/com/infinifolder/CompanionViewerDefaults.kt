package com.infinifolder

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.content.pm.PackageManager
import com.infinifolder.archive.ArchiveFormats
import com.infinifolder.mime.MimeRegistry
import com.infinifolder.model.ResourceRef
import com.infinifolder.preview.AudioIconFormats
import com.infinifolder.viewer.ExternalAppDefaults
import com.infinifolder.viewer.MediaType
import com.infinifolder.viewer.MediaTypeDetector
import io.github.gretchycat.infinistream.CapabilityHandler
import io.github.gretchycat.infinistream.Operations
import io.github.gretchycat.infinistream.RuntimeCapabilities
import io.github.gretchycat.infinistream.ViewerTarget
import io.github.gretchycat.infiniscribe.ScribeTextFormats

/** Local routing defaults for the separately installed media and document apps. */
internal object CompanionViewerDefaults {
    private data class Target(val packageName: String, val activityName: String)
    private data class InitializationSnapshot(val media: ComponentName?, val scribe: ComponentName?, val capabilities: Long)

    private val media = Target("com.infinimedia", "com.infinimedia.InfiniMediaActivity")
    private val scribe = Target("com.infiniscribe", "com.infiniscribe.InfiniScribeActivity")
    private val removedMediaViewers = setOf(
        "com.infinifolder.viewer.AudioPlayerActivity",
        "com.infinifolder.viewer.VideoPlayerActivity",
    )
    private val removedScribeViewers = setOf(
        "com.infinifolder.viewer.TextViewerActivity",
        "com.infinifolder.viewer.Cp437ViewerActivity",
        "com.infinifolder.viewer.MarkdownViewerActivity",
        "com.infinifolder.viewer.HtmlViewerActivity",
        "com.infinifolder.viewer.CsvViewerActivity",
        "com.infinifolder.viewer.EpubViewerActivity",
        "com.infinifolder.viewer.FontViewerActivity",
        "com.infinifolder.viewer.PdfViewerActivity",
    )
    @Volatile private var initializedSnapshot: InitializationSnapshot? = null

    fun isScribeType(type: MediaType): Boolean = type in setOf(
        MediaType.TEXT, MediaType.MARKDOWN, MediaType.HTML, MediaType.PDF, MediaType.FONT,
        MediaType.EPUB, MediaType.EBOOK, MediaType.DOCUMENT, MediaType.SPREADSHEET,
    )

    /** Seed local defaults for installed app entry points while preserving saved choices. */
    @Synchronized fun initialize(context: Context) {
        val app = context.applicationContext
        val defaults = ExternalAppDefaults(app)
        val mediaComponent = installedComponent(app, media)
        val scribeComponent = installedComponent(app, scribe)
        val snapshot = InitializationSnapshot(mediaComponent, scribeComponent,
            io.github.gretchycat.infinistream.RuntimeCapabilities.installed().revision)
        if (initializedSnapshot == snapshot) return
        val components = mapOfNotNull(
            media to mediaComponent,
            scribe to scribeComponent,
        )
        if (components.isEmpty()) {
            initializedSnapshot = snapshot
            return
        }

        migrateRemovedInternalDefaults(app, defaults, components)

        MimeRegistry.associations().toSortedMap().forEach { (extension, mime) ->
            val name = "document.$extension"
            val target = targetFor(name, mime) ?: return@forEach
            val component = components[target] ?: return@forEach
            if (!mayAssociateWithoutIntentFilter(target.packageName) && !handles(app, component, name, mime)) return@forEach
            if (defaults.candidates(Intent.ACTION_VIEW, null, mime, name).isEmpty()) {
                defaults.remember(Intent.ACTION_VIEW, ExternalAppDefaults.EXTENSION, null, mime, name, component)
            }
        }

        // Extensionless source files can still be classified as text or audio
        // from their MIME or validated content signature.
        val typeMimes = (ScribeTextFormats.TEXT_MIME_TYPES + setOf("text/plain") +
            MimeRegistry.associations().values.filter { targetFor("document", it) == scribe } +
            MimeRegistry.associations().values.filter { AudioIconFormats.isAudio(it) || ArchiveFormats.isPlaylist("", it) })
            .map(MimeRegistry::normalize).toSortedSet()
        typeMimes.forEach { mime ->
            val target = targetFor("document", mime) ?: return@forEach
            val component = components[target] ?: return@forEach
            if (defaults.candidates(Intent.ACTION_VIEW, null, mime, "document").isNotEmpty()) return@forEach
            if (mayAssociateWithoutIntentFilter(target.packageName) || handles(app, component, "document", mime)) {
                defaults.remember(Intent.ACTION_VIEW, ExternalAppDefaults.TYPE, null, mime, "document", component)
            }
        }
        initializedSnapshot = snapshot
    }

    /** Add the companion as a local default only when this item has no saved choice. */
    fun ensureForEntry(context: Context, defaults: ExternalAppDefaults, ref: ResourceRef,
        name: String, mime: String): ComponentName? {
        val target = targetFor(name, mime) ?: return null
        val sourceKey = ExternalAppDefaults.sourceKey(ref)
        val existing = defaults.candidates(Intent.ACTION_VIEW, sourceKey, mime, name)
        if (existing.isNotEmpty()) {
            val staleCompanionChoice = existing.any {
                it.packageName == context.packageName && removedTarget(it.className) != null
            }
            if (!staleCompanionChoice) return null
            val replacement = installedComponent(context.applicationContext, target) ?: return null
            migrateRemovedInternalDefaults(context.applicationContext, defaults, mapOf(target to replacement))
            return replacement
        }
        val component = installedComponent(context.applicationContext, target) ?: return null
        if (!mayAssociateWithoutIntentFilter(target.packageName) &&
            !handles(context.applicationContext, component, name, mime)) return null
        val extension = ExternalAppDefaults.extensionOf(name)
        val scope = if (extension == null) ExternalAppDefaults.TYPE else ExternalAppDefaults.EXTENSION
        defaults.remember(Intent.ACTION_VIEW, scope,
            if (scope == ExternalAppDefaults.FILE) ExternalAppDefaults.sourceKey(ref) else null,
            mime, name, component)
        return component
    }

    /** Installed InfiniMedia entry point for explicit media actions from the browser. */
    fun installedMediaComponent(context: Context): ComponentName? =
        installedComponent(context.applicationContext, media)

    /** Installed InfiniScribe host, used by explicit text-view actions. */
    fun installedScribeComponent(context: Context): ComponentName? =
        installedComponent(context.applicationContext, scribe)

    internal fun defaultPackageFor(name: String, mime: String): String? = targetFor(name, mime)?.packageName

    /** A long-press source viewer is offered when another declared module owns the normal view. */
    internal fun hasTextBackupFor(name: String, mime: String): Boolean {
        val targets = matchingHandlers(name, mime).mapNotNull { it.viewerTarget() }.toSet()
        return ViewerTarget.INFINISCRIBE in targets &&
            targets.any { it == ViewerTarget.INFINIFOLDER || it == ViewerTarget.INFINIMEDIA }
    }

    /** The ANSI source capability asks the host for byte-preserving escape rendering. */
    internal fun isRawAnsiSource(name: String, mime: String): Boolean = matchingHandlers(name, mime).any { handler ->
        handler.viewerTarget() == ViewerTarget.INFINISCRIBE && handler.describe().attributes["sourceMode"] == "ansi"
    }

    /** Infinimedia is launched as an explicit component; MIME-filter visibility is not required. */
    internal fun mayAssociateWithoutIntentFilter(packageName: String): Boolean = packageName == media.packageName

    private fun targetFor(name: String, mime: String): Target? {
        val targets = matchingHandlers(name, mime).mapNotNull { it.viewerTarget() }.toSet()
        // For overlapping ANSI playback and source-text handlers, the media app
        // owns normal open; Scribe remains available as the long-press fallback.
        if (ViewerTarget.INFINIMEDIA in targets && ViewerTarget.INFINISCRIBE in targets) return media
        if (ViewerTarget.INFINIMEDIA in targets && ViewerTarget.INFINIFOLDER !in targets) return media
        if (name.endsWith(".mindnodetouch", true) || mime == "application/x-mindnode-touch") return scribe
        val type = MediaTypeDetector.detect(name, mime)
        if (ArchiveFormats.isPlaylist(name, mime) || type in setOf(MediaType.AUDIO, MediaType.VIDEO) ||
            AudioIconFormats.isAudio(MimeRegistry.guess(name))) return media
        return scribe.takeIf { isScribeType(type) }
    }

    private fun matchingHandlers(name: String, mime: String): List<CapabilityHandler> {
        val normalizedMime = MimeRegistry.normalize(mime)
        val operations = setOf(Operations.DECODE, Operations.VIEW, Operations.EDIT, Operations.PLAY)
        return RuntimeCapabilities.installed().registeredHandlers().filter { handler ->
            val descriptor = handler.describe()
            if (descriptor.operations.none { it in operations }) return@filter false
            handler.detectExtensions(name).isNotEmpty() || descriptor.formats.any { format ->
                format.mimeTypes.any { declared ->
                    val value = MimeRegistry.normalize(declared)
                    value == normalizedMime || value.endsWith("/*") && normalizedMime.startsWith(value.dropLast(1))
                }
            }
        }
    }

    private fun removedTarget(className: String): Target? = when (className) {
        in removedMediaViewers -> media
        in removedScribeViewers -> scribe
        else -> null
    }

    internal fun replacementPackageForRemovedViewer(className: String): String? =
        removedTarget(className)?.packageName

    private fun handles(context: Context, component: ComponentName, name: String, mime: String): Boolean {
        val probeUri = Uri.Builder().scheme("content").authority("infinifolder-default-probe")
            .appendPath(name).build()
        val probe = Intent(Intent.ACTION_VIEW).setPackage(component.packageName).setDataAndType(probeUri, mime)
        return runCatching {
            context.packageManager.queryIntentActivities(probe, PackageManager.MATCH_DEFAULT_ONLY)
                .any { ComponentName(it.activityInfo.packageName, it.activityInfo.name) == component }
        }.getOrDefault(false)
    }

    private fun installedComponent(context: Context, target: Target): ComponentName? {
        val component = ComponentName(target.packageName, target.activityName)
        return runCatching {
            val info = context.packageManager.getActivityInfo(component, 0)
            component.takeIf { info.exported && info.enabled && info.applicationInfo.enabled }
        }.getOrNull()
    }

    private fun migrateRemovedInternalDefaults(context: Context, defaults: ExternalAppDefaults,
        available: Map<Target, ComponentName>) {
        defaults.rules().filter { it.action == Intent.ACTION_VIEW && it.component.packageName == context.packageName }
            .forEach { rule ->
                val target = removedTarget(rule.component.className) ?: return@forEach
                val replacement = available[target] ?: return@forEach
                if (!mayAssociateWithoutIntentFilter(target.packageName) &&
                    !handles(context, replacement, rule.name.ifBlank { "document" }, rule.mime)) return@forEach
                defaults.remember(rule.action, rule.scope,
                    if (rule.scope == ExternalAppDefaults.FILE) rule.key else null,
                    rule.mime, rule.name.ifBlank { "document" }, replacement)
            }
    }

    private fun <K, V : Any> mapOfNotNull(vararg pairs: Pair<K, V?>): Map<K, V> =
        pairs.mapNotNull { (key, value) -> value?.let { key to it } }.toMap()
}
