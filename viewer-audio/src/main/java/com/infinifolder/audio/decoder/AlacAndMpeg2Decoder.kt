package com.infinifolder.audio.decoder

import com.beatofthedrum.alacdecoder.AlacUtils
import com.infinifolder.audio.model.AudioPcmData
import javazoom.jl.decoder.Bitstream
import javazoom.jl.decoder.Decoder
import javazoom.jl.decoder.SampleBuffer
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

object AlacAndMpeg2Decoder {

    fun canDecode(bytes: ByteArray, extension: String? = null): Boolean {
        val ext = extension?.lowercase() ?: ""
        if (ext == "mp2" || ext == "alac") return true

        // MP2 frame sync: 1111 1111 111x x01x (Layer II)
        if (bytes.size >= 4 && (bytes[0].toInt() and 0xFF) == 0xFF) {
            val b1 = bytes[1].toInt() and 0xFF
            val layer = (b1 shr 1) and 0x03
            if ((b1 and 0xE0) == 0xE0 && layer == 2) return true
        }

        // MP4 container containing 'alac' atom or 'ftyp'
        if (bytes.size >= 16 && (String(bytes, 4, 4, Charsets.US_ASCII) == "ftyp" || String(bytes, 0, 4, Charsets.US_ASCII) == "moov")) {
            if (findAtom(bytes, "alac") >= 0) return true
            if (ext == "alac" || ext.endsWith("alac.m4a")) return true
        }

        return false
    }

    fun decode(bytes: ByteArray, extension: String? = null, file: File? = null): AudioPcmData? {
        val ext = extension?.lowercase() ?: file?.extension?.lowercase() ?: ""

        // 1. MP2 Decoder via JLayer (real MPEG Audio Layer II subband decompression)
        if (ext == "mp2" || (bytes.size >= 4 && (bytes[0].toInt() and 0xFF) == 0xFF && ((bytes[1].toInt() and 0x06) shr 1) == 2)) {
            val decoded = decodeMp2(bytes)
            if (decoded != null) return decoded
        }

        // 2. ALAC Decoder via Peter McQuillan's pure Java ALAC engine (real Apple Lossless decompression)
        val alacPos = findAtom(bytes, "alac")
        if (alacPos >= 0 || ext == "alac" || ext.endsWith("alac.m4a")) {
            val decoded = decodeAlac(bytes, file)
            if (decoded != null) return decoded
        }

        return null
    }

    private fun decodeMp2(bytes: ByteArray): AudioPcmData? {
        return try {
            val bis = ByteArrayInputStream(bytes)
            val bitstream = Bitstream(bis)
            val decoder = Decoder()
            val sampleList = mutableListOf<Short>()
            var sampleRate = 44100
            var channels = 2

            while (true) {
                val header = bitstream.readFrame() ?: break
                val output = decoder.decodeFrame(header, bitstream) as? SampleBuffer ?: break
                sampleRate = output.sampleFrequency
                channels = output.channelCount
                val buf = output.buffer
                val len = output.bufferLength
                for (i in 0 until len) {
                    sampleList.add(buf[i])
                }
                bitstream.closeFrame()
            }
            if (sampleList.isEmpty()) null
            else AudioPcmData(sampleRate, channels, sampleList.toShortArray(), 16)
        } catch (_: Throwable) {
            null
        }
    }

    private fun decodeAlac(bytes: ByteArray, file: File?): AudioPcmData? {
        val tempFile = if (file != null && file.exists()) file else {
            val f = File.createTempFile("alac_", ".m4a")
            f.writeBytes(bytes)
            f.deleteOnExit()
            f
        }
        val ac = AlacUtils.AlacOpenFileInput(tempFile.absolutePath)
        if (ac.error) {
            if (tempFile != file) tempFile.delete()
            return null
        }

        return try {
            val sampleRate = AlacUtils.AlacGetSampleRate(ac)
            val channels = AlacUtils.AlacGetNumChannels(ac)
            val destBufferSize = 1024 * 24 * 3
            val pDestBuffer = IntArray(destBufferSize)
            val sampleList = mutableListOf<Short>()

            while (true) {
                val bytesUnpacked = AlacUtils.AlacUnpackSamples(ac, pDestBuffer)
                if (bytesUnpacked <= 0) break
                val numSamples = bytesUnpacked / 2
                for (i in 0 until numSamples) {
                    sampleList.add(pDestBuffer[i].toShort())
                }
            }
            if (sampleList.isEmpty()) null
            else AudioPcmData(sampleRate, channels, sampleList.toShortArray(), 16)
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { ac.close() }
            if (tempFile != file) tempFile.delete()
        }
    }

    private fun findAtom(bytes: ByteArray, atomName: String): Int {
        val target = atomName.toByteArray(Charsets.US_ASCII)
        for (i in 0 until (bytes.size - 4)) {
            if (bytes[i] == target[0] && bytes[i + 1] == target[1] &&
                bytes[i + 2] == target[2] && bytes[i + 3] == target[3]
            ) {
                return i
            }
        }
        return -1
    }
}
