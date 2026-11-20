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
        val route =
            listOf(
                SelectionBreadcrumb(root, "Virtual root"),
                SelectionBreadcrumb("sftp://host/share", "Share"),
                SelectionBreadcrumb("archive+zip://sftp%3A%2F%2Fhost%2Fshare%2Fa.zip/", "a.zip"),
            )

        val encoded = SelectionBreadcrumbState.encode(start, route, root)

        assertEquals(route, SelectionBreadcrumbState.decode(encoded, start, root))
    }

    @Test
    fun routeIsNotRestoredForAnotherSelectionStart() {
        val encoded =
            SelectionBreadcrumbState.encode(
                start,
                listOf(SelectionBreadcrumb(root, "Virtual root")),
                root,
            )

        assertNull(SelectionBreadcrumbState.decode(encoded, "content://provider/new-start", root))
    }

    @Test
    fun routeWithoutVirtualRootAnchorIsRejected() {
        assertNull(
            SelectionBreadcrumbState.encode(
                start,
                listOf(SelectionBreadcrumb("content://provider/lost", "Lost folder")),
                root,
            )
        )
        assertNull(
            SelectionBreadcrumbState.decode(
                "{\"version\":1,\"startUri\":\"$start\",\"crumbs\":[{\"uri\":\"content://provider/lost\",\"name\":\"Lost\"}]}",
                start,
                root,
            )
        )
    }

    @Test
    fun onlyFailedNonRootSelectionLocationsRecoverToRoot() {
        assertTrue(
            SelectionLocationFallback.shouldReturnToRoot(true, "content://provider/gone", root)
        )
        assertFalse(SelectionLocationFallback.shouldReturnToRoot(true, root, root))
        assertFalse(
            SelectionLocationFallback.shouldReturnToRoot(false, "content://provider/gone", root)
        )
    }

    @Test
    fun lockedCollectionStateUsesTheCollectionAsItsAnchor() {
        val collection = "content://provider/favorites"
        val route =
            listOf(
                SelectionBreadcrumb(collection, "Favorites"),
                SelectionBreadcrumb("content://provider/favorites/nes", "NES"),
            )
        val encoded = SelectionBreadcrumbState.encode(collection, route, collection)
        assertEquals(route, SelectionBreadcrumbState.decode(encoded, collection, collection))
        assertNull(SelectionBreadcrumbState.decode(encoded, collection, root))
        assertNull(
            SelectionBreadcrumbState.encode(
                collection,
                listOf(SelectionBreadcrumb(root, "Virtual root")),
                collection,
            )
        )
    }

    @Test
    fun failedLockedChildrenRecoverOnlyToTheirCollection() {
        val collection = "content://provider/recents"
        assertTrue(
            SelectionLocationFallback.shouldReturnToRoot(true, "$collection/missing", collection)
        )
        assertFalse(SelectionLocationFallback.shouldReturnToRoot(true, collection, collection))
    }
}
