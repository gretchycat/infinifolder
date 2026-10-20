package com.infinifolder.storage

import android.net.Uri
import com.infinifolder.model.*

interface StorageProvider {
    val id: String
    fun list(parent: ResourceRef): ProviderResult<List<ResourceEntry>>
    fun openRead(ref: ResourceRef): ProviderResult<java.io.InputStream>
    fun openWrite(ref: ResourceRef): ProviderResult<java.io.OutputStream>
    fun createDirectory(parent: ResourceRef, name: String): ProviderResult<ResourceEntry>
    fun createFile(parent: ResourceRef, name: String, mimeType: String = "application/octet-stream"): ProviderResult<ResourceEntry>
    fun rename(ref: ResourceRef, name: String): ProviderResult<ResourceEntry>
    fun delete(ref: ResourceRef): ProviderResult<Unit>
    fun copy(source: ResourceRef, destination: ResourceRef): ProviderResult<ResourceRef>
    fun move(source: ResourceRef, destination: ResourceRef): ProviderResult<ResourceRef>
}

/** SAF tree grants are persisted by Android; URI/document IDs remain opaque provider identities. */
data class SafRoot(val treeUri: Uri, val displayName: String)
