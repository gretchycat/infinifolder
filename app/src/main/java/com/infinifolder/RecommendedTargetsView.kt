package com.infinifolder

import android.content.Context
import android.net.Uri
import android.widget.LinearLayout
import android.widget.TextView
import com.infinifolder.model.*
import com.infinifolder.storage.StorageProvider
import com.infinifolder.theme.ThemeManager
import java.util.concurrent.Executors

/** Read-only capability checks: never creates probe files or waits on the UI thread. */
class RecommendedTargetsView(context: Context, private val provider: StorageProvider,
    private val candidates: List<Candidate>, private val onChoose: (Uri) -> Unit) : LinearLayout(context) {
    data class Candidate(val uri: Uri, val label: String, val randomWriteConfirmedAt: Long = 0)
    private val checks = Executors.newFixedThreadPool(2) { task -> Thread(task, "destination-check").apply { isDaemon = true } }
    @Volatile private var closed = false
    private var started = false
    init { orientation = VERTICAL }
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (started) return
        started = true
        val theme = ThemeManager.activeTheme
        val density = resources.displayMetrics.density
        addView(TextView(context).apply {
            text = "Other locations"; textSize = 16f; setTextColor(theme.textPrimary)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, (12 * density).toInt(), 0, (4 * density).toInt())
        })
        if (candidates.isEmpty()) addView(TextView(context).apply {
            text = "Add folders in Settings → Common locations."
            setTextColor(theme.textSecondary)
        })
        candidates.forEach { candidate ->
            val row = TextView(context).apply {
                text = "${candidate.label}\nChecking write access…"; textSize = 14f
                setTextColor(theme.textSecondary); isEnabled = false
                setPadding(0, (10 * density).toInt(), 0, (10 * density).toInt())
            }
            addView(row)
            checks.execute {
                val result = try {
                    provider.refForUri(candidate.uri)?.let(provider::capabilities)
                        ?: ProviderResult.Failure("Storage source is unavailable")
                } catch (error: Exception) { ProviderResult.Failure(error.message ?: "Cannot check access", error) }
                post {
                    if (closed || !isAttachedToWindow) return@post
                    val capabilities = (result as? ProviderResult.Success)?.value
                    val eligible = capabilities != null && capabilities.mayAttempt(ResourceCapability.CREATE) &&
                        capabilities.mayAttempt(ResourceCapability.LIST) && capabilities.mayAttempt(ResourceCapability.TRANSFER_TARGET)
                    val status = when {
                        result is ProviderResult.Failure -> "Unavailable: ${result.message.take(140)}"
                        !eligible -> "Cannot provide a writable random-access target"
                        capabilities?.get(ResourceCapability.CREATE) != Support.SUPPORTED -> "Creation and random writes need a file-level check"
                        candidate.randomWriteConfirmedAt > 0 -> "Previously wrote torrent data here; file access will be rechecked"
                        capabilities?.get(ResourceCapability.TRANSFER_TARGET) == Support.SUPPORTED ->
                            "Reports random-write support; file access checked on use"
                        else -> "Random writes need a file-level check"
                    }
                    row.text = "${candidate.label}\n$status"
                    row.isEnabled = eligible
                    row.setTextColor(if (eligible) theme.textPrimary else theme.textSecondary)
                    row.alpha = if (eligible) 1f else .65f
                    if (eligible) row.setOnClickListener { onChoose(candidate.uri) }
                }
            }
        }
    }
    override fun onDetachedFromWindow() {
        closed = true
        checks.shutdownNow()
        super.onDetachedFromWindow()
    }
}
