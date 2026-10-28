package com.infinifolder.audio.cast

import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Controller executing UPnP/DLNA AVTransport:1 SOAP control commands (Play, Pause, Stop, Seek)
 * to stream and control playback on smart TVs, speakers, and AV receivers.
 */
object DlnaController {

    private const val AV_TRANSPORT_SERVICE = "urn:schemas-upnp-org:service:AVTransport:1"

    fun setSourceAndPlay(controlUrl: String, streamUrl: String, title: String, artist: String? = null): Boolean {
        if (!setAvTransportUri(controlUrl, streamUrl, title, artist)) return false
        return play(controlUrl)
    }

    fun setAvTransportUri(controlUrl: String, streamUrl: String, title: String, artist: String? = null): Boolean {
        val metadata = buildDidlMetadata(streamUrl, title, artist)
        val body = """
            <u:SetAVTransportURI xmlns:u="$AV_TRANSPORT_SERVICE">
                <InstanceID>0</InstanceID>
                <CurrentURI>$streamUrl</CurrentURI>
                <CurrentURIMetaData>$metadata</CurrentURIMetaData>
            </u:SetAVTransportURI>
        """.trimIndent()
        return sendSoapRequest(controlUrl, "SetAVTransportURI", body)
    }

    fun play(controlUrl: String): Boolean {
        val body = """
            <u:Play xmlns:u="$AV_TRANSPORT_SERVICE">
                <InstanceID>0</InstanceID>
                <Speed>1</Speed>
            </u:Play>
        """.trimIndent()
        return sendSoapRequest(controlUrl, "Play", body)
    }

    fun pause(controlUrl: String): Boolean {
        val body = """
            <u:Pause xmlns:u="$AV_TRANSPORT_SERVICE">
                <InstanceID>0</InstanceID>
            </u:Pause>
        """.trimIndent()
        return sendSoapRequest(controlUrl, "Pause", body)
    }

    fun stop(controlUrl: String): Boolean {
        val body = """
            <u:Stop xmlns:u="$AV_TRANSPORT_SERVICE">
                <InstanceID>0</InstanceID>
            </u:Stop>
        """.trimIndent()
        return sendSoapRequest(controlUrl, "Stop", body)
    }

    fun seek(controlUrl: String, positionMs: Long): Boolean {
        val totalSec = positionMs / 1000L
        val hours = totalSec / 3600L
        val minutes = (totalSec % 3600L) / 60L
        val seconds = totalSec % 60L
        val timeStr = String.format("%02d:%02d:%02d", hours, minutes, seconds)

        val body = """
            <u:Seek xmlns:u="$AV_TRANSPORT_SERVICE">
                <InstanceID>0</InstanceID>
                <Unit>REL_TIME</Unit>
                <Target>$timeStr</Target>
            </u:Seek>
        """.trimIndent()
        return sendSoapRequest(controlUrl, "Seek", body)
    }

    private fun sendSoapRequest(controlUrl: String, action: String, actionBody: String): Boolean {
        return try {
            val url = URL(controlUrl)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 5000
                readTimeout = 5000
                doOutput = true
                setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
                setRequestProperty("SOAPACTION", "\"$AV_TRANSPORT_SERVICE#$action\"")
            }

            val envelope = """
                <?xml version="1.0" encoding="utf-8"?>
                <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
                    <s:Body>
                        $actionBody
                    </s:Body>
                </s:Envelope>
            """.trimIndent()

            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use {
                it.write(envelope)
                it.flush()
            }

            val code = conn.responseCode
            conn.disconnect()
            code in 200..299
        } catch (e: Exception) {
            android.util.Log.w("DlnaController", "SOAP $action failed on $controlUrl: ${e.message}", e)
            false
        }
    }

    private fun buildDidlMetadata(streamUrl: String, title: String, artist: String?): String {
        val escapedTitle = escapeXml(title)
        val escapedArtist = escapeXml(artist ?: "InfiniFolder")
        val rawDidl = """<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/"><item id="1" parentID="0" restricted="1"><dc:title>$escapedTitle</dc:title><dc:creator>$escapedArtist</dc:creator><upnp:class>object.item.audioItem.musicTrack</upnp:class><res protocolInfo="http-get:*:audio/*:*">$streamUrl</res></item></DIDL-Lite>"""
        return escapeXml(rawDidl)
    }

    private fun escapeXml(str: String): String {
        return str.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }
}
