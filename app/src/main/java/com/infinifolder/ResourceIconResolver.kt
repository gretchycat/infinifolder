package com.infinifolder

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.infinifolder.apps.AppsUri
import com.infinifolder.archive.ArchiveFormats
import com.infinifolder.archive.ArchiveUri
import com.infinifolder.mime.MimeRegistry
import com.infinifolder.model.*
import com.infinifolder.preview.*
import com.infinifolder.shortcut.ShortcutInfo
import com.infinifolder.storage.*
import com.infinifolder.theme.IconThemeManager
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** The single listing/tree/search/overlay icon service. Call resolve from a worker. */
class ResourceIconResolver(context: Context) {
    private val appContext = context.applicationContext
    private val artworkCache = ResourceArtworkCache(context)
    private val runtime = StorageRuntime.get(context)
    private val icons = CustomIconStore(context.filesDir)
    private val registry = IconRegistry.at(context.filesDir)
    private val identityResolver = IconIdentityResolver(registry)
    private val theme = IconThemeManager(context.applicationContext)
    val archives = ArchiveIconCapabilities(context)
    @Volatile var onArtworkChanged: ((Uri) -> Unit)? = null
    private val decoder = ThumbnailDecoder(context.contentResolver) { uri ->
        runtime.provider.refForUri(uri)?.let { (runtime.provider.openRead(it) as? ProviderResult.Success)?.value }
    }
    private val audioArtwork=AudioArtworkResolver(context,runtime,decoder)
    private val bookArtwork=BookArtworkResolver(context,runtime)
    data class Icon(val bitmap: Bitmap?, val fallback: String, val label: String, val directory: Boolean,
        val program: Boolean = false, val source: String = "fallback", val mimeType: String = "", val pending: Boolean = false)
    private data class Cached(val icon: Icon, val expires: Long)
    private val rendered = object : android.util.LruCache<String, Cached>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Cached) = value.icon.bitmap?.allocationByteCount ?: 1
    }
    private data class IdentityCache(val identity:IconIdentity,val expires:Long)
    private val identities = android.util.LruCache<String, IdentityCache>(512)
    private val linkInfo = android.util.LruCache<String, ShortcutInfo>(128)
    private val apkIcons = android.util.LruCache<String, Bitmap>(64)
    private val failedApkIcons = android.util.LruCache<String, Boolean>(128)
    private val retryCounts = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private val retries = Executors.newSingleThreadScheduledExecutor()
    private data class RevisionSnapshot(val key:String,val theme:String)
    @Volatile private var lastRevision:RevisionSnapshot?=null
    fun revision(): String {
        val themeRevision=theme.revision()
        val key="${registry.revision}:$themeRevision:${icons.revision}"
        lastRevision=RevisionSnapshot(key,themeRevision)
        return key
    }
    fun close() { onArtworkChanged=null; retries.shutdownNow() }
    fun invalidate() { rendered.evictAll(); identities.evictAll(); linkInfo.evictAll(); NetworkIconTargets.clear(); archives.clear(); audioArtwork.clear(); bookArtwork.clear(); ResourceArtworkCache.clearMemory() }
    private fun linkKey(entry: ResourceEntry) = "${entry.uri}:${entry.metadata.modifiedAtMillis}:${entry.metadata.sizeBytes}"
    fun peekShortcut(entry: ResourceEntry): ShortcutInfo? = linkInfo.get(linkKey(entry))
    /** Worker-only metadata access shared by listing and pinned-link presentation. */
    fun shortcutInfo(entry: ResourceEntry): ShortcutInfo? = (peekShortcut(entry)
        ?: IconWork.bounded(lane="metadata:${entry.ref.providerId}") { readShortcut(entry) })
        ?.also { NetworkIconTargets.remember(entry, it.targetUri) }
    fun sidecar(entry: ResourceEntry): ResourceEntry? = artworkCache.sidecar(entry)
    fun sidecar(uri: Uri, name: String = ""): ResourceEntry? = artworkCache.sidecar(uri, name)
    private fun signature(entry: ResourceEntry,iconRevision:String=revision()): String {
        val sidecar = ResourceIconSidecars.find(entry)
        val covers=if(entry.metadata.isDirectory)ResourceIconSidecars.directory(entry)?.candidates.orEmpty().joinToString {
            "${it.uri}:${it.metadata.modifiedAtMillis}:${it.metadata.sizeBytes}"
        } else ""
        val audioCover=if(AudioIconFormats.isAudio(MimeRegistry.effective(entry.metadata.name,entry.metadata.mimeType)))
            AudioArtworkResolver.coverRevision(entry) else ""
        return "icons-v8:${entry.uri}:${entry.metadata.name}:${entry.metadata.mimeType}:${entry.metadata.sizeBytes}:${entry.metadata.modifiedAtMillis}:$iconRevision:" +
            "${sidecar?.uri}:${sidecar?.metadata?.modifiedAtMillis}:${sidecar?.metadata?.sizeBytes}:$covers:$audioCover"
    }
    fun cached(entry: ResourceEntry, size: Int): Icon? {
        return rendered.get("${signature(entry)}:$size")?.takeIf { System.currentTimeMillis()<it.expires }?.icon
    }
    /** First-frame lookup using the revision already validated by a worker/onResume. No disk I/O. */
    fun peekCached(entry:ResourceEntry,size:Int):Icon? {
        val revision=lastRevision?.takeIf {it.theme==theme.revision()} ?: return null
        return rendered.get("${signature(entry,revision.key)}:$size")
            ?.takeIf {System.currentTimeMillis()<it.expires && !it.icon.pending}?.icon
    }
    fun identity(entry: ResourceEntry): IconIdentity {
        val key="${linkKey(entry)}:${entry.metadata.name}:${entry.metadata.mimeType}:${registry.revision}"
        identities.get(key)?.takeIf {System.currentTimeMillis()<it.expires}?.let { return it.identity }
        val identity=identityResolver.resolve(entry.metadata.name,entry.metadata.mimeType) {
            IconWork.bounded(lane="metadata:${entry.ref.providerId}") { FileMagicDetector.detectFromStream {
                (runtime.provider.openRead(entry.ref) as? ProviderResult.Success)?.value ?: error("Cannot probe source")
            } }?.mimeType
        }
        identities.put(key,IdentityCache(identity,System.currentTimeMillis()+30000));return identity
    }
    fun resolve(entry: ResourceEntry, size: Int, deferCacheWrite: Boolean = false): Icon {
        require(size>0)
        if(Thread.currentThread().isInterrupted) return fallback(entry,size)
        // Link badges follow the destination even when explicit artwork/cache wins.
        if (!entry.metadata.isDirectory && ShortcutInfo.isShortcut(entry.metadata.name, entry.metadata.mimeType)) shortcutInfo(entry)
        cached(entry,size)?.let { return it }
        val requestRevision = revision()
        val started=System.nanoTime()
        val result=runCatching { resolve(entry,size,mutableSetOf()) }.getOrElse { fallback(entry,size) }
        // A cancelled first-install wait or a theme switch must not cache old
        // artwork/fallback as a completed result for the current theme.
        if (Thread.currentThread().isInterrupted || revision() != requestRevision) return result
        val lifetime=when {
            result.pending -> 500L
            entry.metadata.isDirectory -> 5000L
            entry.metadata.isSymbolicLink || ShortcutInfo.isShortcut(entry.metadata.name,entry.metadata.mimeType) ||
                entry.metadata.modifiedAtMillis==null -> 30000L
            else -> 300000L
        }
        rendered.put("${signature(entry,requestRevision)}:$size",Cached(result,System.currentTimeMillis()+lifetime))
        val elapsed=(System.nanoTime()-started)/1_000_000
        if(elapsed>=100)DiagnosticLog.record("artwork.resolve.slow",
            "name=${entry.metadata.name} provider=${entry.ref.providerId} source=${result.source} elapsedMs=$elapsed")
        return result
    }
    /** Keeps the old caller contract; normal icon cache writes stay in memory/app cache. */
    fun cacheDisplayed(entry: ResourceEntry, size: Int, icon: Icon) {
        if(!entry.metadata.isDirectory)rendered.put("${signature(entry)}:$size",Cached(icon,System.currentTimeMillis()+if(icon.pending)500 else 30000))
    }
    private fun fallback(entry: ResourceEntry, size: Int): Icon = Icon(
        if(AppsUri.isRoot(entry.uri))FileTypeIcons.installedApplications(size)
        else if(entry.metadata.isDirectory)FileTypeIcons.folder(size) else FileTypeIcons.bitmap(entry.metadata,size),
        if(AppsUri.isRoot(entry.uri))"▦" else FileIconView.fallback(entry.metadata),entry.metadata.name,entry.metadata.isDirectory)
    private fun notifyChanged(uri: Uri) { rendered.evictAll(); onArtworkChanged?.invoke(uri) }
    private fun overrideBitmap(value:String?,mime:String?,size:Int):Bitmap? =
        IconOverrideAssets.decode(appContext.filesDir,icons,decoder,value,mime,size)
    private fun uriRule(raw: String,size: Int): Bitmap? {
        val rule=registry.matchUri(raw) ?: return null
        return overrideBitmap(rule.overrideIcon,null,size) ?: theme.resolveLogicalIcon(rule.iconName,size)
    }
    private fun resolve(entry: ResourceEntry,size: Int,seen: MutableSet<ResourceRef>): Icon {
        val base=fallback(entry,size)
        if(!seen.add(entry.ref) || seen.size>16)return base.copy(bitmap=LinkIcons.protocol("BROKEN",size),source="broken-link")
        // A directory's own cover wins over sibling per-file custom artwork.
        if(!entry.metadata.isDirectory &&
            (ResourceIconSidecars.find(entry)!=null || !ResourceIconSidecars.hasObserved(entry))) {
            val saved=IconWork.bounded(lane="metadata:${entry.ref.providerId}") {artworkCache.load(entry,size,false)}
            if(saved!=null && !saved.generated && IconBitmapUsability.visible(saved.bitmap))return base.copy(bitmap=saved.bitmap,source="file-override")
        }
        if(entry.ref.providerId==PartialDownloadsProvider.SCHEME) return base.copy(fallback="⬇",source="partial-download")
        if(ShortcutInfo.isShortcut(entry.metadata.name,entry.metadata.mimeType))return link(entry,size,seen)
        if(LinkIcons.isMagnet(entry.uri))return base.copy(bitmap=theme.resolveSchemeIcon("magnet",size) ?: LinkIcons.protocol("MAGNET",size),source="scheme")
        if(entry.metadata.isSymbolicLink) {
            val target=(IconWork.bounded(lane="metadata:${entry.ref.providerId}") { LinkResolver.resolve(runtime.provider,entry) } as? ProviderResult.Success)?.value?.entry
            if(target!=null)return resolve(target,size,seen).copy(label=entry.metadata.name)
            return base.copy(bitmap=LinkIcons.protocol("BROKEN",size),source="broken-link")
        }
        if(AppsUri.isRoot(entry.uri))return installedApplications(entry.metadata.name,size)
        if(AppsUri.isAppUri(entry.uri))return base.copy(bitmap=AppsUri.getPackageName(entry.uri)?.let { runtime.apps.getAppIconBitmap(it,size,useDiskCache=false) } ?: base.bitmap,source="provider-app")
        if(AppsUri.isCategoryUri(entry.uri))return base.copy(bitmap=null,fallback=runtime.apps.getCategoryIcon(AppsUri.getCategoryName(entry.uri).orEmpty()),source="provider-category")
        if(entry.metadata.isDirectory)return directory(entry,size,seen)
        val id=identity(entry)
        val meta=entry.metadata.copy(mimeType=id.mimeType)
        val record=registry.mime(id.mimeType)
        var artworkPending=false
        if(AudioIconFormats.isAudio(id.mimeType) && !ArchiveFormats.isPlaylist(meta.name,id.mimeType)) {
            val audio=audioArtwork.resolve(entry.copy(metadata=meta),size)
            artworkPending=audio.pending
            retryPending(entry,audio.pending)
            audio.artwork?.let {
                return base.copy(bitmap=it.bitmap,source=it.source,mimeType=id.mimeType,pending=artworkPending)
            }
        }
        val genericArchive=ArchiveFormats.isDefaultBrowsableArchive("",id.mimeType)
        for(step in IconPolicy.steps(record,genericArchive)) {
            if(Thread.currentThread().isInterrupted)break
            val bitmap:Bitmap?=when(step) {
                IconPolicy.Step.THUMBNAIL -> if(id.mimeType in BookArtworkResolver.MIME_TYPES)
                    bookArtwork.resolve(entry.copy(metadata=meta),size)
                else IconWork.bounded(2000,lane="thumbnail:${entry.ref.providerId}") {
                    if(id.mimeType=="application/vnd.android.package-archive")resolveApkIcon(entry,size)
                    else if(ImageFormats.hasDosTextPreview(meta))decoder.decodeTextPreview(entry.uri,size,true)
                    else decoder.decode(entry.uri,size,registry.rendererExtension(id.mimeType,entry.metadata.name))
                }
                IconPolicy.Step.MIME_OVERRIDE -> overrideBitmap(record.overrideIcon,id.mimeType,size)
                IconPolicy.Step.THEME_MIME -> if(AudioIconFormats.isAudio(id.mimeType))theme.resolveAudioIcon(meta,size,record.iconName)
                    else theme.resolveMimeSpecific(id.mimeType,size,record.iconName,meta.name)
                IconPolicy.Step.ARCHIVE_FOLDER -> if(archives.inspect(entry.copy(metadata=meta)).browsable)
                    theme.resolveLogicalIcon("folder-archive",size) ?: FileTypeIcons.folder(size,true) else null
                IconPolicy.Step.GENERATED_TYPE -> FileTypeIcons.bitmap(meta,size,id.mimeType)
                IconPolicy.Step.THEME_CATEGORY -> theme.resolveMimeDefaults(id.mimeType,size) ?: theme.resolveMimeCategory(id.mimeType,size)
                IconPolicy.Step.GENERIC -> FileTypeIcons.bitmap(meta,size,id.mimeType)
            }
            if(bitmap!=null && IconBitmapUsability.visible(bitmap))return base.copy(bitmap=bitmap,source=step.name.lowercase(),mimeType=id.mimeType,pending=artworkPending)
        }
        return base.copy(mimeType=id.mimeType,pending=artworkPending)
    }
    /** Shared folder baseline, with no directory enumeration or document reads. Worker-only. */
    fun directoryTheme(entry:ResourceEntry,size:Int):Icon {
        if(AppsUri.isRoot(entry.uri))return installedApplications(entry.metadata.name,size)
        val original=fallback(entry,size)
        val metadata=directoryIconMetadata(entry)
        if(!metadata.isDirectory) {
            val id=identityResolver.resolve(metadata.name,metadata.mimeType) {null}
            val record=registry.mime(id.mimeType)
            val artwork=overrideBitmap(record.overrideIcon,id.mimeType,size)
                ?: theme.resolveMimeSpecific(id.mimeType,size,record.iconName,metadata.name)
                ?: theme.resolveLogicalIcon("package-x-generic",size)
                ?: FileTypeIcons.bitmap(metadata.copy(mimeType=id.mimeType),size,id.mimeType)
            return original.copy(bitmap=artwork,source="container-theme",mimeType=id.mimeType)
        }
        return original.copy(bitmap=theme.resolveFolderIcon(entry.metadata.name,size) ?: original.bitmap,source="directory-theme")
    }
    /** A mounted container is navigable like a directory, but keeps its file identity.
     * Internal archive directories continue to use normal folder artwork. No I/O. */
    fun directoryIconMetadata(entry:ResourceEntry):ResourceMetadata {
        if(!ArchiveUri.isArchiveUri(entry.uri) || ArchiveUri.getEntryPath(entry.uri).isNotEmpty())return entry.metadata
        val source=ArchiveUri.getSourceUri(entry.uri)
        val sourceName=if(ArchiveUri.isArchiveUri(source))ArchiveUri.getEntryPath(source).substringAfterLast('/')
            else source.lastPathSegment?.substringAfterLast('/').orEmpty()
        val name=entry.metadata.name.takeIf {MimeRegistry.guess(it)!="application/octet-stream"}
            ?: sourceName.ifBlank {entry.metadata.name}
        return entry.metadata.copy(name=name,mimeType=MimeRegistry.guess(name),isDirectory=false)
    }
    private fun installedApplications(name:String,size:Int)=Icon(
        theme.resolvePlaceIcon("installed_apps",size) ?: FileTypeIcons.installedApplications(size),
        "▦",name,true,source="installed-applications")

    fun resolveApplicationPublic(name:String,size:Int)=Icon(
        theme.resolvePlaceIcon("application_public",size) ?: FileTypeIcons.installedApplications(size),
        "▦",name,true,source="application-public")

    /** Provider ownership and package artwork do not require a document-tree grant. */
    fun resolveAppStorage(authority:String,name:String,size:Int):Icon {
        val owner=AppDocumentSources.getProgramPackage(appContext,authority)
        val bitmap=owner?.let {runtime.apps.getAppIconBitmap(it,size)}
        return if(bitmap!=null)Icon(bitmap,"▦",name,true,source="provider-app-storage")
            else resolveApplicationPublic(name,size)
    }

    /** Discovery seeds the authority cache; this first-frame lookup never queries PackageManager. */
    fun peekAppStorageIcon(authority:String,size:Int):Bitmap? =
        AppDocumentSources.getProgramPackage(null,authority)?.let {runtime.apps.getCachedAppIconBitmap(it,size)}
    private fun retryPending(entry:ResourceEntry,pending:Boolean) {
        if(pending && retryCounts.merge(entry.uri.toString(),1,Int::plus)!!<=8)
            retries.schedule({notifyChanged(entry.uri)},500,TimeUnit.MILLISECONDS)
        if(!pending)retryCounts.remove(entry.uri.toString())
    }
    private fun directory(entry: ResourceEntry,size: Int,seen:MutableSet<ResourceRef>): Icon {
        val base=directoryTheme(entry,size)
        fun sourceIcon(pending:Boolean):Icon {
            // Mounting changes navigation capability, not the source file's icon.
            // This also shares EPUB/PDF cover and per-file override behavior with
            // the ordinary listing; explicit inner directory covers still win.
            if(ArchiveUri.isArchiveUri(entry.uri) && ArchiveUri.getEntryPath(entry.uri).isEmpty()) {
                val uri=ArchiveUri.getSourceUri(entry.uri)
                val source=runtime.provider.refForUri(uri)?.let {ref ->
                    (IconWork.bounded(lane="metadata:${ref.providerId}") {runtime.provider.stat(ref)} as? ProviderResult.Success)?.value
                }
                if(source!=null && !source.metadata.isDirectory)
                    return resolve(source,size,seen).copy(label=entry.metadata.name,directory=true,pending=pending)
            }
            return base.copy(pending=pending)
        }
        val observed=ResourceIconSidecars.directory(entry)?.takeIf {
            it.complete && System.currentTimeMillis()-it.observedAt in 0..5000
        }
        val success=if(observed==null)IconWork.bounded(lane="directory:${entry.ref.providerId}") {
            runtime.provider.list(entry.ref)
        } as? ProviderResult.Success else null
        val items=success?.value.orEmpty()
        val pending=success?.isLoading==true
        retryPending(entry,pending)
        if(success!=null)ResourceIconSidecars.observe(items,entry.uri,complete=!pending)
        val possible=observed?.candidates ?: DirectoryIconResolver.candidates(items)
        if(possible.isEmpty())return sourceIcon(pending)
        val caseSensitive=(IconWork.bounded(300,lane="directory:${entry.ref.providerId}") {
            (runtime.provider.fileSystemInfo(entry.ref) as? ProviderResult.Success)?.value?.caseSensitive
        }) ?: (entry.ref.providerId!="smb")
        val candidates=DirectoryIconResolver.candidates(possible,caseSensitive)
        val started=System.currentTimeMillis()
        for(candidate in candidates) {
            if(Thread.currentThread().isInterrupted || System.currentTimeMillis()-started>3000)break
            val bitmap=IconWork.bounded(1500,lane="thumbnail:${candidate.ref.providerId}") { if(ImageFormats.hasDosTextPreview(candidate.metadata))decoder.decodeTextPreview(candidate.uri,size,true) else decoder.decode(candidate.uri,size) }
            if(bitmap!=null && IconBitmapUsability.visible(bitmap))return base.copy(bitmap=bitmap,source=if(DirectoryIconResolver.priority(candidate.metadata.name)==0)"directory-cover" else "directory-icon",pending=pending)
        }
        return sourceIcon(pending)
    }
    fun resolveLocation(uri:Uri,name:String,size:Int):Icon {
        val rule=runCatching {uriRule(uri.toString(),size)}.getOrNull()
        if(rule!=null)return Icon(rule,"🔗",name,true,source="uri-rule")
        if(AppsUri.isRoot(uri))return installedApplications(name,size)
        val scheme=uri.scheme.orEmpty().lowercase()
        if(ContainerUris.networkType(uri)!=null || scheme in setOf("magnet","torrent")) {
            return Icon(theme.resolveSchemeIcon(scheme,size) ?: LinkIcons.protocol(LinkIcons.networkProtocol(uri,runtime.connections.list()),size),
                "🔗",name,true,source="scheme")
        }
        val ref=runtime.provider.refForUri(uri)
        return if(ref!=null)resolve(ResourceEntry(ref,ResourceMetadata(name,"vnd.android.document/directory",null,null,true),uri),size)
            else Icon(theme.resolveSchemeIcon(scheme,size) ?: LinkIcons.protocol(scheme,size),"🔗",name,true,source="scheme")
    }
    private fun link(entry: ResourceEntry,size: Int,seen: MutableSet<ResourceRef>): Icon {
        val info=shortcutInfo(entry) ?: return fallback(entry,size).copy(bitmap=LinkIcons.protocol("BROKEN",size),source="broken-link")
        val label=info.displayNameOverride?.takeIf { it.isNotBlank() } ?: entry.metadata.name.substringBeforeLast('.')
        val target=runCatching { Uri.parse(IconRegistry.normalizeUri(info.targetUri)) }.getOrNull()
            ?: return fallback(entry,size).copy(bitmap=LinkIcons.protocol("BROKEN",size),label=label,source="broken-link")
        val base=Icon(null,"🔗",label,info.targetIsDirectory)
        CustomIconStore.decodeBase64Bitmap(info.iconBase64)?.let { return base.copy(bitmap=it,source="link-override") }
        uriRule(info.targetUri,size)?.let { return base.copy(bitmap=it,source="uri-rule") }
        val scheme=target.scheme.orEmpty().lowercase()
        if(scheme=="content") {
            val thumbnail=IconWork.bounded(lane="thumbnail:content") {
                val flags=appContext.contentResolver.query(target,arrayOf(android.provider.DocumentsContract.Document.COLUMN_FLAGS),null,null,null)?.use {cursor ->
                    if(cursor.moveToFirst())cursor.getLong(0) else 0L
                } ?: 0L
                if(flags and android.provider.DocumentsContract.Document.FLAG_SUPPORTS_THUMBNAIL.toLong()!=0L)
                    android.provider.DocumentsContract.getDocumentThumbnail(appContext.contentResolver,target,android.graphics.Point(size,size),null)
                else null
            }
            if(thumbnail!=null)return base.copy(bitmap=thumbnail,source="content-provider-thumbnail")
        }
        val local=scheme in setOf("file","content","apps","archive")
        if(local) {
            val ref=runtime.provider.refForUri(target)
            val stat=ref?.let { IconWork.bounded(lane="metadata:${it.providerId}") { runtime.provider.stat(it) } as? ProviderResult.Success }
            val resolved=stat?.value?.let { resolve(it,size,seen) }
            if(resolved!=null)return resolved.copy(label=label)
        }
        if(scheme in setOf("http","https")) {
            val favicon=WebLinkIcons.request(appContext,info.targetUri) { notifyChanged(entry.uri) }
            if(favicon!=null)return base.copy(bitmap=favicon,source="favicon")
            return base.copy(bitmap=theme.resolveSchemeIcon(scheme,size) ?: LinkIcons.protocol("WEB",size),source="scheme",pending=WebLinkIcons.pending(info.targetUri))
        }
        if(TermuxCommandLink.owns(target))return base.copy(bitmap=runtime.apps.getAppIconBitmap("com.termux",size,useDiskCache=false) ?: LinkIcons.protocol("SSH",size),source="provider-app")
        if(scheme=="content") {
            val providerIcon=IconWork.bounded(lane="metadata:content") { val drawable=appContext.packageManager.resolveContentProvider(target.authority.orEmpty(),0)?.loadIcon(appContext.packageManager)
                drawable?.let { android.graphics.Bitmap.createBitmap(size,size,Bitmap.Config.ARGB_8888).also { image -> it.setBounds(0,0,size,size);it.draw(android.graphics.Canvas(image)) } } }
            if(providerIcon!=null)return base.copy(bitmap=providerIcon,source="content-provider")
        }
        if(scheme in setOf("file","content"))return base.copy(bitmap=FileTypeIcons.bitmap(
            ResourceMetadata(target.lastPathSegment ?: "Document","application/octet-stream",null,null,false),size),source="provider-document-fallback")
        val protocol=if(ContainerUris.networkType(target)!=null)LinkIcons.networkProtocol(target,runtime.connections.list()) else scheme
        return base.copy(bitmap=theme.resolveSchemeIcon(scheme,size) ?: LinkIcons.protocol(protocol,size),source="scheme")
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
            stream.use { input ->
                tempFile.outputStream().use { output ->
                    io.github.gretchycat.infinistream.copy(input, output,
                        bufferSize = 16 * 1024, maxBytes = maxApkStageBytes)
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
            val bytes = io.github.gretchycat.infinistream.readBytes(input, 1024 * 1024)
            ShortcutInfo.fromJson(bytes.toString(Charsets.UTF_8))?.also { linkInfo.put(linkKey(entry), it) }
        }
        }.getOrNull()
    }
}
