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
        if (intent.action != FileSelectionContract.ACTION_SELECT) {
            setResult(RESULT_CANCELED)
            finish()
            return
        }

        val receiver = object : ResultReceiver(Handler(mainLooper)) {
            override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                when (resultCode) {
                    FileSelectionContract.RESULT_SELECTED -> deliverSelection(resultData ?: Bundle())
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

        val mimeTypes = intent.getStringArrayListExtra(FileSelectionContract.EXTRA_MIME_TYPES).orEmpty()
        val initialDirectory = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(FileSelectionContract.EXTRA_INITIAL_DIRECTORY, Uri::class.java)
        } else @Suppress("DEPRECATION") (intent.getParcelableExtra(FileSelectionContract.EXTRA_INITIAL_DIRECTORY) as? Uri)
        val startUri = initialDirectory ?: ContainerLinksProvider.ROOT
        val startName = if (initialDirectory == null) "Virtual root" else null
        val start = FloatingContainerService.intent(this, startUri, startName)
            .putExtra(FloatingContainerService.EXTRA_SELECTION_MODE, true)
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
            .putExtra(FloatingContainerService.EXTRA_SELECTION_RECEIVER, receiver)
        runCatching { startService(start) }.onFailure { error ->
            receiver.send(FileSelectionContract.RESULT_FALLBACK, Bundle().apply {
                putBoolean(FileSelectionContract.EXTRA_FALLBACK_TO_SOURCES, true)
                putString(FileSelectionContract.EXTRA_FAILURE_MESSAGE,
                    error.message ?: "InfiniFolder could not start its floating file selector")
            })
        }
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
        if (sources.isEmpty() || names.size != sources.size) {
            setResult(RESULT_CANCELED)
            finish()
            return
        }
        val relays = runCatching { sources.mapIndexed { index, source ->
            FileSelectionRelayProvider.register(this, source, names[index],
                mimes.getOrElse(index) { "application/octet-stream" }, sizes.getOrNull(index)?.takeIf { it >= 0L })
        } }.getOrElse {
            setResult(RESULT_CANCELED, Intent().putExtra(FileSelectionContract.EXTRA_FAILURE_MESSAGE,
                it.message ?: "Could not share this file"))
            finish()
            return
        }
        val name = names.first()
        val mime = mimes.firstOrNull()?.takeIf(String::isNotBlank) ?: "application/octet-stream"
        val result = Intent().setDataAndType(relays.first(), if (relays.size == 1) mime else "*/*").apply {
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
            putExtra("displayName", name)
            putExtra("mimeType", mime)
            clipData = android.content.ClipData.newRawUri(name, relays.first()).apply {
                relays.drop(1).forEach { addItem(android.content.ClipData.Item(it)) }
            }
        }
        setResult(RESULT_OK, result)
        finish()
    }
}
