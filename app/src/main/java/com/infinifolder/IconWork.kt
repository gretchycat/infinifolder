package com.infinifolder

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Isolates storage probes from display workers; a stalled provider cannot stall rendering. */
object IconWork {
    private val workers = java.util.concurrent.ThreadPoolExecutor(2,2,30,TimeUnit.SECONDS,java.util.concurrent.ArrayBlockingQueue<Runnable>(32))
    fun <T> bounded(timeoutMs: Long = 1500, action: () -> T): T? {
        val task = try {workers.submit<T> {action()}} catch(_:java.util.concurrent.RejectedExecutionException) {return null}
        return try { task.get(timeoutMs, TimeUnit.MILLISECONDS) }
        catch (_: InterruptedException) {task.cancel(true);Thread.currentThread().interrupt();null}
        catch (_: Exception) { task.cancel(true); null }
    }
}
