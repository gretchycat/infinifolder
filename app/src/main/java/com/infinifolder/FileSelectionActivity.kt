package com.infinifolder

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.ResultReceiver
import android.os.Build
import com.infinifolder.overlay.FloatingContainerService
import com.infinifolder.storage.ContainerLinksProvider
import io.github.gretchycat.infiniselect.FileSelectionContract

/** Thin exported adapter from libinfiniselect's intent protocol to the existing floating browser. */
class FileSelectionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val saveMode = intent.action == FileSelectionContract.ACTION_SAVE
        if (!saveMode && intent.action != FileSelectionContract.ACTION_SELECT) {
            setResult(RESULT_CANCELED)
            finish()
            return
        }

        val mimeTypes = intent.getStringArrayListExtra(FileSelectionContract.EXTRA_MIME_TYPES).orEmpty()
        val progressReceiver = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(FileSelectionContract.EXTRA_PROGRESS_RECEIVER, ResultReceiver::class.java)
        } else @Suppress("DEPRECATION") (intent.getParcelableExtra(FileSelectionContract.EXTRA_PROGRESS_RECEIVER) as? ResultReceiver)
        val selectionResultReceiver = object : ResultReceiver(Handler(mainLooper)) {
            override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                when (resultCode) {
                    FileSelectionContract.RESULT_LOADING_STARTED,
                    FileSelectionContract.RESULT_LOADING_PROGRESS,
                    FileSelectionContract.RESULT_LOADING_FAILED -> progressReceiver?.send(resultCode, resultData ?: Bundle())
                    FileSelectionContract.RESULT_SELECTED -> if (saveMode) saveInSelectedDirectory(resultData ?: Bundle())
                        else deliverSelection(resultData ?: Bundle())
                    FileSelectionContract.RESULT_FALLBACK -> {
                        setResult(RESULT_OK, Intent().putExtra(
                            FileSelectionContract.EXTRA_FALLBACK_TO_SOURCES, true
                        ).putExtra(
                            FileSelectionContract.EXTRA_FAILURE_MESSAGE,
                            resultData?.getString(FileSelectionContract.EXTRA_FAILURE_MESSAGE)
                        ))
                        finish()
                    }
                    else -> { setResult(RESULT_CANCELED); finish() }
                }
            }
        }
        val initialDirectory = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(FileSelectionContract.EXTRA_INITIAL_DIRECTORY, Uri::class.java)
        } else @Suppress("DEPRECATION") (intent.getParcelableExtra(FileSelectionContract.EXTRA_INITIAL_DIRECTORY) as? Uri)
        val startUri = initialDirectory ?: ContainerLinksProvider.ROOT
        val startName = if (initialDirectory == null) "Virtual root" else null
        val start = FloatingContainerService.intent(this, startUri, startName)
            .putExtra(FloatingContainerService.EXTRA_SELECTION_MODE, true)
            .putExtra(FloatingContainerService.EXTRA_SELECTION_SAVE_MODE, saveMode)
            .putExtra(FileSelectionContract.EXTRA_SAVE_FILE_NAME,
                intent.getStringExtra(FileSelectionContract.EXTRA_SAVE_FILE_NAME))
            .putExtra(FloatingContainerService.EXTRA_ALLOW_MULTIPLE,
                intent.getBooleanExtra(FileSelectionContract.EXTRA_ALLOW_MULTIPLE, false))
            .putStringArrayListExtra(FloatingContainerService.EXTRA_SELECTION_MIME_TYPES, ArrayList(mimeTypes))
            .putStringArrayListExtra(FloatingContainerService.EXTRA_SELECTION_EXTENSIONS,
                ArrayList(intent.getStringArrayListExtra(FileSelectionContract.EXTRA_SUPPORTED_EXTENSIONS).orEmpty()))
            .putExtra(FloatingContainerService.EXTRA_HIDE_UNSUPPORTED,
                intent.getBooleanExtra(FileSelectionContract.EXTRA_HIDE_UNSUPPORTED, false))
            .putExtra(FloatingContainerService.EXTRA_ALLOW_RECURSIVE_AUDIO_DIRECTORIES,
                intent.getBooleanExtra(FileSelectionContract.EXTRA_ALLOW_RECURSIVE_AUDIO_DIRECTORIES, false))
            .putExtra(FloatingContainerService.EXTRA_SELECTION_SCROLL_Y,
                intent.getIntExtra(FileSelectionContract.EXTRA_INITIAL_SCROLL_Y, 0).coerceAtLeast(0))
            .putExtra(FloatingContainerService.EXTRA_SELECTION_RECEIVER, selectionResultReceiver)
        runCatching { startService(start) }.onFailure { error ->
            selectionResultReceiver.send(FileSelectionContract.RESULT_FALLBACK, Bundle().apply {
                putBoolean(FileSelectionContract.EXTRA_FALLBACK_TO_SOURCES, true)
                putString(FileSelectionContract.EXTRA_FAILURE_MESSAGE,
                    error.message ?: "InfiniFolder could not start its floating file selector")
            })
        }
    }

    private fun saveInSelectedDirectory(data: Bundle) {
        val directory = data.getString(FileSelectionContract.EXTRA_SAVE_DIRECTORY)?.let(Uri::parse)
        val source = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(FileSelectionContract.EXTRA_SAVE_SOURCE_URI, Uri::class.java)
        } else @Suppress("DEPRECATION") (intent.getParcelableExtra(FileSelectionContract.EXTRA_SAVE_SOURCE_URI) as? Uri)
        val requestedName = intent.getStringExtra(FileSelectionContract.EXTRA_SAVE_FILE_NAME)
        val safeRequestedName = requestedName?.takeIf { it.isNotBlank() }
        val mime = intent.getStringExtra(FileSelectionContract.EXTRA_SAVE_MIME_TYPE) ?: "application/octet-stream"
        val expectedSize = intent.getLongExtra(FileSelectionContract.EXTRA_SAVE_SOURCE_SIZE, -1L).takeIf { it >= 0L }
        if (directory == null || source == null || safeRequestedName == null) {
            finishSave(false, error = "The save request is incomplete", directory = directory)
            return
        }
        val saveDirectory = directory ?: return
        val sourceUri = source ?: return
        val initialName = safeRequestedName ?: return
        if (initialName != initialName.substringAfterLast('/') || initialName in setOf(".", "..") ||
            initialName.contains('\\') || initialName.contains('\u0000')) {
            finishSave(false, error = "The requested filename is invalid", directory = directory)
            return
        }
        Thread({
            val result = runCatching {
                val provider = com.infinifolder.storage.StorageRuntime.get(applicationContext).provider
                val parent = provider.refForUri(saveDirectory) ?: error("InfiniFolder cannot write to this location")
                val parentEntry = when (val stat = provider.stat(parent)) {
                    is com.infinifolder.model.ProviderResult.Success -> stat.value
                    is com.infinifolder.model.ProviderResult.Failure -> error(stat.message)
                    is com.infinifolder.model.ProviderResult.Unsupported -> error("This location cannot be checked for write access")
                }
                check(parentEntry.metadata.isDirectory) { "Choose a directory to save into" }
                val names = when (val listing = provider.list(parent)) {
                    is com.infinifolder.model.ProviderResult.Success -> com.infinifolder.storage.StorageSafety.complete(listing).map { it.metadata.name.lowercase(java.util.Locale.ROOT) }.toHashSet()
                    is com.infinifolder.model.ProviderResult.Failure -> error(listing.message)
                    is com.infinifolder.model.ProviderResult.Unsupported -> error("This location cannot list existing files safely")
                }
                val dot = initialName.lastIndexOf('.').takeIf { it > 0 }
                val stem = dot?.let { initialName.substring(0, it) } ?: initialName
                val extension = dot?.let { initialName.substring(it) }.orEmpty()
                var name = initialName
                var suffix = 2
                while (name.lowercase(java.util.Locale.ROOT) in names) {
                    name = "$stem ($suffix)$extension"
                    suffix++
                }
                val created = when (val create = provider.createFile(parent, name, mime)) {
                    is com.infinifolder.model.ProviderResult.Success -> create.value
                    is com.infinifolder.model.ProviderResult.Failure -> error(create.message)
                    is com.infinifolder.model.ProviderResult.Unsupported -> error("This location cannot create files")
                }
                try {
                    val input = contentResolver.openInputStream(sourceUri) ?: error("The playlist data could not be opened")
                    val output = when (val opened = provider.openWrite(created.ref)) {
                        is com.infinifolder.model.ProviderResult.Success -> opened.value
                        is com.infinifolder.model.ProviderResult.Failure -> error(opened.message)
                        is com.infinifolder.model.ProviderResult.Unsupported -> error("This location cannot write files")
                    }
                    var written = 0L
                    input.use { stream -> output.use { target ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val count = stream.read(buffer)
                            if (count < 0) break
                            target.write(buffer, 0, count)
                            written += count
                        }
                        target.flush()
                    } }
                    if (expectedSize != null) check(written == expectedSize) { "The saved file size did not match the source" }
                    val finalEntry = (provider.stat(created.ref) as? com.infinifolder.model.ProviderResult.Success)?.value
                    finalEntry?.metadata?.sizeBytes?.let { check(it == written) { "The saved file size did not match the written data" } }
                    name to created.uri
                } catch (error: Throwable) {
                    runCatching { provider.delete(created.ref) }
                    throw error
                }
            }
            runOnUiThread {
                result.fold(
                    { (name, uri) -> finishSave(true, name, uri, directory = directory) },
                    { error ->
                        com.infinifolder.storage.DiagnosticLog.record("infiniselect.save.failed", error = error)
                        finishSave(false, error = error.message ?: "Could not save file", directory = directory)
                    }
                )
            }
        }, "InfiniFolderSaveSelection").start()
    }

    private fun finishSave(success: Boolean, name: String? = null, uri: Uri? = null,
        error: String? = null, directory: Uri? = null) {
        val result = Intent().putExtra(FileSelectionContract.EXTRA_SAVE_SUCCESS, success)
        if (success) {
            result.putExtra(FileSelectionContract.EXTRA_SAVE_RESULT_NAME, name)
            result.data = uri
        } else result.putExtra(FileSelectionContract.EXTRA_SAVE_ERROR, error)
        directory?.let { result.putExtra(FileSelectionContract.EXTRA_SAVE_DIRECTORY, it.toString()) }
        setResult(RESULT_OK, result)
        finish()
    }

    private fun deliverSelection(data: Bundle) {
        val sources = data.getStringArrayList(FileSelectionContract.EXTRA_RESULT_URIS)
            ?.map(Uri::parse).orEmpty().ifEmpty {
                listOfNotNull(data.getString(FileSelectionContract.EXTRA_RESULT_URI)?.let(Uri::parse))
            }
        val names = data.getStringArrayList(FileSelectionContract.EXTRA_RESULT_NAMES).orEmpty()
            .ifEmpty { listOfNotNull(data.getString(FileSelectionContract.EXTRA_RESULT_NAME)) }
        val mimes = data.getStringArrayList(FileSelectionContract.EXTRA_RESULT_MIMES).orEmpty()
            .ifEmpty { listOf(data.getString(FileSelectionContract.EXTRA_RESULT_MIME) ?: "application/octet-stream") }
        val sizes = data.getLongArray(FileSelectionContract.EXTRA_RESULT_SIZES)
            ?: longArrayOf(data.getLong(FileSelectionContract.EXTRA_RESULT_SIZE, -1L))
        val directoryUris = data.getStringArrayList(FileSelectionContract.EXTRA_ITEM_DIRECTORY_URIS).orEmpty()
        val directoryNames = data.getStringArrayList(FileSelectionContract.EXTRA_ITEM_DIRECTORY_NAMES).orEmpty()
        val directoryCoverSources = data.getStringArrayList(
            FileSelectionContract.EXTRA_ITEM_DIRECTORY_COVER_URIS).orEmpty()
        if (sources.isEmpty() || names.size != sources.size) {
            setResult(RESULT_CANCELED)
            finish()
            return
        }
        val relays = runCatching {
            FileSelectionRelayProvider.registerAll(this, sources.mapIndexed { index, source ->
                FileSelectionRelayProvider.RegistrationRequest(source, names[index],
                    mimes.getOrElse(index) { "application/octet-stream" },
                    sizes.getOrNull(index)?.takeIf { it >= 0L })
            })
        }.getOrElse {
            setResult(RESULT_CANCELED, Intent().putExtra(FileSelectionContract.EXTRA_FAILURE_MESSAGE,
                it.message ?: "Could not share this file"))
            finish()
            return
        }
        // Preserve only a URI to each distinct cover. Do not read or decode image
        // bytes while building a recursive playlist; the viewer fills the folder
        // icon asynchronously after the queue is available.
        val coverRequestsBySource = linkedMapOf<String, FileSelectionRelayProvider.RegistrationRequest>()
        directoryCoverSources.asSequence().filter(String::isNotBlank).distinct().forEach { sourceText ->
            runCatching {
                val source = Uri.parse(sourceText)
                val name = source.lastPathSegment?.substringAfterLast('/')?.takeIf(String::isNotBlank) ?: "cover"
                val guessedMime = com.infinifolder.mime.MimeRegistry.guess(name)
                FileSelectionRelayProvider.RegistrationRequest(source, name,
                    guessedMime.takeIf { it.startsWith("image/", ignoreCase = true) } ?: "image/*", null)
            }.onSuccess { request -> coverRequestsBySource[sourceText] = request }
                .onFailure { error -> com.infinifolder.storage.DiagnosticLog.record(
                    "infiniselect.cover-relay.failed", "source=$sourceText", error) }
        }
        val coverRelaysBySource = runCatching {
            FileSelectionRelayProvider.registerAll(this, coverRequestsBySource.values.toList())
                .let { uris -> coverRequestsBySource.keys.zip(uris).toMap() }
        }.getOrElse { error ->
            com.infinifolder.storage.DiagnosticLog.record("infiniselect.cover-relay.failed", error = error)
            emptyMap()
        }
        val ungrantedCoverRelays = hashSetOf<Uri>()
        // The result passes through libinfiniselect before it reaches the viewer.
        // Grant the selected relay URIs to that calling package directly so the
        // intermediate Activity can safely forward a multi-item ClipData result.
        // Selection results arrive through a ResultReceiver, not a conventional
        // Activity result callback. Use the requesting package supplied by the
        // picker library before the framework's optional caller fields.
        val recipientPackage = intent.getStringExtra(FileSelectionContract.EXTRA_REQUESTING_PACKAGE)
            ?: callingPackage ?: callingActivity?.packageName
        recipientPackage?.let { recipient ->
            val grantFailure = runCatching {
                relays.forEach { relay ->
                    grantUriPermission(recipient, relay, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                coverRelaysBySource.values.distinct().forEach { relay ->
                    runCatching { grantUriPermission(recipient, relay, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                        .onFailure { error ->
                            ungrantedCoverRelays += relay
                            com.infinifolder.storage.DiagnosticLog.record(
                                "infiniselect.cover-grant.failed", "recipient=$recipient", error)
                        }
                }
            }.exceptionOrNull()
            if (grantFailure != null) {
                com.infinifolder.storage.DiagnosticLog.record(
                    "infiniselect.grant.failed", "recipient=$recipient count=${relays.size}", grantFailure)
                setResult(RESULT_CANCELED, Intent().putExtra(FileSelectionContract.EXTRA_FAILURE_MESSAGE,
                    grantFailure.message ?: "Could not grant access to the selected files"))
                finish()
                return
            }
            com.infinifolder.storage.DiagnosticLog.record(
                "infiniselect.grant", "recipient=$recipient count=${relays.size}")
        } ?: com.infinifolder.storage.DiagnosticLog.record(
            "infiniselect.grant.skipped", "no calling package count=${relays.size}")
        val directoryCoverRelays = sources.indices.map { index ->
            directoryCoverSources.getOrNull(index)?.let(coverRelaysBySource::get)
                ?.takeUnless(ungrantedCoverRelays::contains)?.toString().orEmpty()
        }
        val name = names.first()
        val mime = mimes.firstOrNull()?.takeIf(String::isNotBlank) ?: "application/octet-stream"
        val result = Intent().setDataAndType(relays.first(), if (relays.size == 1) mime else "*/*").apply {
            // InfiniFolder selection relays do not support persistable grants;
            // the app grants each relay directly to the requesting package.
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            putExtra(FileSelectionContract.EXTRA_RESULT_URI, relays.first().toString())
            putExtra(FileSelectionContract.EXTRA_RESULT_NAME, name)
            putExtra(FileSelectionContract.EXTRA_RESULT_MIME, mime)
            putExtra(FileSelectionContract.EXTRA_RESULT_SIZE, sizes.firstOrNull() ?: -1L)
            data.getString(FileSelectionContract.EXTRA_RESULT_DIRECTORY)?.let {
                putExtra(FileSelectionContract.EXTRA_RESULT_DIRECTORY, it)
            }
            putExtra(FileSelectionContract.EXTRA_RESULT_SCROLL_Y,
                data.getInt(FileSelectionContract.EXTRA_RESULT_SCROLL_Y, 0).coerceAtLeast(0))
            putStringArrayListExtra(FileSelectionContract.EXTRA_RESULT_URIS, ArrayList(relays.map(Uri::toString)))
            putStringArrayListExtra(FileSelectionContract.EXTRA_RESULT_NAMES, ArrayList(names))
            putStringArrayListExtra(FileSelectionContract.EXTRA_RESULT_MIMES, ArrayList(mimes))
            putExtra(FileSelectionContract.EXTRA_RESULT_SIZES, sizes)
            putStringArrayListExtra(FileSelectionContract.EXTRA_ITEM_DIRECTORY_URIS,
                ArrayList(relays.indices.map { directoryUris.getOrNull(it).orEmpty() }))
            putStringArrayListExtra(FileSelectionContract.EXTRA_ITEM_DIRECTORY_NAMES,
                ArrayList(relays.indices.map { directoryNames.getOrNull(it).orEmpty() }))
            putStringArrayListExtra(FileSelectionContract.EXTRA_ITEM_DIRECTORY_COVER_URIS,
                ArrayList(directoryCoverRelays))
            putExtra("displayName", name)
            putExtra("mimeType", mime)
            // Per-item URIs and directory-cover URIs are included in extras and
            // already granted directly to the recipient. Keep ClipData small;
            // recursive selections can contain hundreds of items.
            clipData = android.content.ClipData.newRawUri(name, relays.first())
        }
        setResult(RESULT_OK, result)
        finish()
    }
}
