package com.infinifolder.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RealAudioVisualizerBridgeTest {

    @Test
    fun testInitialBuffersAreZero() {
        val bridge = RealAudioVisualizerBridge()
        val bands = FloatArray(16)
        val peaks = FloatArray(16)
        val wave = FloatArray(76)
        val vu = FloatArray(4)

        bridge.getSpectrum(bands, peaks, isPlaying = false)
        bridge.getWaveform(wave, isPlaying = false)
        bridge.getVuLevels(vu, isPlaying = false)

        for (b in bands) {
            assertEquals(0f, b, 0.001f)
        }
        for (p in peaks) {
            assertEquals(0f, p, 0.001f)
        }
        for (w in wave) {
            assertEquals(0f, w, 0.001f)
        }
        for (v in vu) {
            assertEquals(0f, v, 0.001f)
        }
    }

    @Test
    fun testResetClearsData() {
        val bridge = RealAudioVisualizerBridge()
        bridge.reset()

        val bands = FloatArray(16)
        bridge.getSpectrum(bands, null, isPlaying = true)
        for (b in bands) {
            assertEquals(0f, b, 0.001f)
        }
    }
}
