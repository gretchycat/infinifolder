package com.infinifolder

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class IconWorkTest {
    @Test fun timedOutWorkReturnsFallbackAndInterruptsTheActualWorker() {
        val interrupted=CountDownLatch(1)
        val started=System.nanoTime()
        assertNull(IconWork.bounded(30) {
            try {Thread.sleep(10000);"late"}catch(error:InterruptedException){interrupted.countDown();throw error}
        })
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started)<1500)
        assertTrue(interrupted.await(1,TimeUnit.SECONDS))
    }
    @Test fun completedWorkReturnsItsRealResultAndExceptionsFallBack() {
        val file=java.io.File.createTempFile("icon-probe-", ".txt")
        try {file.writeText("actual file bytes");assertEquals("actual file bytes",IconWork.bounded {file.readText()})}
        finally {file.delete()}
        assertNull(IconWork.bounded<String> {throw java.io.IOException("inaccessible")})
    }
}
