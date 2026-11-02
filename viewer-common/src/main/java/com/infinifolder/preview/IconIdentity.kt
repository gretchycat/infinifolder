package com.infinifolder.preview

import com.infinifolder.mime.MimeRegistry

/** Identity decisions do not decide how a file is drawn or whether a container can be browsed. */
data class IconIdentity(val mimeType: String, val associated: Boolean, val ambiguous: Boolean,
    val magicMime: String? = null)
class IconIdentityResolver(private val registry: IconRegistry) {
    fun resolve(name: String, supplied: String?, detect: () -> String?): IconIdentity {
        val candidates = registry.candidates(name)
        // Generic binary associations carry no specific format identity. Let bounded
        // content detection recognize real images/containers without treating every BIN as a disc.
        if (candidates.size == 1 && candidates.single() != "application/octet-stream")
            return IconIdentity(candidates.single(), true, false)
        val detected = detect()?.let(MimeRegistry::normalize)?.takeUnless { it == "application/octet-stream" }
        val stated = MimeRegistry.normalize(supplied)
        val packaged=MimeRegistry.packageFormat("",stated)
        val identity=if(packaged!=null && detected=="application/zip")packaged.mime else detected ?: stated
        return IconIdentity(identity, false, candidates.size > 1, detected)
    }
}
