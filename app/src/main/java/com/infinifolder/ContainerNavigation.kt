package com.infinifolder

import com.infinifolder.archive.ArchiveFormats
import com.infinifolder.model.ResourceEntry
import com.infinifolder.storage.FileMagicDetector
import com.infinifolder.storage.FileMagicResult
import com.infinifolder.storage.ProviderStreams
import com.infinifolder.storage.StorageRuntime

/**
 * Automatic navigation requires content evidence and a successful read by Vault, off the UI thread.
 */
internal object ContainerNavigation {
    fun verify(runtime: StorageRuntime, entry: ResourceEntry): Boolean =
        verifyContent(
            ArchiveFormats.isDiscImage(entry.metadata.name, entry.metadata.mimeType),
            detect = {
                if (runtime.local.owns(entry.uri))
                    FileMagicDetector.detect(runtime.local.fileFor(entry.uri), true)
                else
                    FileMagicDetector.detectFromSource(
                        entry.metadata.name,
                        entry.metadata.mimeType,
                        ProviderStreams.resource(
                            runtime.provider,
                            entry.ref,
                            entry.metadata,
                            positional =
                                FileMagicDetector.requiresPositionalProbe(
                                    entry.metadata.name,
                                    entry.metadata.mimeType,
                                ),
                        ),
                    )
            },
            readContainer = { runtime.archives.getArchiveTree(entry.uri) },
        )

    internal fun verifyContent(
        imageCandidate: Boolean,
        detect: () -> FileMagicResult?,
        readContainer: () -> Unit,
    ): Boolean =
        try {
            val magic = detect()
            // Some raw filesystems have no distinctive prefix. Only their real parser
            // may confirm them; an extension or provider MIME is never confirmation.
            if (
                !imageCandidate &&
                    magic?.isArchiveOrDiscImage != true &&
                    !ArchiveFormats.isDefaultBrowsableArchive("", magic?.mimeType.orEmpty())
            )
                false
            else {
                readContainer()
                true
            }
        } catch (cancelled: java.io.InterruptedIOException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
}
