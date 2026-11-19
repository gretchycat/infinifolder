package com.infinifolder

import com.infinifolder.model.ResourceEntry
import com.infinifolder.ops.FileOperations
import com.infinifolder.ops.FolderStats
import com.infinifolder.storage.StorageProvider
import java.util.concurrent.CancellationException

/** Properties must survive a failed listing without presenting incomplete totals as complete. */
internal object FolderPropertiesStats {
    sealed interface Outcome {
        data class Complete(val stats: FolderStats) : Outcome
        data class Unavailable(val error: Exception) : Outcome
        data object Cancelled : Outcome
    }

    fun load(
        entries: List<ResourceEntry>,
        provider: StorageProvider,
        isCancelled: () -> Boolean = { false },
        onProgress: (FolderStats) -> Unit = {}
    ): Outcome {
        fun cancelled() = isCancelled() || Thread.currentThread().isInterrupted
        if (cancelled()) return Outcome.Cancelled
        return try {
            val stats = FileOperations.calculateFolderStats(entries, provider, ::cancelled) {
                if (!cancelled()) onProgress(it)
            }
            if (cancelled()) Outcome.Cancelled else Outcome.Complete(stats)
        } catch (error: Exception) {
            if (error is InterruptedException) Thread.currentThread().interrupt()
            if (cancelled() || error is CancellationException) Outcome.Cancelled
            else Outcome.Unavailable(error)
        }
    }
}
