package com.infinifolder.storage

import com.infinifolder.model.*
import java.io.InputStream
import java.io.OutputStream

/** UI operations dispatch by provider identity, never by interpreting SAF IDs as file paths. */
class RoutingStorageProvider(vararg providers: StorageProvider) : StorageProvider {
    override val id = "router"
    private val byId = providers.associateBy { it.id }
    private fun provider(ref: ResourceRef): StorageProvider? = byId[ref.providerId]

    override fun list(parent: ResourceRef): ProviderResult<List<ResourceEntry>> {
        val p = provider(parent) ?: return ProviderResult.Failure("Unknown provider: ${parent.providerId}")
        return try { p.list(parent) } catch (e: Exception) { ProviderResult.Failure(e.message ?: "Failed to list folder", e) }
    }
    override fun openRead(ref: ResourceRef): ProviderResult<InputStream> {
        val p = provider(ref) ?: return ProviderResult.Failure("Unknown provider: ${ref.providerId}")
        return try { p.openRead(ref) } catch (e: Exception) { ProviderResult.Failure(e.message ?: "Failed to read file", e) }
    }
    override fun openWrite(ref: ResourceRef): ProviderResult<OutputStream> {
        val p = provider(ref) ?: return ProviderResult.Failure("Unknown provider: ${ref.providerId}")
        return try { p.openWrite(ref) } catch (e: Exception) { ProviderResult.Failure(e.message ?: "Failed to write file", e) }
    }
    override fun createDirectory(parent: ResourceRef, name: String): ProviderResult<ResourceEntry> {
        val p = provider(parent) ?: return ProviderResult.Failure("Unknown provider: ${parent.providerId}")
        return try { p.createDirectory(parent, name) } catch (e: Exception) { ProviderResult.Failure(e.message ?: "Failed to create directory", e) }
    }
    override fun createFile(parent: ResourceRef, name: String, mimeType: String): ProviderResult<ResourceEntry> {
        val p = provider(parent) ?: return ProviderResult.Failure("Unknown provider: ${parent.providerId}")
        return try { p.createFile(parent, name, mimeType) } catch (e: Exception) { ProviderResult.Failure(e.message ?: "Failed to create file", e) }
    }
    override fun rename(ref: ResourceRef, name: String): ProviderResult<ResourceEntry> {
        val p = provider(ref) ?: return ProviderResult.Failure("Unknown provider: ${ref.providerId}")
        return try { p.rename(ref, name) } catch (e: Exception) { ProviderResult.Failure(e.message ?: "Failed to rename", e) }
    }
    override fun delete(ref: ResourceRef): ProviderResult<Unit> {
        val p = provider(ref) ?: return ProviderResult.Failure("Unknown provider: ${ref.providerId}")
        return try { p.delete(ref) } catch (e: Exception) { ProviderResult.Failure(e.message ?: "Failed to delete", e) }
    }
    override fun copy(source: ResourceRef, destination: ResourceRef): ProviderResult<ResourceRef> {
        if (source.providerId != destination.providerId) return ProviderResult.Unsupported(ResourceCapability.COPY)
        val p = provider(source) ?: return ProviderResult.Failure("Unknown provider: ${source.providerId}")
        return try { p.copy(source, destination) } catch (e: Exception) { ProviderResult.Failure(e.message ?: "Failed to copy", e) }
    }
    override fun move(source: ResourceRef, destination: ResourceRef): ProviderResult<ResourceRef> {
        if (source.providerId != destination.providerId) return ProviderResult.Unsupported(ResourceCapability.MOVE)
        val p = provider(source) ?: return ProviderResult.Failure("Unknown provider: ${source.providerId}")
        return try { p.move(source, destination) } catch (e: Exception) { ProviderResult.Failure(e.message ?: "Failed to move", e) }
    }
}
