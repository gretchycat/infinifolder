package com.infinifolder

import android.os.ParcelFileDescriptor
import org.junit.Assert.*
import org.junit.Test

class StorageAccessModeTest {
    @Test fun readOnly() {
        val mode = StorageAccessMode.parse("r")
        assertTrue(mode.readable)
        assertFalse(mode.writable)
        assertEquals(ParcelFileDescriptor.MODE_READ_ONLY, mode.descriptorFlags)
    }

    @Test fun writeOnlyCannotRead() {
        for (value in listOf("w", "wt")) {
            val mode = StorageAccessMode.parse(value)
            assertFalse(mode.readable)
            assertTrue(mode.writable)
            assertEquals(ParcelFileDescriptor.MODE_WRITE_ONLY, mode.descriptorFlags)
        }
    }

    @Test fun readWrite() {
        for (value in listOf("rw", "rwt")) {
            val mode = StorageAccessMode.parse(value)
            assertTrue(mode.readable)
            assertTrue(mode.writable)
            assertEquals(ParcelFileDescriptor.MODE_READ_WRITE, mode.descriptorFlags)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun unsupportedModeIsExplicit() { StorageAccessMode.parse("wa") }
}
