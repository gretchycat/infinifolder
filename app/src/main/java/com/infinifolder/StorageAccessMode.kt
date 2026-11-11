package com.infinifolder

import android.os.ParcelFileDescriptor

/** Access bits shared by the proxy descriptor and its callback guards. */
internal data class StorageAccessMode(val readable: Boolean, val writable: Boolean) {
    val descriptorFlags: Int get() = when {
        readable && writable -> ParcelFileDescriptor.MODE_READ_WRITE
        writable -> ParcelFileDescriptor.MODE_WRITE_ONLY
        else -> ParcelFileDescriptor.MODE_READ_ONLY
    }

    companion object {
        fun parse(mode: String): StorageAccessMode = when (mode) {
            "r" -> StorageAccessMode(readable = true, writable = false)
            "w", "wt" -> StorageAccessMode(readable = false, writable = true)
            "rw", "rwt" -> StorageAccessMode(readable = true, writable = true)
            else -> throw IllegalArgumentException("Unsupported descriptor mode: $mode")
        }
    }
}
