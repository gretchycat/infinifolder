package com.infinifolder.preview

import com.infinifolder.mime.MimeRegistry
import com.infinifolder.storage.FileMagicDetector
import com.infinifolder.storage.FileMagicResult

/** Identity decisions do not decide how a file is drawn or whether a container can be browsed. */
data class IconIdentity(val mimeType: String, val associated: Boolean, val ambiguous: Boolean,
    val magicMime: String? = null)
class IconIdentityResolver(private val registry: IconRegistry) {
    fun resolve(name: String, supplied: String?, detect: () -> String?): IconIdentity {
        val candidates = registry.candidates(name)
        val stated = MimeRegistry.normalize(supplied)
        val candidate = candidates.singleOrNull()?.takeUnless(MimeRegistry::isUnspecified)
        val detected = try { detect()?.let(MimeRegistry::normalize) }
        catch (_: Exception) {
            // A denied/offline source cannot invalidate an intentional association.
            if (candidate != null) return IconIdentity(candidate, true, false)
            null
        }
        if (candidate != null) {
            val validated = FileMagicDetector.validatedExtensionMime(name, candidate,
                detected?.let { FileMagicResult(it, "") })
            return IconIdentity(validated, validated == candidate, false, detected)
        }

        // Generic or conflicting extension associations need a content identity;
        // no signature and no text evidence means generic binary.
        val packageMime = stated.takeIf { MimeRegistry.packageFormat(name, it) != null }
        val identity = if (detected == "application/zip" && packageMime != null) packageMime
            else detected ?: if (candidates.isEmpty()) stated else "application/octet-stream"
        return IconIdentity(identity, false, candidates.size > 1, detected)
    }
}
