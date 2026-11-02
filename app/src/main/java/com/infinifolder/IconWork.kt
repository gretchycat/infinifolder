package com.infinifolder

import com.infinifolder.storage.DiagnosticLog
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Isolates storage probes from display workers; a stalled provider cannot stall rendering. */
object IconWork {
    // A provider that ignores interruption must not occupy another provider's workers.
    // Separate directory enumeration and content decoding from small metadata/sidecar reads.
    private val lanes = ConcurrentHashMap<String, ThreadPoolExecutor>()
    fun <T> bounded(timeoutMs: Long = 1500, lane: String = "general", action: () -> T): T? {
        val workers=lanes.computeIfAbsent(lane) { name ->
            ThreadPoolExecutor(2,2,30,TimeUnit.SECONDS,java.util.concurrent.ArrayBlockingQueue<Runnable>(32),
                java.util.concurrent.ThreadFactory { Thread(it,"IconWork-$name").apply {isDaemon=true} })
                .apply {allowCoreThreadTimeOut(true)}
        }
        val queuedAt=System.nanoTime()
        val task = try {workers.submit<T> {action()}} catch(_:java.util.concurrent.RejectedExecutionException) {return null}
        return try { task.get(timeoutMs, TimeUnit.MILLISECONDS) }
        catch (_: InterruptedException) {task.cancel(true);Thread.currentThread().interrupt();null}
        catch (_: Exception) { task.cancel(true); null }
        finally {
            if(task.isCancelled)workers.remove(task as Runnable)
            val elapsed=(System.nanoTime()-queuedAt)/1_000_000
            if(elapsed>=100)DiagnosticLog.record("artwork.work.slow",
                "lane=$lane elapsedMs=$elapsed cancelled=${task.isCancelled} active=${workers.activeCount} queued=${workers.queue.size}")
        }
    }
}
