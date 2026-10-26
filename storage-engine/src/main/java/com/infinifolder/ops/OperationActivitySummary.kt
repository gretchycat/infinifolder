package com.infinifolder.ops

/** Aggregate current work without mixing byte counts and item counts as one unit. */
data class OperationActivitySummary(val actions: Int, val itemsProcessed: Long, val knownTotalItems: Long,
    val hasUnknownItems: Boolean, val bytesPerSecond: Long, val percent: Int?,
    val averageBytesPerSecond: Long = 0, val estimatedRemainingSeconds: Long? = null,
    val etaStatus: String = "estimating…") {
    fun formatEta(): String = estimatedRemainingSeconds?.let { "ETA ~${formatEtaDuration(it)}" } ?: "ETA $etaStatus"
    companion object {
        fun from(snapshots: List<OperationSnapshot>, now: Long = System.currentTimeMillis()): OperationActivitySummary {
            val active = snapshots.filter { !it.id.startsWith("playback_") &&
                (it.state == OperationState.RUNNING || it.state == OperationState.QUEUED) }
            val processed = active.sumOf {
                if (it.totalItems > 0) it.itemsProcessed.coerceIn(0, it.totalItems).toLong() else it.itemsProcessed.coerceAtLeast(0).toLong()
            }
            val total = active.sumOf { it.totalItems.coerceAtLeast(0).toLong() }
            val rate = active.filter { it.state == OperationState.RUNNING && now - it.lastUpdateMillis < 5000 }
                .sumOf { (if (it.canFinishLater) it.torrentStats?.downloadBytesPerSecond ?: 0L else it.bytesPerSecond)
                    .coerceAtLeast(0).toDouble() }.coerceAtMost(Long.MAX_VALUE.toDouble()).toLong()
            fun fraction(op: OperationSnapshot): Double? = when {
                op.totalBytes != null && op.totalBytes > 0 -> op.bytes.coerceAtLeast(0).toDouble() / op.totalBytes
                op.totalItems > 0 -> op.itemsProcessed.coerceAtLeast(0).toDouble() / op.totalItems
                else -> null
            }?.coerceIn(0.0, 1.0)
            val fractions = active.map(::fraction)
            val progress = when {
                active.isEmpty() || fractions.any { it == null } -> null
                active.all { it.totalBytes != null && it.totalBytes > 0 } ->
                    active.sumOf { it.bytes.coerceIn(0, it.totalBytes!!).toDouble() } / active.sumOf { it.totalBytes!!.toDouble() }
                active.all { (it.totalBytes ?: 0) <= 0 && it.totalItems > 0 } -> processed.toDouble() / total
                else -> fractions.filterNotNull().average()
            }
            return OperationActivitySummary(active.size, processed, total, active.any { it.totalItems <= 0 }, rate,
                progress?.let { (it * 100).toInt().coerceIn(0, 99) })
        }
    }
}
