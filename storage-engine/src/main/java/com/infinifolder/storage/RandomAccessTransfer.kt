package com.infinifolder.storage

import com.infinifolder.archive.BlockDevice
import com.infinifolder.model.ProviderResult
import com.infinifolder.model.ResourceCapability
import com.infinifolder.model.ResourceRef
import java.io.IOException

/** A provider that can receive random-offset content directly from a producer. */
interface RandomAccessTransferSource {
    fun pendingResumeTransfers(): List<PendingResumeTransfer> = emptyList()
    fun claimAutoResume(source: ResourceRef, destination: ResourceRef): Boolean = true
    fun findResumeDestination(source: ResourceRef, destinationParent: ResourceRef, requestedName: String): ResumeDestination? = null
    fun rememberResumeDestination(
        source: ResourceRef,
        destinationParent: ResourceRef,
        requestedName: String,
        destination: ResourceRef,
        destinationName: String
    ) = Unit
    fun forgetResumeDestination(source: ResourceRef, destinationParent: ResourceRef, requestedName: String) = Unit
    fun discardResumeState(source: ResourceRef, destination: ResourceRef) = Unit
    fun migrateResumeState(source: ResourceRef, oldDestination: ResourceRef, newDestination: ResourceRef) = Unit
    fun finishResume(source: ResourceRef, destination: ResourceRef) = Unit
    fun transferTo(
        source: ResourceRef,
        destination: ResourceRef,
        target: BlockDevice,
        isCancelled: () -> Boolean,
        onBytesWritten: (Long) -> Unit,
        onPieceVerification: (verifiedPieces: Int, totalPieces: Int) -> Unit = { _, _ -> }
    ): ProviderResult<Long>
}

data class PendingResumeTransfer(
    val source: ResourceRef,
    val destinationParent: ResourceRef,
    val requestedName: String,
    val destination: ResourceRef,
    val incompleteName: String,
    val finalName: String
)

/** Optional fast path; Unsupported means callers can fall back to streamed openRead/openWrite. */
interface RandomAccessTransferRouter {
    fun findResumeDestination(source: ResourceRef, destinationParent: ResourceRef, requestedName: String): ResumeDestination? = null
    fun rememberResumeDestination(
        source: ResourceRef,
        destinationParent: ResourceRef,
        requestedName: String,
        destination: ResourceRef,
        destinationName: String
    ) = Unit
    fun forgetResumeDestination(source: ResourceRef, destinationParent: ResourceRef, requestedName: String) = Unit
    fun discardResumeState(source: ResourceRef, destination: ResourceRef) = Unit
    fun migrateResumeState(source: ResourceRef, oldDestination: ResourceRef, newDestination: ResourceRef) = Unit
    fun finishResume(source: ResourceRef, destination: ResourceRef) = Unit
    fun transferRandomAccess(
        source: ResourceRef,
        destination: ResourceRef,
        isCancelled: () -> Boolean,
        onBytesWritten: (Long) -> Unit,
        onPieceVerification: (verifiedPieces: Int, totalPieces: Int) -> Unit = { _, _ -> }
    ): ProviderResult<Long>
}

data class ResumeDestination(val ref: ResourceRef, val name: String)
