package com.infinifolder.storage

import com.infinifolder.model.ProviderResult
import org.junit.Assert.*
import org.junit.Test

class DirectoryQueryResultTest {
    @Test fun absentCursorIsAnErrorRatherThanAnEmptyDirectory() {
        assertTrue(directoryQueryResult<String>(null) is ProviderResult.Failure)
    }
    @Test fun completedEmptyCursorRemainsAValidEmptyDirectory() {
        val result = directoryQueryResult(emptyList<String>()) as ProviderResult.Success
        assertTrue(result.value.isEmpty())
        assertFalse(result.isLoading)
        assertNull(result.notice)
    }
    @Test fun emptyLoadingCursorIsNotACompletedEmptyDirectory() {
        val result = directoryQueryResult(emptyList<String>(), loading = true) as ProviderResult.Success
        assertTrue(result.isLoading)
    }
    @Test fun providerErrorWithNoRowsIsShownAsAnError() {
        val result = directoryQueryResult(emptyList<String>(), error = "Connection unavailable") as ProviderResult.Failure
        assertTrue(result.message.contains("Connection unavailable"))
    }
    @Test fun providerErrorPreservesPartialRowsAndWarning() {
        val result = directoryQueryResult(listOf("cached.txt"), error = "Some files unavailable") as ProviderResult.Success
        assertEquals(listOf("cached.txt"), result.value)
        assertEquals("Some files unavailable", result.notice)
    }
    @Test fun loadingCanFinishWithRealFilesOnTheNextQuery() {
        val initial = directoryQueryResult(emptyList<String>(), loading = true) as ProviderResult.Success
        val completed = directoryQueryResult(listOf("notes.txt", "Documents")) as ProviderResult.Success
        assertTrue(initial.isLoading)
        assertFalse(completed.isLoading)
        assertEquals(2, completed.value.size)
    }
    @Test fun informationalMessageSurvivesAnEmptyResponse() {
        val result = directoryQueryResult(emptyList<String>(), info = "Sign in to see files") as ProviderResult.Success
        assertEquals("Sign in to see files", result.notice)
    }
}
