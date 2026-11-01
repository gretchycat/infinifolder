package com.infinifolder.viewer

import org.junit.Assert.*
import org.junit.Test

class SyntaxRefreshSchedulerTest {
    private class Queue {
        val queued = mutableListOf<Pair<Runnable, Long>>()
        var posts = 0
        fun post(callback: Runnable, delay: Long) { posts++; queued.add(callback to delay) }
        fun remove(callback: Runnable) { queued.removeAll { it.first === callback } }
        fun runNext() { queued.removeAt(0).first.run() }
    }
    @Test fun repeatedLayoutCallbacksDoNotPostponeThePendingPass() {
        val queue = Queue()
        var applied = 0
        val scheduler = SyntaxRefreshScheduler(queue::post, queue::remove) { applied++ }
        repeat(1000) { scheduler.request() }
        assertEquals(1, queue.posts)
        assertEquals(160L, queue.queued.single().second)
        queue.runNext()
        assertEquals(1, applied)
    }
    @Test fun reparseRunsImmediatelyDespiteMoreLayoutCallbacks() {
        val queue = Queue()
        var applied = 0
        val scheduler = SyntaxRefreshScheduler(queue::post, queue::remove) { applied++ }
        scheduler.request()
        scheduler.request(immediate = true)
        repeat(1000) { scheduler.request() }
        assertEquals(1, queue.queued.size)
        assertEquals(0L, queue.queued.single().second)
        queue.runNext()
        assertEquals(1, applied)
    }
    @Test fun laterRequestsStillRunAndDisposalCancelsWork() {
        val queue = Queue()
        var applied = 0
        val scheduler = SyntaxRefreshScheduler(queue::post, queue::remove) { applied++ }
        scheduler.request(immediate = true)
        queue.runNext()
        scheduler.request(immediate = true)
        queue.runNext()
        assertEquals(2, applied)
        scheduler.request()
        scheduler.cancel()
        assertTrue(queue.queued.isEmpty())
    }
}
