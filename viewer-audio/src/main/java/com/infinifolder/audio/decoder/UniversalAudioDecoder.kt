package com.infinifolder.audio.decoder

import com.infinifolder.audio.AudioDecodeObserver
import com.infinifolder.audio.AudioDecodeRequest
import com.infinifolder.audio.AudioDecodeResult
import com.infinifolder.audio.AudioProbe
import com.infinifolder.audio.AudioProcessing
import com.infinifolder.audio.AudioProcessor
import com.infinifolder.audio.diagnostics.AudioPlaybackDiagnostics
import com.infinifolder.audio.model.AudioPcmData
import java.io.File

/** Connects library decode events to the existing playback reports. */
object UniversalAudioDecoder {
    private val processor: AudioProcessor = AudioProcessing
    private val diagnostics = object : AudioDecodeObserver {
        override fun decoded(result: AudioDecodeResult) {
            AudioPlaybackDiagnostics.recordDecoderResult(result.decoder, result.pcm, sourceFormat = result.sourceFormat)
        }
        override fun warning(decoder: String, error: Throwable) {
            AudioPlaybackDiagnostics.recordParserWarning("Decoder $decoder failed: ${error.message}", error)
        }
        override fun unsupported() {
            AudioPlaybackDiagnostics.recordDecoderResult("UniversalAudioDecoder", null)
        }
    }

    val SUPPORTED_EXTENSIONS: Set<String> get() = AudioProcessing.supportedExtensions

    fun isSupported(bytes: ByteArray, extension: String? = null): Boolean =
        processor.isSupported(AudioProbe(bytes, extension))

    fun decode(bytes: ByteArray, extension: String? = null, file: File? = null, reportDiagnostics: Boolean = true): AudioPcmData? =
        processor.decode(AudioDecodeRequest(bytes, extension, file), if (reportDiagnostics) diagnostics else null)?.pcm

    fun prepareForOutput(source: File, destination: File, reportDiagnostics: Boolean = true,
        cancelled: () -> Boolean = { false }): com.infinifolder.audio.AudioPreparation =
        AudioProcessing.prepareForOutput(source, destination, observer = if (reportDiagnostics) object : com.infinifolder.audio.AudioPreparationObserver {
            override fun warning(decoder: String, error: Throwable) {
                if (!cancelled()) diagnostics.warning(decoder, error)
            }
            override fun prepared(result: com.infinifolder.audio.AudioPreparation) {
                if (!cancelled()) AudioPlaybackDiagnostics.recordPreparationResult(result)
            }
        } else null, cancelled = cancelled)
}
