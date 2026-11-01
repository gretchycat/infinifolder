package com.infinifolder.audio.cast

import android.content.Context
import com.infinifolder.viewer.AudioTrack
import io.github.gretchycat.infinistream.PositionalReadable
import io.github.gretchycat.infinistream.SourceResource
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InterruptedIOException
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.FutureTask
import java.util.concurrent.ExecutionException
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

/** Serves the selected tracks to the Cast receiver over the local network. */
class CastMediaServer(private val context: Context) {
    private var socket: ServerSocket? = null
    private var executor: ExecutorService? = null
    private val running = AtomicBoolean(false)
    private val trackLock = Any()
    private val tracks = ConcurrentHashMap<String, AudioTrack>()
    private val sources = LinkedHashMap<String, TrackSource>(4,0.75f,true)
    private val clients = ConcurrentHashMap.newKeySet<Socket>()

    @Synchronized fun start(): Boolean {
        if (running.get()) return true
        return try {
            val listener = ServerSocket(0)
            socket = listener
            val workers = ThreadPoolExecutor(4,4,0,TimeUnit.MILLISECONDS,ArrayBlockingQueue<Runnable>(16))
            executor = workers
            running.set(true)
            Thread({
                while (running.get()) {
                    try {
                        val client = listener.accept()
                        clients.add(client)
                        if (!running.get()) { clients.remove(client); client.close(); continue }
                        try { workers.execute {
                            try { handle(client) }
                            catch (error: Exception) { android.util.Log.w("CastMediaServer","Client closed or failed",error) }
                        } }
                        catch (_: java.util.concurrent.RejectedExecutionException) { clients.remove(client); client.close() }
                    }
                    catch (_: Exception) { break }
                }
            }, "CastMediaServer").apply { isDaemon = true; start() }
            true
        } catch (e: Exception) {
            android.util.Log.w("CastMediaServer", "Cannot start server", e)
            false
        }
    }
    @Synchronized fun stop() {
        running.set(false)
        runCatching { socket?.close() }
        socket = null
        clients.forEach { runCatching { it.close() } }
        clients.clear()
        executor?.shutdownNow()
        executor = null
        val retired = synchronized(trackLock) { sources.values.toList().also { sources.clear(); tracks.clear() } }
        retired.forEach { it.retire() }
    }
    fun getStreamUrl(track: AudioTrack): String? {
        val ip = localAddress() ?: return null
        val token = UUID.randomUUID().toString()
        val port = socket?.localPort?.takeIf { running.get() } ?: return null
        synchronized(trackLock) {
            if (!running.get()) return null
            tracks[token] = track
        }
        return "http://${ip.hostAddress}:$port/$token"
    }
    private fun localAddress(): InetAddress? = try {
        val addresses = NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
            .sortedByDescending { it.name.contains("wlan", true) }
            .flatMap { it.inetAddresses.toList() }
        addresses.firstOrNull { it is Inet4Address && it.isSiteLocalAddress && !it.isLoopbackAddress }
    } catch (_: Exception) { null }

    private fun handle(client: Socket) {
        var requestMethod = "unknown"
        var requestRange: String? = null
        var requestName: String? = null
        try { client.use { s ->
            val output = BufferedOutputStream(s.getOutputStream())
            var responseStarted = false
            try {
                s.soTimeout = 15000
                val input = BufferedInputStream(s.getInputStream())
                var headerBytes = 0
                fun readLine(): String? {
                    val line = ByteArrayOutputStream(128)
                    while (true) {
                        val byte = input.read()
                        if (byte < 0) { if (line.size() == 0) return null; throw IOException("Truncated HTTP headers") }
                        if (++headerBytes > 65536 || line.size() >= 8192) throw IOException("HTTP headers exceed limit")
                        if (byte == 10) return line.toString("US-ASCII").removeSuffix("\r")
                        line.write(byte)
                    }
                }
                val parts = (readLine() ?: return).split(' ')
                if (parts.size < 2) return
                val method = parts[0]
                requestMethod = method
                var range: String? = null
                while (true) {
                    val line = readLine() ?: throw IOException("Truncated HTTP headers")
                    if (line.isEmpty()) break
                    if (line.startsWith("Range:", true)) range = line.substringAfter(':').trim()
                }
                requestRange = range
                if (method != "GET" && method != "HEAD") { error(output, 405, "Method Not Allowed"); return }
                val token = parts[1].substringBefore('?').removePrefix("/")
                val track = tracks[token]
                if (track == null) { error(output,404,"Not Found"); return }
                requestName = track.fileName
                com.infinifolder.storage.DiagnosticLog.record(
                    "cast.http.request",
                    "file=${track.fileName} method=$method range=${range ?: "full"}"
                )
                val (acquire,retired) = synchronized(trackLock) {
                    if (!running.get()) throw InterruptedIOException("Cast stopped")
                    val selected = sources[token] ?: TrackSource(context,track) { !running.get() }.also { sources[token]=it }
                    // Pin before eviction can retire the entry. Preparation runs
                    // outside the cache lock; another range can use its own view.
                    val reservation = selected.reserve()
                    val retryable = {
                        try { reservation() }
                        catch (error: Exception) {
                            synchronized(trackLock) { if (sources[token] === selected) sources.remove(token) }
                            selected.retire()
                            throw error
                        }
                    }
                    retryable to if (sources.size > 4) sources.remove(sources.keys.first()) else null
                }
                retired?.retire()
                acquire().use { lease -> lease.resource.open().use { source ->
                    val positional = source as? PositionalReadable ?: throw IOException("Cast source is not positional")
                    val size = source.metadata.length ?: throw IOException("Cast length is unknown")
                    val mime = source.metadata.content.mimeType ?: throw IOException("Cast content type is unknown")
                    com.infinifolder.storage.DiagnosticLog.record(
                        "cast.http.source",
                        "file=${track.fileName} mime=$mime bytes=$size"
                    )
                    responseStarted = true
                    val bounds = writeHeaders(output,mime,size,range) ?: return
                    output.flush()
                    if (method == "GET") {
                        val buffer = ByteArray(65536)
                        var position = bounds.first
                        var remaining = if (size == 0L) 0L else bounds.last-bounds.first+1
                        val requestedBytes = remaining
                        while (remaining > 0) {
                            if (!running.get() || Thread.currentThread().isInterrupted) throw InterruptedIOException("Cast stopped")
                            val wanted = minOf(buffer.size.toLong(),remaining).toInt()
                            val count = positional.readAt(position,buffer,0,wanted)
                            if (count <= 0 || count > wanted) throw IOException("Cast source ended before its declared length")
                            output.write(buffer,0,count); position+=count; remaining-=count
                        }
                        output.flush()
                        com.infinifolder.storage.DiagnosticLog.record(
                            "cast.http.complete",
                            "file=${track.fileName} method=$method range=${range ?: "full"} bytes=$requestedBytes"
                        )
                    } else {
                        com.infinifolder.storage.DiagnosticLog.record(
                            "cast.http.complete",
                            "file=${track.fileName} method=$method range=${range ?: "full"} bytes=0"
                        )
                    }
                } }
            } catch (e: Exception) {
                if (!responseStarted) runCatching { error(output,500,"Stream Failed") }
                android.util.Log.w("CastMediaServer", "Stream request failed", e)
                com.infinifolder.storage.DiagnosticLog.record(
                    "cast.http.failed",
                    "file=${requestName ?: "unknown"} method=$requestMethod range=${requestRange ?: "full"}",
                    e
                )
            }
        } } finally { clients.remove(client) }
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

/** One preparation per cached URL. Active requests retain evicted owners until they finish. */
private class TrackSource(context: Context, track: AudioTrack, private val serverStopped: () -> Boolean) {
    private val lock = Any()
    private val retired = AtomicBoolean(false)
    private var active = 0
    private var prepared: AudioTranscoder.PreparedAudio? = null
    private val work = FutureTask<AudioTranscoder.PreparedAudio> {
        val result = AudioTranscoder.open(context,track) {
            serverStopped() || (retired.get() && synchronized(lock) { active == 0 })
        }
        val keep = synchronized(lock) {
            if (serverStopped() || (retired.get() && active == 0)) false else { prepared=result; true }
        }
        if (!keep) { result.close(); throw InterruptedIOException("Cast source retired") }
        result
    }

    class Lease(val resource: SourceResource, private val release: () -> Unit) : Closeable {
        private val closed = AtomicBoolean(false)
        override fun close() { if (closed.compareAndSet(false,true)) release() }
    }

    fun reserve(): () -> Lease {
        synchronized(lock) {
            if (retired.get() || serverStopped()) throw InterruptedIOException("Cast source retired")
            active++
        }
        return ::acquireReserved
    }

    private fun acquireReserved(): Lease {
        try {
            work.run()
            val result = work.get()
            if (serverStopped()) throw InterruptedIOException("Cast stopped")
            return Lease(result.resource,::release)
        } catch (error: Exception) {
            release()
            if (error is InterruptedException) Thread.currentThread().interrupt()
            when (error) {
                is InterruptedException, is CancellationException -> throw InterruptedIOException("Cast preparation cancelled").also { it.initCause(error) }
                is ExecutionException -> throw IOException("Cannot prepare Cast source",error.cause)
                else -> throw error
            }
        }
    }

    fun retire() {
        retired.set(true)
        if (serverStopped() || synchronized(lock) { active == 0 }) work.cancel(true)
        closeIfIdle()
    }
    private fun release() { synchronized(lock) { active-- }; closeIfIdle() }
    private fun closeIfIdle() {
        val closing = synchronized(lock) {
            if (retired.get() && active == 0) prepared.also { prepared=null } else null
        }
        runCatching { closing?.close() }.onFailure { android.util.Log.w("CastMediaServer","Source close failed",it) }
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
