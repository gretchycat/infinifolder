package com.infinifolder.audio.decoder

import com.infinifolder.audio.model.AudioPcmData
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

object VocDecoder {
    private const val VOC_MAGIC = "Creative Voice File\u001A"

    fun canDecode(bytes: ByteArray): Boolean {
        if (bytes.size < 26) return false
        val header = String(bytes, 0, 20, Charsets.ISO_8859_1)
        return header == VOC_MAGIC
    }

    fun decode(bytes: ByteArray): AudioPcmData? {
        if (!canDecode(bytes)) return null
        val headerLen = (bytes[20].toInt() and 0xFF) or ((bytes[21].toInt() and 0xFF) shl 8)
        var pos = headerLen.coerceAtLeast(26)

        var sampleRate = 22050
        var channels = 1
        var bitsPerSample = 8
        var isSigned16 = false

        val pcmOut = ByteArrayOutputStream()

        while (pos < bytes.size) {
            val blockType = bytes[pos++].toInt() and 0xFF
            if (blockType == 0) break // Terminator block
            if (pos + 3 > bytes.size) break

            val b0 = bytes[pos++].toInt() and 0xFF
            val b1 = bytes[pos++].toInt() and 0xFF
            val b2 = bytes[pos++].toInt() and 0xFF
            val blockSize = b0 or (b1 shl 8) or (b2 shl 16)
            val blockDataEnd = (pos + blockSize).coerceAtMost(bytes.size)

            when (blockType) {
                1 -> { // Voice data (legacy 8-bit)
                    if (pos + 2 <= blockDataEnd) {
                        val tc = bytes[pos++].toInt() and 0xFF
                        val pack = bytes[pos++].toInt() and 0xFF
                        sampleRate = if (tc < 256) (1000000 / (256 - tc)).coerceAtLeast(4000) else 22050
                        val dataLen = blockDataEnd - pos
                        if (dataLen > 0) {
                            pcmOut.write(bytes, pos, dataLen)
                            pos += dataLen
                        }
                    }
                }
                2 -> { // Voice continuation
                    val dataLen = blockDataEnd - pos
                    if (dataLen > 0) {
                        pcmOut.write(bytes, pos, dataLen)
                        pos += dataLen
                    }
                }
                8 -> { // Extra info (stereo/sample rate)
                    if (pos + 4 <= blockDataEnd) {
                        val tcDiv = (bytes[pos++].toInt() and 0xFF) or ((bytes[pos++].toInt() and 0xFF) shl 8)
                        val pack = bytes[pos++].toInt() and 0xFF
                        val mode = bytes[pos++].toInt() and 0xFF
                        channels = if (mode == 1) 2 else 1
                        val divisor = (65536 - tcDiv).coerceAtLeast(1)
                        sampleRate = (256000000L / (channels * divisor)).toInt().coerceIn(4000, 96000)
                    }
                    pos = blockDataEnd
                }
                9 -> { // Voice data (new format)
                    if (pos + 12 <= blockDataEnd) {
                        val sr = (bytes[pos].toInt() and 0xFF) or
                                ((bytes[pos + 1].toInt() and 0xFF) shl 8) or
                                ((bytes[pos + 2].toInt() and 0xFF) shl 16) or
                                ((bytes[pos + 3].toInt() and 0xFF) shl 24)
                        pos += 4
                        sampleRate = if (sr > 0) sr else 44100
                        bitsPerSample = bytes[pos++].toInt() and 0xFF
                        channels = bytes[pos++].toInt() and 0xFF
                        val format = (bytes[pos].toInt() and 0xFF) or ((bytes[pos + 1].toInt() and 0xFF) shl 8)
                        pos += 2
                        pos += 4 // Reserved
                        isSigned16 = (bitsPerSample == 16 && format == 4) || bitsPerSample == 16

                        val dataLen = blockDataEnd - pos
                        if (dataLen > 0) {
                            pcmOut.write(bytes, pos, dataLen)
                            pos += dataLen
                        }
                    }
                }
                else -> {
                    // Skip unsupported block types (loops, silence, markers)
                    pos = blockDataEnd
                }
            }
        }

        val rawPcm = pcmOut.toByteArray()
        if (rawPcm.isEmpty()) return null

        return if (isSigned16) {
            AudioPcmData.from16BitLittleEndian(rawPcm, sampleRate, channels)
        } else {
            AudioPcmData.from8BitUnsigned(rawPcm, sampleRate, channels)
        }
    }
}
