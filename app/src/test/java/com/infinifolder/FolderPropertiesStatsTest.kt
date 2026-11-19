package com.infinifolder

import android.net.TestUri
import com.infinifolder.model.*
import com.infinifolder.ops.FolderStats
import com.infinifolder.storage.StorageProvider
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class FolderPropertiesStatsTest {
    private fun entry(path:String,directory:Boolean=false,bytes:Long?=null)=ResourceEntry(
        ResourceRef("mock",path),ResourceMetadata(path.substringAfterLast('/'),if(directory)"vnd.android.document/directory" else "text/plain",bytes,0L,directory),TestUri("mock://$path"))
    private class Storage(val listing:(ResourceRef)->ProviderResult<List<ResourceEntry>>):StorageProvider {
        override val id="mock"
        var lists=0
        override fun list(parent:ResourceRef):ProviderResult<List<ResourceEntry>> {lists++;return listing(parent)}
        override fun openRead(ref:ResourceRef):Nothing=error("Unexpected read")
        override fun openWrite(ref:ResourceRef):Nothing=error("Unexpected write")
        override fun createDirectory(parent:ResourceRef,name:String):Nothing=error("Unexpected creation")
        override fun createFile(parent:ResourceRef,name:String,mimeType:String):Nothing=error("Unexpected creation")
        override fun rename(ref:ResourceRef,name:String):Nothing=error("Unexpected rename")
        override fun delete(ref:ResourceRef):Nothing=error("Unexpected deletion")
        override fun copy(source:ResourceRef,destination:ResourceRef):Nothing=error("Unexpected copy")
        override fun move(source:ResourceRef,destination:ResourceRef):Nothing=error("Unexpected move")
    }
    @Test fun deniedNestedDirectoryBecomesUnavailableInsteadOfEscapingTheWorker() {
        val root=entry("root",true);val android=entry("root/Android",true);val denied=entry("root/Android/data",true)
        val error=IOException("Android did not allow reading directory: data")
        val storage=Storage{ref->when(ref.documentId){
            "root"->ProviderResult.Success(listOf(entry("root/readable.txt",bytes=42),android))
            "root/Android"->ProviderResult.Success(listOf(denied))
            else->ProviderResult.Failure(error.message!!,error)
        }}
        val result=FolderPropertiesStats.load(listOf(root),storage)
        assertTrue(result is FolderPropertiesStats.Outcome.Unavailable)
        result as FolderPropertiesStats.Outcome.Unavailable
        assertEquals(error.message,result.error.message);assertSame(error,result.error.cause);assertEquals(3,storage.lists)
    }
    @Test fun readableDirectoriesAndMultipleSelectedFilesKeepExactTotals() {
        val root=entry("root",true);val sub=entry("root/sub",true)
        val storage=Storage{ref->ProviderResult.Success(if(ref.documentId=="root")listOf(entry("root/a",bytes=12),sub)else listOf(entry("root/sub/b",bytes=30)))}
        val result=FolderPropertiesStats.load(listOf(root,entry("outside",bytes=5)),storage)
        assertEquals(FolderPropertiesStats.Outcome.Complete(FolderStats(47,3,1)),result)
        assertEquals(2,storage.lists)
    }
    @Test fun pendingPartialAndUnsupportedListingsCannotProduceCompleteTotals() {
        val child=entry("root/a",bytes=20)
        for(listing in listOf<ProviderResult<List<ResourceEntry>>>(
            ProviderResult.Success(listOf(child),isLoading=true),
            ProviderResult.Success(listOf(child),notice="Partial listing: access denied"),
            ProviderResult.Unsupported(ResourceCapability.LIST)
        ))assertTrue(FolderPropertiesStats.load(listOf(entry("root",true)),Storage{listing}) is FolderPropertiesStats.Outcome.Unavailable)
    }
    @Test fun directSecurityAndNetworkExceptionsAreContainedForSingleOrMultipleSelections() {
        for(error in listOf(SecurityException("Permission denied"),IOException("Connection lost"))) {
            val result=FolderPropertiesStats.load(listOf(entry("readable",bytes=42),entry("remote",true)),Storage{throw error})
            assertTrue(result is FolderPropertiesStats.Outcome.Unavailable)
            assertSame(error,(result as FolderPropertiesStats.Outcome.Unavailable).error)
        }
    }
    @Test fun dismissBeforeOrDuringListingDoesNotPublishPartialTotalsOrProgress() {
        var cancelled=true;var progress=0
        val storage=Storage{cancelled=true;ProviderResult.Success(listOf(entry("root/a",bytes=42)))}
        assertEquals(FolderPropertiesStats.Outcome.Cancelled,FolderPropertiesStats.load(listOf(entry("root",true)),storage,{cancelled}){progress++})
        assertEquals(0,storage.lists)
        cancelled=false
        assertEquals(FolderPropertiesStats.Outcome.Cancelled,FolderPropertiesStats.load(listOf(entry("root",true)),storage,{cancelled}){progress++})
        assertEquals(1,storage.lists);assertEquals(0,progress)
    }
    @Test fun cancellationThatCoincidesWithProviderFailureIsStillCancellation() {
        var cancelled=false
        val storage=Storage{cancelled=true;ProviderResult.Failure("Permission denied")}
        assertEquals(FolderPropertiesStats.Outcome.Cancelled,FolderPropertiesStats.load(listOf(entry("root",true)),storage,{cancelled}))
    }
    @Test fun interruptedWorkerExitsWithoutAccessingStorage() {
        val storage=Storage{ProviderResult.Success(emptyList())}
        Thread.currentThread().interrupt()
        try {
            assertEquals(FolderPropertiesStats.Outcome.Cancelled,FolderPropertiesStats.load(listOf(entry("root",true)),storage))
            assertEquals(0,storage.lists)
        } finally {Thread.interrupted()}
    }
}
