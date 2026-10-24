package com.infinifolder.ops

import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean

enum class OperationState { QUEUED, RUNNING, COMPLETE, FAILED, CANCELLED }

data class OperationUpdate(
    val bytes: Long = 0L,
    val totalBytes: Long? = null,
    val itemsProcessed: Int = 0,
    val totalItems: Int = 0,
    val currentItem: String? = null,
    val bytesPerSecond: Long = 0L,
    val message: String? = null,
    val indeterminate: Boolean? = null,
    val currentItemBytes: Long? = null,
    val currentItemTotalBytes: Long? = null,
    val warningCount: Int = 0
)

data class OperationSnapshot(
    val id: String,
    val label: String,
    val state: OperationState,
    val bytes: Long = 0L,
    val totalBytes: Long? = null,
    val itemsProcessed: Int = 0,
    val totalItems: Int = 0,
    val currentItem: String? = null,
    val bytesPerSecond: Long = 0L,
    val startTimeMillis: Long = System.currentTimeMillis(),
    val message: String? = null,
    val indeterminate: Boolean = false,
    val currentItemBytes: Long? = null,
    val currentItemTotalBytes: Long? = null,
    val warningCount: Int = 0
) {
    /** Overall totals stay visible during per-file preparation/verification phases. */
    val overallProgressPercent: Int
        get() = when {
            state == OperationState.COMPLETE -> 100
            totalBytes != null && totalBytes > 0 -> (bytes.toDouble() / totalBytes * 100).toInt().coerceIn(0, 99)
            totalItems > 0 -> (itemsProcessed.toDouble() / totalItems * 100).toInt().coerceIn(0, 99)
            else -> -1
        }
    val currentItemProgressPercent: Int
        get() {
            val done = currentItemBytes ?: return -1
            val total = currentItemTotalBytes ?: return -1
            return if (total > 0) (done.toDouble() / total * 100).toInt().coerceIn(0, 100)
                else if (total == 0L) 100 else -1
        }
    val progressPercent: Int
        get() = when {
            indeterminate -> -1
            totalBytes != null && totalBytes > 0 -> ((bytes * 100) / totalBytes).toInt().coerceIn(0, 100)
            totalItems > 0 -> ((itemsProcessed * 100) / totalItems).coerceIn(0, 100)
            else -> -1
        }

    fun formatRate(): String = when {
        bytesPerSecond >= 1024 * 1024 -> "%.1f MB/s".format(bytesPerSecond / (1024.0 * 1024.0))
        bytesPerSecond >= 1024 -> "%.1f KB/s".format(bytesPerSecond / 1024.0)
        bytesPerSecond > 0 -> "$bytesPerSecond B/s"
        else -> ""
    }
}

/** UI-independent bounded queue. Observed by OperationService and UI progress dialogs. */
class OperationCoordinator(private val concurrency: Int = 1) {
    private val executor = Executors.newFixedThreadPool(concurrency.coerceIn(1, 3))
    private val cancelled = mutableMapOf<String, AtomicBoolean>()
    private val records = LinkedHashMap<String, OperationSnapshot>()
    private val futures = mutableMapOf<String, Future<*>>()
    private val listeners = mutableListOf<(List<OperationSnapshot>) -> Unit>()

    @Synchronized fun snapshots(): List<OperationSnapshot> = records.values.toList()

    @Synchronized fun activeSnapshots(): List<OperationSnapshot> =
        records.values.filter { it.state == OperationState.RUNNING || it.state == OperationState.QUEUED }

    @Synchronized fun addListener(listener: (List<OperationSnapshot>) -> Unit) {
        listeners.add(listener)
        listener(snapshots())
    }

    @Synchronized fun removeListener(listener: (List<OperationSnapshot>) -> Unit) {
        listeners.remove(listener)
    }

    private fun notifyListeners() {
        val currentSnapshots = snapshots()
        val copy = synchronized(this) { listeners.toList() }
        copy.forEach { it(currentSnapshots) }
    }

    @Synchronized fun submit(
        label: String,
        totalBytes: Long? = null,
        totalItems: Int = 0,
        work: (update: (OperationUpdate) -> Unit, isCancelled: () -> Boolean) -> Unit
    ): String {
        val id = UUID.randomUUID().toString()
        val token = AtomicBoolean(false)
        cancelled[id] = token
        records[id] = OperationSnapshot(
            id = id,
            label = label,
            state = OperationState.QUEUED,
            bytes = 0L,
            totalBytes = totalBytes,
            totalItems = totalItems,
            startTimeMillis = System.currentTimeMillis()
        )
        notifyListeners()
        futures[id] = executor.submit {
            update(id) { it.copy(state = OperationState.RUNNING) }
            try {
                work({ u ->
                    update(id) {
                        it.copy(
                            bytes = u.bytes,
                            totalBytes = u.totalBytes ?: it.totalBytes,
                            itemsProcessed = u.itemsProcessed,
                            totalItems = if (u.totalItems > 0) u.totalItems else it.totalItems,
                            currentItem = u.currentItem ?: it.currentItem,
                            bytesPerSecond = u.bytesPerSecond,
                            message = u.message ?: it.message,
                            indeterminate = u.indeterminate ?: it.indeterminate,
                            currentItemBytes = u.currentItemBytes,
                            currentItemTotalBytes = u.currentItemTotalBytes,
                            warningCount = maxOf(it.warningCount, u.warningCount)
                        )
                    }
                }, { token.get() })
                update(id) { it.copy(state = if (token.get()) OperationState.CANCELLED else OperationState.COMPLETE, indeterminate = false) }
            } catch (e: Exception) {
                com.infinifolder.storage.DiagnosticLog.record("operation.failed", label, e)
                update(id) {
                    it.copy(
                        state = if (token.get()) OperationState.CANCELLED else OperationState.FAILED,
                        indeterminate = false,
                        message = e.message ?: "Operation failed"
                    )
                }
            }
        }
        return id
    }

    @Synchronized fun submit(
        label: String,
        totalBytes: Long? = null,
        work: (progress: (Long) -> Unit, isCancelled: () -> Boolean) -> Unit
    ): String = submit(label, totalBytes, 0) { update, isCancelled ->
        work({ bytes -> update(OperationUpdate(bytes = bytes)) }, isCancelled)
    }

    var onCancelExternal: ((String) -> Unit)? = null

    @Synchronized fun cancel(id: String) {
        cancelled[id]?.set(true)
        futures[id]?.cancel(true)
        onCancelExternal?.invoke(id)
        update(id) { it.copy(state = OperationState.CANCELLED) }
    }

    @Synchronized fun registerExternal(
        id: String,
        label: String,
        totalBytes: Long? = null,
        currentItem: String? = null
    ) {
        records[id] = OperationSnapshot(
            id = id,
            label = label,
            state = OperationState.RUNNING,
            bytes = 0L,
            totalBytes = totalBytes,
            currentItem = currentItem,
            startTimeMillis = System.currentTimeMillis()
        )
        notifyListeners()
    }

    @Synchronized fun updateExternal(
        id: String,
        bytes: Long,
        totalBytes: Long? = null,
        currentItem: String? = null,
        message: String? = null
    ) {
        records[id]?.let {
            records[id] = it.copy(
                bytes = bytes,
                totalBytes = totalBytes ?: it.totalBytes,
                currentItem = currentItem ?: it.currentItem,
                message = message ?: it.message
            )
            notifyListeners()
        }
    }

    @Synchronized fun completeExternal(id: String) {
        records[id]?.let {
            records[id] = it.copy(state = OperationState.COMPLETE)
            notifyListeners()
        }
    }

    private fun update(id: String, transform: (OperationSnapshot) -> OperationSnapshot) {
        synchronized(this) {
            val record = records[id] ?: return
            records[id] = transform(record)
        }
        // Notification/listener work can perform Binder calls. Never hold the
        // coordinator lock while UI callers are waiting to read snapshots.
        notifyListeners()
    }
}

object AppOperations { val coordinator = OperationCoordinator() }
