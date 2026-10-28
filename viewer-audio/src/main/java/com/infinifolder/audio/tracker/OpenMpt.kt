package com.infinifolder.audio.tracker

import android.util.Log
import com.infinifolder.audio.model.AudioPcmData

object OpenMpt {
    private const val TAG = "OpenMpt"
    private var isLoaded = false

    init {
        try {
            System.loadLibrary("c++_shared")
            System.loadLibrary("ogg")
            System.loadLibrary("vorbis")
            System.loadLibrary("vorbisfile")
            System.loadLibrary("mpg123")
            System.loadLibrary("openmpt")
            System.loadLibrary("infiniopenmpt")
            isLoaded = nativeIsAvailable()
            Log.i(TAG, "libopenmpt initialized successfully via JNI")
        } catch (e: Throwable) {
            Log.w(TAG, "libopenmpt JNI not available, will use fallback engine: ${e.message}")
            isLoaded = false
        }
    }

    fun isAvailable(): Boolean = isLoaded

    fun canDecode(bytes: ByteArray): Boolean {
        if (!isLoaded || bytes.size < 4) return false
        return try {
            nativeProbe(bytes)
        } catch (_: Throwable) {
            false
        }
    }

    fun renderToPcm(bytes: ByteArray, maxDurationMs: Long = 180000L, sampleRate: Int = 44100): AudioPcmData? {
        if (!isLoaded || bytes.isEmpty()) return null
        return try {
            val samples = nativeRenderToPcm(bytes, sampleRate, maxDurationMs)
            if (samples != null && samples.isNotEmpty()) {
                AudioPcmData(sampleRate, 2, samples, 16)
            } else {
                null
            }
        } catch (e: Throwable) {
            Log.w(TAG, "OpenMpt render failed: ${e.message}")
            null
        }
    }

    fun getDurationSeconds(bytes: ByteArray): Double {
        if (!isLoaded) return 0.0
        return try {
            nativeGetDurationSeconds(bytes)
        } catch (_: Throwable) {
            0.0
        }
    }

    fun getMetadata(bytes: ByteArray, key: String): String? {
        if (!isLoaded) return null
        return try {
            nativeGetMetadata(bytes, key)
        } catch (_: Throwable) {
            null
        }
    }

    @JvmStatic private external fun nativeIsAvailable(): Boolean
    @JvmStatic private external fun nativeProbe(data: ByteArray): Boolean
    @JvmStatic private external fun nativeGetMetadata(data: ByteArray, key: String): String?
    @JvmStatic private external fun nativeGetDurationSeconds(data: ByteArray): Double
    @JvmStatic private external fun nativeRenderToPcm(data: ByteArray, sampleRate: Int, maxDurationMs: Long): ShortArray?
}
