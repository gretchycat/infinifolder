package com.infinifolder.storage

/** Private mutation view. Only commit publishes; close without commit discards all staged changes. */
interface StorageWriteBatch : java.io.Closeable {
    val provider: StorageProvider
    fun commit()
}
