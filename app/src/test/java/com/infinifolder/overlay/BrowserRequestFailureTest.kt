package com.infinifolder.overlay

import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeoutException
import org.junit.Assert.*
import org.junit.Test

class BrowserRequestFailureTest {
    @Test
    fun uiTaskFailureKeepsTheSetupErrorInsteadOfTheExecutorWrapper() {
        val original = IllegalArgumentException("Folder browser requires an attached host surface")
        val task = FutureTask<Unit> { throw original }
        task.run()
        val error =
            try {
                task.get()
                error("Expected setup failure")
            } catch (error: ExecutionException) {
                error
            }
        val failure = browserRequestFailure(error)
        assertEquals(original.message, failure.message)
        assertSame(original, failure.cause)
    }

    @Test
    fun timeoutIsDistinguishableFromImmediateFailure() {
        val error = TimeoutException()
        val failure = browserRequestFailure(error)
        assertEquals("Folder browser did not respond within 10 seconds", failure.message)
        assertSame(error, failure.cause)
    }

    @Test
    fun missingMessageStillIdentifiesExceptionType() {
        assertEquals(
            "Folder browser failed (NullPointerException)",
            browserRequestFailure(ExecutionException(NullPointerException())).message,
        )
    }

    @Test
    fun interruptedWaitRestoresInterruptStatus() {
        try {
            val error = InterruptedException()
            assertSame(error, browserRequestFailure(error).cause)
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
    }
}
