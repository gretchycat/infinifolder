package com.infinifolder.model

enum class Support { SUPPORTED, UNSUPPORTED, UNKNOWN }
enum class CreationGuarantee { EXCLUSIVE, SERVER_UNIQUE_NAME, UNKNOWN, UNSUPPORTED }
data class BackendFeatures(
    val randomRead: Support = Support.UNKNOWN,
    val randomWrite: Support = Support.UNKNOWN,
    val atomicReplacement: Support = Support.UNSUPPORTED,
    val creation: CreationGuarantee = CreationGuarantee.UNKNOWN,
    val rename: Support = Support.UNKNOWN,
    val delete: Support = Support.UNKNOWN,
    val nativeLinks: Support = Support.UNKNOWN,
    val readOnly: Boolean = false
)
data class ResourceCapabilities(val operations: Map<ResourceCapability, Support>, val creation: CreationGuarantee) {
    operator fun get(operation: ResourceCapability) = operations[operation] ?: Support.UNKNOWN
    fun mayAttempt(operation: ResourceCapability) = get(operation) != Support.UNSUPPORTED
    companion object {
        fun from(metadata: ResourceMetadata, features: BackendFeatures): ResourceCapabilities {
            fun yes(value: Boolean) = if (value) Support.SUPPORTED else Support.UNSUPPORTED
            val writable = metadata.canWrite && !features.readOnly
            val base = mutableMapOf(
                ResourceCapability.STAT to Support.SUPPORTED,
                ResourceCapability.READ to yes(metadata.canRead && !metadata.isDirectory),
                ResourceCapability.LIST to yes(metadata.canRead && metadata.isDirectory),
                ResourceCapability.WRITE to yes(writable && !metadata.isDirectory),
                ResourceCapability.COPY to yes(metadata.canRead && !metadata.isSymbolicLink),
                ResourceCapability.MOVE to if (metadata.isSymbolicLink || features.readOnly) Support.UNSUPPORTED else features.delete,
                ResourceCapability.CREATE to if (!metadata.isDirectory || !writable || features.creation == CreationGuarantee.UNSUPPORTED) Support.UNSUPPORTED
                    else if (features.creation == CreationGuarantee.UNKNOWN) Support.UNKNOWN else Support.SUPPORTED,
                ResourceCapability.RENAME to if (features.readOnly) Support.UNSUPPORTED else features.rename,
                ResourceCapability.DELETE to if (features.readOnly) Support.UNSUPPORTED else features.delete,
                ResourceCapability.SEEK to if (metadata.isDirectory || !metadata.canRead) Support.UNSUPPORTED else features.randomRead,
                ResourceCapability.SEEK_WRITE to if (metadata.isDirectory || !writable) Support.UNSUPPORTED else features.randomWrite,
                ResourceCapability.ATOMIC_REPLACE to if (metadata.isDirectory || !writable) Support.UNSUPPORTED else features.atomicReplacement
            )
            base.putAll(metadata.operationHints)
            return ResourceCapabilities(base, features.creation)
        }
    }
}
