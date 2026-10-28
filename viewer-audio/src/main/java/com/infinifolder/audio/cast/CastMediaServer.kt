package com.infinifolder.audio.cast

import android.content.Context
import android.net.Uri
import com.infinifolder.viewer.AudioPlayerService
import com.infinifolder.viewer.MediaSourceAdapter
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Embedded HTTP streaming server that serves audio to Google Cast, DLNA renderers,
 * and smart TV web browsers on the local network. Supports HTTP Range requests and
 * transparently transcodes unsupported formats into standard PCM WAV.
 */
class CastMediaServer(
    private val context: Context,
    private val preferredPort: Int = 8192
) {
    private var serverSocket: ServerSocket? = null
    private val isRunning = AtomicBoolean(false)
    private var executor: ExecutorService? = null

    var serverPort: Int = 0
        private set

    fun start(): Boolean {
        if (isRunning.get()) return true
        return try {
            val s = try {
                ServerSocket(preferredPort)
            } catch (_: Exception) {
                ServerSocket(0) // dynamic fallback port
            }
            serverSocket = s
            serverPort = s.localPort
            isRunning.set(true)
            executor = Executors.newCachedThreadPool()

            Thread({
                while (isRunning.get() && !s.isClosed) {
                    try {
                        val client = s.accept()
                        executor?.submit { handleClient(client) }
                    } catch (_: Exception) {
                        break
                    }
                }
            }, "CastMediaServer-Accept").apply {
                isDaemon = true
                start()
            }
            true
        } catch (e: Exception) {
            android.util.Log.w("CastMediaServer", "Failed to start media server: ${e.message}", e)
            false
        }
    }

    fun stop() {
        if (!isRunning.compareAndSet(true, false)) return
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null
        executor?.shutdownNow()
        executor = null
    }

    fun getStreamUrl(trackIndex: Int = -1): String? {
        val ip = getLocalIpAddress() ?: return null
        val path = if (trackIndex >= 0) "/track/$trackIndex" else "/stream"
        return "http://${ip.hostAddress}:$serverPort$path"
    }

    fun getWebPlayerUrl(): String? {
        val ip = getLocalIpAddress() ?: return null
        return "http://${ip.hostAddress}:$serverPort/web"
    }

    fun getLocalIpAddress(): InetAddress? {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            var fallback: InetAddress? = null
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isLoopback || !iface.isUp) continue
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (!addr.isLoopbackAddress && addr is Inet4Address && addr.isSiteLocalAddress) {
                        if (iface.name.contains("wlan", true)) return addr
                        if (fallback == null) fallback = addr
                    }
                }
            }
            fallback
        } catch (_: Exception) {
            null
        }
    }

    private fun handleClient(socket: Socket) {
        socket.use { s ->
            s.soTimeout = 15000
            val input = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.US_ASCII))
            val output = BufferedOutputStream(s.getOutputStream())

            val reqLine = input.readLine() ?: return
            val parts = reqLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0].uppercase()
            val uriPath = parts[1]

            // Parse headers
            var rangeHeader: String? = null
            var line: String? = input.readLine()
            while (!line.isNullOrBlank()) {
                if (line.startsWith("Range:", ignoreCase = true)) {
                    rangeHeader = line.substringAfter(':').trim()
                }
                line = input.readLine()
            }

            if (method != "GET" && method != "HEAD") {
                sendResponse(output, 405, "Method Not Allowed", "text/plain", "Method not allowed".toByteArray())
                return
            }

            when {
                uriPath == "/web" -> serveWebPlayer(output)
                uriPath == "/status.json" -> serveStatusJson(output)
                uriPath.startsWith("/stream") || uriPath.startsWith("/track/") -> {
                    serveAudioStream(output, uriPath, rangeHeader, method == "HEAD")
                }
                else -> {
                    sendResponse(output, 404, "Not Found", "text/plain", "Not Found".toByteArray())
                }
            }
        }
    }

    private fun serveAudioStream(output: OutputStream, uriPath: String, rangeHeader: String?, isHead: Boolean) {
        val service = AudioPlayerService.currentService
        val track = if (uriPath.startsWith("/track/")) {
            val idx = uriPath.substringAfter("/track/").substringBefore('?').toIntOrNull() ?: -1
            service?.playlist?.getOrNull(idx)
        } else {
            service?.currentTrack
        }

        if (track == null) {
            sendResponse(output, 404, "Not Found", "text/plain", "No active track".toByteArray())
            return
        }

        val uri = track.uri
        val name = track.title
        val directlyCompatible = AudioTranscoder.isDirectlyPlayableByCast(uri, name)

        if (directlyCompatible) {
            serveDirectStream(output, uri, name, rangeHeader, isHead)
        } else {
            serveTranscodedWav(output, uri, name, rangeHeader, isHead)
        }
    }

    private fun serveDirectStream(output: OutputStream, uri: Uri, name: String, rangeHeader: String?, isHead: Boolean) {
        try {
            val mime = AudioTranscoder.getMimeType(uri, name)
            val fullBytes = MediaSourceAdapter.openStream(context, uri).use { it.readBytes() }
            serveBytesWithRange(output, fullBytes, mime, rangeHeader, isHead)
        } catch (e: Exception) {
            sendResponse(output, 500, "Internal Server Error", "text/plain", "Error: ${e.message}".toByteArray())
        }
    }

    private fun serveTranscodedWav(output: OutputStream, uri: Uri, name: String, rangeHeader: String?, isHead: Boolean) {
        val wavBytes = AudioTranscoder.getOrTranscodeToWav(context, uri, name)
        if (wavBytes == null) {
            sendResponse(output, 500, "Internal Server Error", "text/plain", "Transcode failed".toByteArray())
            return
        }
        serveBytesWithRange(output, wavBytes, "audio/wav", rangeHeader, isHead)
    }

    private fun serveBytesWithRange(
        output: OutputStream,
        data: ByteArray,
        mimeType: String,
        rangeHeader: String?,
        isHead: Boolean
    ) {
        val totalLength = data.size.toLong()
        var start = 0L
        var end = totalLength - 1L

        val isPartial = if (!rangeHeader.isNullOrBlank() && rangeHeader.startsWith("bytes=")) {
            val rangeSpec = rangeHeader.substringAfter("bytes=").trim()
            val dashIdx = rangeSpec.indexOf('-')
            if (dashIdx >= 0) {
                val startStr = rangeSpec.substring(0, dashIdx).trim()
                val endStr = rangeSpec.substring(dashIdx + 1).trim()
                if (startStr.isNotEmpty()) start = startStr.toLong().coerceIn(0L, totalLength - 1L)
                if (endStr.isNotEmpty()) end = endStr.toLong().coerceIn(start, totalLength - 1L)
                true
            } else false
        } else false

        val contentLength = end - start + 1L
        val statusLine = if (isPartial) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n"
        val sb = StringBuilder()
        sb.append(statusLine)
        sb.append("Content-Type: $mimeType\r\n")
        sb.append("Accept-Ranges: bytes\r\n")
        sb.append("Content-Length: $contentLength\r\n")
        if (isPartial) {
            sb.append("Content-Range: bytes $start-$end/$totalLength\r\n")
        }
        sb.append("Access-Control-Allow-Origin: *\r\n")
        sb.append("Connection: keep-alive\r\n\r\n")

        output.write(sb.toString().toByteArray(Charsets.US_ASCII))
        if (!isHead && contentLength > 0) {
            output.write(data, start.toInt(), contentLength.toInt())
        }
        output.flush()
    }

    private fun serveStatusJson(output: OutputStream) {
        val s = AudioPlayerService.currentService
        val track = s?.currentTrack
        val json = """
            {
                "isPlaying": ${s?.isPlaying == true},
                "title": "${track?.title?.replace("\"", "\\\"") ?: "No Track"}",
                "artist": "${track?.artist?.replace("\"", "\\\"") ?: ""}",
                "album": "${track?.album?.replace("\"", "\\\"") ?: ""}",
                "positionMs": ${s?.currentPosition ?: 0},
                "durationMs": ${s?.currentDuration ?: 0}
            }
        """.trimIndent()
        sendResponse(output, 200, "OK", "application/json", json.toByteArray())
    }

    private fun serveWebPlayer(output: OutputStream) {
        val s = AudioPlayerService.currentService
        val track = s?.currentTrack
        val title = track?.title ?: "InfiniFolder Stream"
        val artist = track?.artist ?: "Local Audio Player"
        val html = """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>$title - InfiniFolder Cast</title>
                <style>
                    body {
                        font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
                        background: #111;
                        color: #00FF66;
                        display: flex;
                        flex-direction: column;
                        align-items: center;
                        justify-content: center;
                        min-height: 100vh;
                        margin: 0;
                        padding: 16px;
                    }
                    .card {
                        background: #1c1c24;
                        border: 2px solid #333;
                        border-radius: 12px;
                        padding: 24px;
                        max-width: 400px;
                        width: 100%;
                        box-shadow: 0 8px 24px rgba(0,0,0,0.8);
                        text-align: center;
                    }
                    h1 { font-size: 1.2rem; margin: 0 0 8px 0; color: #fff; word-break: break-word; }
                    h2 { font-size: 0.9rem; margin: 0 0 16px 0; color: #aaa; }
                    audio { width: 100%; margin-top: 16px; outline: none; }
                    .badge {
                        display: inline-block;
                        padding: 4px 8px;
                        border-radius: 4px;
                        background: #003311;
                        color: #00FF66;
                        font-size: 0.75rem;
                        font-family: monospace;
                        margin-bottom: 12px;
                    }
                </style>
            </head>
            <body>
                <div class="card">
                    <div class="badge">📡 INFINIFOLDER CAST STREAM</div>
                    <h1>$title</h1>
                    <h2>$artist</h2>
                    <audio controls autoplay src="/stream"></audio>
                </div>
            </body>
            </html>
        """.trimIndent()
        sendResponse(output, 200, "OK", "text/html; charset=utf-8", html.toByteArray())
    }

    private fun sendResponse(output: OutputStream, code: Int, msg: String, mime: String, body: ByteArray) {
        val header = "HTTP/1.1 $code $msg\r\n" +
                "Content-Type: $mime\r\n" +
                "Content-Length: ${body.size}\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Connection: close\r\n\r\n"
        output.write(header.toByteArray(Charsets.US_ASCII))
        output.write(body)
        output.flush()
    }
}
