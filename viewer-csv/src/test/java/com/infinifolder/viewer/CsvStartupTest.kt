package com.infinifolder.viewer

import android.widget.ListView
import android.widget.TextView
import org.junit.Test
import java.util.concurrent.ExecutorService

/** Android invokes onScroll during listener registration, before the first source read/layout. */
class CsvStartupTest {
    private fun withActivity(action: (CsvViewerActivity) -> Unit) {
        val activity = CsvViewerActivity()
        try { action(activity) } finally {
            for (name in listOf("documentWorker", "countWorker")) {
                val field = CsvViewerActivity::class.java.getDeclaredField(name).apply { isAccessible = true }
                (field.get(activity) as ExecutorService).shutdownNow()
            }
        }
    }
    private fun updateStats(activity: CsvViewerActivity) {
        CsvViewerActivity::class.java.getDeclaredMethod("updateRowStats").apply { isAccessible = true }.invoke(activity)
    }
    @Test fun synchronousScrollCallbackBeforeRowListAssignmentDoesNotCrash() = withActivity { activity ->
        // Reproduces the uninitialized field access from the handset crash report.
        updateStats(activity)
    }
    @Test fun statisticsWaitForAllViewsDuringPartialSetup() = withActivity { activity ->
        CsvViewerActivity::class.java.getDeclaredField("rowList").apply { isAccessible = true }.set(activity, ListView(activity))
        updateStats(activity)
        CsvViewerActivity::class.java.getDeclaredField("statsTextView").apply { isAccessible = true }.set(activity, TextView(activity))
        updateStats(activity)
    }
}
