package com.infinifolder.audio.cast

import android.content.Context
import com.infinifolder.viewer.AudioTrack
import com.infinifolder.viewer.MediaSourceAdapter
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicBoolean

/** Serves the selected tracks to the Cast receiver over the local network. */
class CastMediaServer(private val context: Context) {
    private var socket: ServerSocket? = null
    private var executor: ExecutorService? = null
    private val running = AtomicBoolean(false)
    private val tracks = ConcurrentHashMap<String, AudioTrack>()

    fun start(): Boolean {
        if (running.get()) return true
        return try {
            val listener = ServerSocket(0)
            socket = listener
            executor = Executors.newCachedThreadPool()
            running.set(true)
            Thread({
                while (running.get()) {
                    try { val client = listener.accept(); executor?.submit { handle(client) } }
                    catch (_: Exception) { break }
                }
            }, "CastMediaServer").apply { isDaemon = true; start() }
            true
        } catch (e: Exception) {
            android.util.Log.w("CastMediaServer", "Cannot start server", e)
            false
        }
    }
    fun stop() {
        running.set(false)
        runCatching { socket?.close() }
        socket = null
        executor?.shutdownNow()
        executor = null
        tracks.clear()
    }
    fun getStreamUrl(track: AudioTrack): String? {
        val ip = localAddress() ?: return null
        val token = UUID.randomUUID().toString()
        tracks[token] = track
        return "http://${ip.hostAddress}:${socket?.localPort ?: return null}/$token"
    }
    private fun localAddress(): InetAddress? = try {
        val addresses = NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
            .sortedByDescending { it.name.contains("wlan", true) }
            .flatMap { it.inetAddresses.toList() }
        addresses.firstOrNull { it is Inet4Address && it.isSiteLocalAddress && !it.isLoopbackAddress }
    } catch (_: Exception) { null }

    private fun handle(client: Socket) {
        client.use { s ->
            try {
                s.soTimeout = 15000
                val input = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.US_ASCII))
                val output = BufferedOutputStream(s.getOutputStream())
                val parts = (input.readLine() ?: return).split(' ')
                if (parts.size < 2) return
                val method = parts[0]
                var range: String? = null
                while (true) {
                    val line = input.readLine() ?: break
                    if (line.isEmpty()) break
                    if (line.startsWith("Range:", true)) range = line.substringAfter(':').trim()
                }
                if (method != "GET" && method != "HEAD") { error(output, 405, "Method Not Allowed"); return }
                val track = tracks[parts[1].substringBefore('?').removePrefix("/")]
                if (track == null) { error(output, 404, "Not Found"); return }
                val name = track.uri.lastPathSegment ?: track.title
                val mime = AudioTranscoder.getMimeType(track.uri, name)
                if (AudioTranscoder.isDirectlyPlayableByCast(track.uri, name)) {
                    MediaSourceAdapter.stage(context, track.uri).use { source ->
                        RandomAccessFile(source.file, "r").use { file ->
                            val offset = track.startByte.coerceIn(0, file.length())
                            val available = file.length() - offset
                            val size = if (track.lengthBytes > 0) minOf(available, track.lengthBytes) else available
                            val bounds = writeHeaders(output, mime, size, range) ?: return
                            if (method == "GET") {
                                file.seek(offset + bounds.first)
                                var remaining = bounds.last - bounds.first + 1
                                val buffer = ByteArray(65536)
                                while (remaining > 0) {
                                    val read = file.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                                    if (read < 0) break
                                    output.write(buffer, 0, read); remaining -= read
                                }
                            }
                            output.flush()
                        }
                    }
                } else {
                    val data = AudioTranscoder.getOrTranscodeToWav(context, track.uri, name)
                    if (data == null) { error(output, 500, "Transcode Failed"); return }
                    val bounds = writeHeaders(output, "audio/wav", data.size.toLong(), range) ?: return
                    if (method == "GET" && bounds.last >= bounds.first)
                        output.write(data, bounds.first.toInt(), (bounds.last - bounds.first + 1).toInt())
                    output.flush()
                }
            } catch (e: Exception) { android.util.Log.w("CastMediaServer", "Stream request failed", e) }
        }
    }
    private fun writeHeaders(output: OutputStream, mime: String, size: Long, header: String?): LongRange? {
        val bounds = parseRange(header, size)
        if (bounds == null) {
            output.write("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */$size\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            output.flush(); return null
        }
        val partial = header != null
        val length = if (size == 0L) 0 else bounds.last - bounds.first + 1
        val contentRange = if (partial) "Content-Range: bytes ${bounds.first}-${bounds.last}/$size\r\n" else ""
        output.write(("HTTP/1.1 ${if (partial) "206 Partial Content" else "200 OK"}\r\nContent-Type: $mime\r\nAccept-Ranges: bytes\r\nContent-Length: $length\r\n${contentRange}Connection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
        return bounds
    }
    private fun error(output: OutputStream, code: Int, message: String) {
        output.write("HTTP/1.1 $code $message\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()); output.flush()
    }
}

internal fun parseRange(header: String?, size: Long): LongRange? {
    if (header == null) return 0L..(size - 1)
    if (size <= 0 || !header.startsWith("bytes=") || header.contains(',')) return null
    val pair = header.removePrefix("bytes=").split('-')
    if (pair.size != 2) return null
    if (pair[0].isEmpty()) {
        val suffix = pair[1].toLongOrNull()?.takeIf { it > 0 } ?: return null
        return (size - minOf(suffix, size))..(size - 1)
    }
    val start = pair[0].toLongOrNull()?.takeIf { it >= 0 && it < size } ?: return null
    val end = if (pair[1].isEmpty()) size - 1 else pair[1].toLongOrNull() ?: return null
    if (end < start) return null
    return start..minOf(end, size - 1)
}
