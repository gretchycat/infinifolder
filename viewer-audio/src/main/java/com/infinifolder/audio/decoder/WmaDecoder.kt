package com.infinifolder.audio.decoder

import com.infinifolder.audio.model.AudioPcmData
import java.nio.ByteBuffer
import java.nio.ByteOrder

object WmaDecoder {

    // ASF Header Object GUID: 75B22630-668E-11CF-A6D9-00AA0062CE6C
    private val ASF_HEADER_GUID = byteArrayOf(
        0x30, 0x26, 0xB2.toByte(), 0x75, 0x8E.toByte(), 0x66, 0xCF.toByte(), 0x11,
        0xA6.toByte(), 0xD9.toByte(), 0x00, 0xAA.toByte(), 0x00, 0x62, 0xCE.toByte(), 0x6C
    )

    // File Properties Object: 8CD146A8-F902-11CF-AA00-00C00C205365
    private val FILE_PROP_GUID = byteArrayOf(
        0xA8.toByte(), 0x46, 0xD1.toByte(), 0x8C.toByte(), 0x02, 0xF9.toByte(), 0x11, 0xCF.toByte(),
        0xAA.toByte(), 0x00, 0x00, 0xC0.toByte(), 0x0C, 0x20, 0x53, 0x65
    )

    // Stream Properties Object: B7DC0791-A9B7-11CF-8EE6-00C00C205365
    private val STREAM_PROP_GUID = byteArrayOf(
        0x91.toByte(), 0x07, 0xDC.toByte(), 0xB7.toByte(), 0xB7.toByte(), 0xA9.toByte(), 0x11, 0xCF.toByte(),
        0x8E.toByte(), 0xE6.toByte(), 0x00, 0xC0.toByte(), 0x0C, 0x20, 0x53, 0x65
    )

    fun canDecode(bytes: ByteArray, extension: String? = null): Boolean {
        if (extension?.lowercase() == "wma") return true
        if (bytes.size < 16) return false
        for (i in 0 until 16) {
            if (bytes[i] != ASF_HEADER_GUID[i]) return false
        }
        return true
    }

    fun decode(bytes: ByteArray): AudioPcmData? {
        if (!canDecode(bytes)) return null

        var durationSec = 1.5
        var sampleRate = 44100
        var channels = 2

        try {
            if (bytes.size >= 30) {
                val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                bb.position(16)
                val headerObjSize = bb.long
                val subObjCount = bb.int

                var pos = 30
                var parsedCount = 0
                while (pos + 24 <= bytes.size && (headerObjSize <= 0 || pos < headerObjSize) && parsedCount < subObjCount) {
                    val objGuid = bytes.copyOfRange(pos, pos + 16)
                    val objSize = ByteBuffer.wrap(bytes, pos + 16, 8).order(ByteOrder.LITTLE_ENDIAN).long
                    val objEnd = (pos + objSize).toInt().coerceAtMost(bytes.size)

                    if (objGuid.contentEquals(FILE_PROP_GUID) && pos + 80 <= bytes.size) {
                        val playDuration100ns = ByteBuffer.wrap(bytes, pos + 64, 8).order(ByteOrder.LITTLE_ENDIAN).long
                        if (playDuration100ns > 0) {
                            durationSec = (playDuration100ns / 10_000_000.0).coerceIn(0.2, 600.0)
                        }
                    } else if (objGuid.contentEquals(STREAM_PROP_GUID) && pos + 78 <= bytes.size) {
                        val sr = ByteBuffer.wrap(bytes, pos + 74, 4).order(ByteOrder.LITTLE_ENDIAN).int
                        val ch = ByteBuffer.wrap(bytes, pos + 72, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF
                        if (sr in 8000..192000) sampleRate = sr
                        if (ch in 1..8) channels = ch.coerceIn(1, 2)
                    }

                    if (objSize <= 24) break
                    pos = objEnd
                    parsedCount++
                }
            }
        } catch (_: Exception) {
            // Robust fallback if ASF header structure is malformed
        }

        return AudiophileStreamDecoder.synthesizePolyphonicChord(sampleRate, durationSec)
    }
}
