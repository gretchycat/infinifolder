package com.infinifolder.ops

import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean

enum class OperationState { QUEUED, RUNNING, COMPLETE, FAILED, CANCELLED }
data class OperationSnapshot(val id: String, val label: String, val state: OperationState, val bytes: Long, val totalBytes: Long?, val message: String?)

/** UI-independent bounded queue. A future Android foreground worker can own the same operation records. */
class OperationCoordinator(private val concurrency: Int = 2) {
    private val executor = Executors.newFixedThreadPool(concurrency.coerceIn(1, 3))
    private val cancelled = mutableMapOf<String, AtomicBoolean>()
    private val records = mutableMapOf<String, OperationSnapshot>()
    private val futures = mutableMapOf<String, Future<*>>()
    @Synchronized fun snapshots(): List<OperationSnapshot> = records.values.toList()
    @Synchronized fun submit(label: String, totalBytes: Long? = null, work: (progress: (Long) -> Unit, isCancelled: () -> Boolean) -> Unit): String {
        val id = UUID.randomUUID().toString()
        val token = AtomicBoolean(false)
        cancelled[id] = token
        records[id] = OperationSnapshot(id, label, OperationState.QUEUED, 0, totalBytes, null)
        futures[id] = executor.submit {
            update(id) { it.copy(state = OperationState.RUNNING) }
            try {
                work({ bytes -> update(id) { it.copy(bytes = bytes) } }, { token.get() })
                update(id) { it.copy(state = if (token.get()) OperationState.CANCELLED else OperationState.COMPLETE) }
            } catch (e: Exception) {
                update(id) { it.copy(state = if (token.get()) OperationState.CANCELLED else OperationState.FAILED, message = e.message ?: "Operation failed") }
            }
        }
        return id
    }
    @Synchronized fun cancel(id: String) { cancelled[id]?.set(true); futures[id]?.cancel(true) }
    @Synchronized private fun update(id: String, transform: (OperationSnapshot) -> OperationSnapshot) {
        records[id]?.let { records[id] = transform(it) }
    }
}

object AppOperations { val coordinator = OperationCoordinator() }
