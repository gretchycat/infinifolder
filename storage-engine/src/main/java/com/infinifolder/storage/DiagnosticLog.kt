package com.infinifolder.storage

import android.content.Context
import android.net.Uri
import java.io.File
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** Bounded app-private diagnostics with an optional user-granted document destination. */
object DiagnosticLog {
    private val lock = Any()
    private val muted = ThreadLocal.withInitial { false }
    private val worker: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private val publishLock = Any()
    private var pendingPublish: ScheduledFuture<*>? = null
    private var publishGeneration = 0L
    @Volatile private var app: Context? = null
    @Volatile var lastExportError: String? = null
        private set
    private fun file(context: Context) = File(context.filesDir, "diagnostics/session.log").apply { parentFile?.mkdirs() }
    fun init(context: Context) {
        if (app != null) return
        synchronized(lock) {
            if (app != null) return
            app = context.applicationContext
        }
        record("session", "started sdk=${android.os.Build.VERSION.SDK_INT} device=${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
    }
    fun sanitize(message: String): String = message
        .replace(Regex("(?i)([a-z][a-z0-9+.-]*://)[^/\\s@]+@"), "$1<redacted>@")
        .replace(Regex("(?i)(password|passwd|passphrase|token|secret|authorization)([\\s:=]+)([^\\s&,;]+)"), "$1$2<redacted>")
        .replace(Regex("(?i)Bearer\\s+[^\\s]+"), "Bearer <redacted>")
    fun record(event: String, detail: String = "", error: Throwable? = null) {
        val context = app ?: return
        if (muted.get() == true) return
        runCatching {
            val description = buildString {
                append(Instant.now()); append(" ["); append(Thread.currentThread().name); append("] ")
                append(event); append(' '); append(detail)
                if (error != null) { append(" "); append(error.javaClass.simpleName); append(": "); append(error.message)
                    error.stackTrace.take(12).forEach { append("\n    at "); append(it) } }
                append('\n')
            }
            synchronized(lock) {
                val log = file(context)
                if (log.length() > 1024 * 1024) log.writeText(log.readText().takeLast(512 * 1024))
                log.appendText(sanitize(description))
            }
            publishAsync()
        }
    }
    fun snapshot(context: Context): String = synchronized(lock) { runCatching { file(context).readText() }.getOrDefault("") }
    fun destination(context: Context): Uri? = context.getSharedPreferences("diagnostics", Context.MODE_PRIVATE)
        .getString("output_uri", null)?.let(Uri::parse)
    fun setDestination(context: Context, uri: Uri) {
        init(context)
        context.getSharedPreferences("diagnostics", Context.MODE_PRIVATE).edit().putString("output_uri", uri.toString()).apply()
        record("diagnostics", "output folder selected")
    }
    fun writeNow(context: Context): Result<Unit> {
        synchronized(publishLock) {
            publishGeneration++
            pendingPublish?.cancel(false)
            pendingPublish = null
        }
        return runCatching {
        val uri = destination(context) ?: error("Choose a log folder first")
        val bytes = snapshot(context).toByteArray(Charsets.UTF_8)
        muted.set(true)
        try {
            val provider = StorageRuntime.get(context).provider
            val ref = provider.refForUri(uri) ?: error("The selected log destination is unsupported")
            when (val result = provider.openWrite(ref)) {
                is com.infinifolder.model.ProviderResult.Success -> result.value.use { it.write(bytes); it.flush() }
                is com.infinifolder.model.ProviderResult.Failure -> error(result.message)
                is com.infinifolder.model.ProviderResult.Unsupported -> error("The selected log file is not writable")
            }
        } finally { muted.set(false) }
        lastExportError = null
    }.onFailure { lastExportError = sanitize(it.message ?: "Could not write diagnostic log") }
    }
    private fun publishAsync() {
        val context = app ?: return
        if (destination(context) == null) return
        synchronized(publishLock) {
            pendingPublish?.cancel(false)
            val generation = ++publishGeneration
            // Export once the current burst of diagnostic events has settled. Copying
            // the entire log to SAF after every event can dominate storage operations.
            pendingPublish = worker.schedule({
                val shouldPublish = synchronized(publishLock) {
                    if (publishGeneration != generation) false
                    else { pendingPublish = null; true }
                }
                if (!shouldPublish) return@schedule
                writeNow(context)
            }, 5, TimeUnit.SECONDS)
        }
    }
}
