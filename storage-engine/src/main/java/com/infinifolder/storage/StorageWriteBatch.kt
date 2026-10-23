package com.infinifolder.storage

/** Private destination view. Only commit publishes; close without commit discards all staged changes. */
interface StorageWriteBatch : java.io.Closeable {
    val provider: StorageProvider
    fun commit()
}
