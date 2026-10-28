package com.infinifolder.audio.cast

import java.net.InetAddress

enum class CastProtocol {
    DLNA,
    CHROMECAST,
    WEB_STREAM
}

data class CastDevice(
    val id: String,
    val name: String,
    val protocol: CastProtocol,
    val ip: InetAddress,
    val port: Int,
    val controlUrl: String? = null,
    val manufacturer: String? = null,
    val model: String? = null,
    val iconUrl: String? = null
) {
    override fun toString(): String = "$name (${protocol.name}) @ ${ip.hostAddress}:$port"
}
