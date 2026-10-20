package com.infinifolder.storage

import com.infinifolder.model.*

/** UI operations dispatch by provider identity, never by interpreting SAF IDs as file paths. */
class RoutingStorageProvider(vararg providers: StorageProvider) : StorageProvider {
    override val id = "router"
    private val byId = providers.associateBy { it.id }
    private fun provider(ref: ResourceRef) = requireNotNull(byId[ref.providerId]) { "Unknown provider" }
    override fun list(parent: ResourceRef) = provider(parent).list(parent)
    override fun openRead(ref: ResourceRef) = provider(ref).openRead(ref)
    override fun openWrite(ref: ResourceRef) = provider(ref).openWrite(ref)
    override fun createDirectory(parent: ResourceRef, name: String) = provider(parent).createDirectory(parent, name)
    override fun rename(ref: ResourceRef, name: String) = provider(ref).rename(ref, name)
    override fun delete(ref: ResourceRef) = provider(ref).delete(ref)
    override fun copy(source: ResourceRef, destination: ResourceRef): ProviderResult<ResourceRef> =
        if (source.providerId == destination.providerId) provider(source).copy(source, destination)
        else ProviderResult.Unsupported(ResourceCapability.COPY)
    override fun move(source: ResourceRef, destination: ResourceRef): ProviderResult<ResourceRef> =
        if (source.providerId == destination.providerId) provider(source).move(source, destination)
        else ProviderResult.Unsupported(ResourceCapability.MOVE)
}
