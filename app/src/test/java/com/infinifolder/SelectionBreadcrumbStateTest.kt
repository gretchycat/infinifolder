package com.infinifolder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectionBreadcrumbStateTest {
    private val root = "container-links:///"
    private val start = "container-links:///"

    @Test
    fun persistedRouteRestoresEveryBreadcrumb() {
        val route = listOf(
            SelectionBreadcrumb(root, "Virtual root"),
            SelectionBreadcrumb("sftp://host/share", "Share"),
            SelectionBreadcrumb("archive+zip://sftp%3A%2F%2Fhost%2Fshare%2Fa.zip/", "a.zip"),
        )

        val encoded = SelectionBreadcrumbState.encode(start, route, root)

        assertEquals(route, SelectionBreadcrumbState.decode(encoded, start, root))
    }

    @Test
    fun routeIsNotRestoredForAnotherSelectionStart() {
        val encoded = SelectionBreadcrumbState.encode(
            start,
            listOf(SelectionBreadcrumb(root, "Virtual root")),
            root,
        )

        assertNull(SelectionBreadcrumbState.decode(encoded, "content://provider/new-start", root))
    }

    @Test
    fun routeWithoutVirtualRootAnchorIsRejected() {
        assertNull(SelectionBreadcrumbState.encode(
            start,
            listOf(SelectionBreadcrumb("content://provider/lost", "Lost folder")),
            root,
        ))
        assertNull(SelectionBreadcrumbState.decode(
            "{\"version\":1,\"startUri\":\"$start\",\"crumbs\":[{\"uri\":\"content://provider/lost\",\"name\":\"Lost\"}]}",
            start,
            root,
        ))
    }

    @Test
    fun onlyFailedNonRootSelectionLocationsRecoverToRoot() {
        assertTrue(SelectionLocationFallback.shouldReturnToVirtualRoot(true, "content://provider/gone", root))
        assertFalse(SelectionLocationFallback.shouldReturnToVirtualRoot(true, root, root))
        assertFalse(SelectionLocationFallback.shouldReturnToVirtualRoot(false, "content://provider/gone", root))
    }
}
