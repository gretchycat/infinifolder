package com.infinifolder.archive

import android.net.Uri
import com.infinifolder.model.ProviderResult
import com.infinifolder.storage.StorageProvider
import java.io.IOException

/** Container access uses the same backend-owned resource hooks as ordinary file access. */
object SeekableSourceResolver {
    const val MAX_NESTING_DEPTH = 16

    fun resolve(provider: StorageProvider, sourceUri: Uri, writable: Boolean = false): BlockDevice? {
        val ref = provider.refForUri(sourceUri) ?: throw IOException("No provider owns $sourceUri")
        return when (val result = provider.openSeekable(ref, writable)) {
            is ProviderResult.Success -> result.value
            is ProviderResult.Unsupported -> {
                if (writable) throw IOException("This storage connection cannot safely save changes inside an image. Copy the image to writable local storage and edit that copy.")
                null
            }
            is ProviderResult.Failure -> throw IOException(result.message, result.cause)
        }
    }
}
