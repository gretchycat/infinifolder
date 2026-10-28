package com.infinifolder.audio.cast

import android.content.Context
import com.infinifolder.viewer.AudioPlayerService
import com.infinifolder.viewer.AudioTrack
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Central manager for audio casting across Google Cast, DLNA renderers,
 * and smart TV web players.
 */
object CastManager {

    interface Listener {
        fun onCastStateChanged(isCasting: Boolean, device: CastDevice?)
        fun onDiscoveredDevicesUpdated(devices: List<CastDevice>)
        fun onCastPlaybackStateChanged(isPlaying: Boolean)
    }

    private var mediaServer: CastMediaServer? = null
    private var discovery: CastDeviceDiscovery? = null
    private val listeners = CopyOnWriteArrayList<Listener>()

    var activeDevice: CastDevice? = null
        private set

    var isRemotePlaying: Boolean = false
        private set

    val isCasting: Boolean
        get() = activeDevice != null

    fun addListener(l: Listener) {
        if (!listeners.contains(l)) listeners.add(l)
    }

    fun removeListener(l: Listener) {
        listeners.remove(l)
    }

    fun ensureServerStarted(context: Context): CastMediaServer {
        mediaServer?.let { return it }
        val server = CastMediaServer(context.applicationContext)
        server.start()
        mediaServer = server
        return server
    }

    fun startDiscovery(context: Context) {
        ensureServerStarted(context)
        if (discovery == null) {
            discovery = CastDeviceDiscovery(context.applicationContext) { devices ->
                listeners.forEach { it.onDiscoveredDevicesUpdated(devices) }
            }
        }
        discovery?.startDiscovery()
    }

    fun stopDiscovery() {
        discovery?.stopDiscovery()
    }

    fun getDiscoveredDevices(): List<CastDevice> {
        return discovery?.getDiscoveredDevices() ?: emptyList()
    }

    fun getStreamUrl(): String? {
        return mediaServer?.getStreamUrl()
    }

    fun getWebPlayerUrl(): String? {
        return mediaServer?.getWebPlayerUrl()
    }

    fun connectToDevice(context: Context, device: CastDevice, onResult: (Boolean) -> Unit) {
        val server = ensureServerStarted(context)
        val streamUrl = server.getStreamUrl()
        val service = AudioPlayerService.currentService
        val track = service?.currentTrack

        if (streamUrl == null) {
            onResult(false)
            return
        }

        Thread {
            val success = when (device.protocol) {
                CastProtocol.DLNA -> {
                    val controlUrl = device.controlUrl
                    if (controlUrl != null) {
                        DlnaController.setSourceAndPlay(
                            controlUrl,
                            streamUrl,
                            track?.title ?: "InfiniFolder Audio",
                            track?.artist
                        )
                    } else false
                }
                CastProtocol.CHROMECAST, CastProtocol.WEB_STREAM -> {
                    true
                }
            }

            if (success) {
                activeDevice = device
                isRemotePlaying = true
                // Pause phone speaker output while remote renderer plays
                service?.pause()
                listeners.forEach {
                    it.onCastStateChanged(true, device)
                    it.onCastPlaybackStateChanged(true)
                }
            }
            onResult(success)
        }.start()
    }

    fun disconnect() {
        val dev = activeDevice ?: return
        activeDevice = null
        isRemotePlaying = false

        Thread {
            if (dev.protocol == CastProtocol.DLNA && dev.controlUrl != null) {
                DlnaController.stop(dev.controlUrl)
            }
        }.start()

        listeners.forEach {
            it.onCastStateChanged(false, null)
            it.onCastPlaybackStateChanged(false)
        }
    }

    fun play() {
        val dev = activeDevice ?: return
        isRemotePlaying = true
        if (dev.protocol == CastProtocol.DLNA && dev.controlUrl != null) {
            Thread { DlnaController.play(dev.controlUrl) }.start()
        }
        listeners.forEach { it.onCastPlaybackStateChanged(true) }
    }

    fun pause() {
        val dev = activeDevice ?: return
        isRemotePlaying = false
        if (dev.protocol == CastProtocol.DLNA && dev.controlUrl != null) {
            Thread { DlnaController.pause(dev.controlUrl) }.start()
        }
        listeners.forEach { it.onCastPlaybackStateChanged(false) }
    }

    fun togglePlayPause() {
        if (isRemotePlaying) {
            pause()
        } else {
            play()
        }
    }

    fun seek(positionMs: Long) {
        val dev = activeDevice ?: return
        if (dev.protocol == CastProtocol.DLNA && dev.controlUrl != null) {
            Thread { DlnaController.seek(dev.controlUrl, positionMs) }.start()
        }
    }

    fun onTrackChanged(track: AudioTrack?) {
        val dev = activeDevice ?: return
        val streamUrl = mediaServer?.getStreamUrl() ?: return
        if (track == null) return
        isRemotePlaying = true
        listeners.forEach { it.onCastPlaybackStateChanged(true) }

        if (dev.protocol == CastProtocol.DLNA && dev.controlUrl != null) {
            Thread {
                DlnaController.setSourceAndPlay(
                    dev.controlUrl,
                    streamUrl,
                    track.title,
                    track.artist
                )
            }.start()
        }
    }
}
