package com.infinifolder.overlay

import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeoutException

/** Preserve the server-side failure when a provider request crosses the UI executor. */
internal fun browserRequestFailure(error: Exception): IllegalStateException {
    var cause: Throwable = error
    while (cause is ExecutionException && cause.cause != null) cause = cause.cause!!
    if (cause is InterruptedException) Thread.currentThread().interrupt()
    val message =
        when (cause) {
            is TimeoutException -> "Folder browser did not respond within 10 seconds"
            is InterruptedException -> "Folder browser request interrupted"
            else ->
                cause.message?.takeIf { it.isNotBlank() }
                    ?: "Folder browser failed (${cause.javaClass.simpleName})"
        }
    return IllegalStateException(message, cause)
}
