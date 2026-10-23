package com.infinifolder.storage

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.attribute.PosixFilePermission
import com.infinifolder.model.ResourceMetadata

class PermissionHelperTest {

    @Test
    fun testFormatPosixStandard() {
        val dirPerm = PermissionHelper.formatPosix(0b111_101_101, true)
        assertEquals("drwxr-xr-x (0755)", dirPerm)

        val filePerm = PermissionHelper.formatPosix(0b110_100_100, false)
        assertEquals("-rw-r--r-- (0644)", filePerm)

        val readOnlyFile = PermissionHelper.formatPosix(0b100_100_100, false)
        assertEquals("-r--r--r-- (0444)", readOnlyFile)

        val privateFile = PermissionHelper.formatPosix(0b110_000_000, false)
        assertEquals("-rw------- (0600)", privateFile)
    }

    @Test
    fun testFormatPosixSpecialBits() {
        // SUID
        val suid = PermissionHelper.formatPosix(0b100_111_101_101, false)
        assertEquals("-rwsr-xr-x (4755)", suid)

        // SGID
        val sgid = PermissionHelper.formatPosix(0b010_111_101_101, true)
        assertEquals("drwxr-sr-x (2755)", sgid)

        // Sticky bit
        val sticky = PermissionHelper.formatPosix(0b001_111_111_111, true)
        assertEquals("drwxrwxrwt (1777)", sticky)
    }

    @Test
    fun testPosixPermissionsToMode() {
        val perms = setOf(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.GROUP_READ,
            PosixFilePermission.OTHERS_READ
        )
        val mode = PermissionHelper.posixPermissionsToMode(perms)
        assertEquals(0b110_100_100, mode) // 0644
        assertEquals("-rw-r--r-- (0644)", PermissionHelper.formatPosix(mode, false))
    }

    @Test
    fun testGetFilePermissionsOnTempFile() {
        val tempFile = File.createTempFile("test_perm", ".txt")
        try {
            val perms = PermissionHelper.getFilePermissions(tempFile)
            assertTrue("Temporary file should be readable", perms.canRead)
            assertTrue("Temporary file should be writable", perms.canWrite)
            assertNotNull(perms.formatted)

            // Test read-only
            if (tempFile.setReadOnly()) {
                val roPerms = PermissionHelper.getFilePermissions(tempFile)
                assertTrue(roPerms.canRead)
                assertFalse("File marked read-only should have canWrite = false", roPerms.canWrite)
            }
        } finally {
            tempFile.setWritable(true)
            tempFile.delete()
        }
    }

    @Test
    fun testResourceMetadataDefaults() {
        val meta = ResourceMetadata(
            name = "test.txt",
            mimeType = "text/plain",
            sizeBytes = 123L,
            modifiedAtMillis = 1000L,
            isDirectory = false
        )
        assertTrue(meta.canRead)
        assertTrue(meta.canWrite)
        assertFalse(meta.canExecute)
        assertNull(meta.permissionsMode)
        assertNull(meta.permissionsFormatted)
        assertNull(meta.owner)
        assertNull(meta.group)

        val customMeta = meta.copy(
            canRead = true,
            canWrite = false,
            canExecute = false,
            permissionsMode = 0b100_100_100,
            permissionsFormatted = "-r--r--r-- (0444)",
            owner = "user1",
            group = "staff"
        )
        assertFalse(customMeta.canWrite)
        assertEquals("-r--r--r-- (0444)", customMeta.permissionsFormatted)
        assertEquals("user1", customMeta.owner)
        assertEquals("staff", customMeta.group)
    }

    @Test
    fun testEvaluatePosixPermissionsOwnerGroupOther() {
        val alice = SubjectIdentity(uid = 1000, username = "alice", gid = 1000, groupname = "alice")
        val bobInDev = SubjectIdentity(uid = 1001, username = "bob", gid = 2000, groupname = "bob", supplementaryGids = setOf(1000))
        val charlie = SubjectIdentity(uid = 1002, username = "charlie", gid = 3000, groupname = "charlie")
        val root = SubjectIdentity(uid = 0, username = "root")

        // Mode 0750: rwxr-x--- (Owner rwx, Group r-x, Other ---)
        val mode = 0b111_101_000
        val fileUid = 1000
        val fileGid = 1000

        // Alice is owner -> should have rwx
        val alicePerms = PermissionHelper.evaluatePosixPermissions(mode, fileUid, "alice", fileGid, "alice", alice)
        assertTrue("Alice should read", alicePerms.first)
        assertTrue("Alice should write", alicePerms.second)
        assertTrue("Alice should execute", alicePerms.third)

        // Bob is in group -> should have r-x (read and execute, but NOT write)
        val bobPerms = PermissionHelper.evaluatePosixPermissions(mode, fileUid, "alice", fileGid, "alice", bobInDev)
        assertTrue("Bob in group should read", bobPerms.first)
        assertFalse("Bob in group should not write", bobPerms.second)
        assertTrue("Bob in group should execute", bobPerms.third)

        // Charlie is other -> should have no permissions
        val charliePerms = PermissionHelper.evaluatePosixPermissions(mode, fileUid, "alice", fileGid, "alice", charlie)
        assertFalse("Charlie as other should not read", charliePerms.first)
        assertFalse("Charlie as other should not write", charliePerms.second)
        assertFalse("Charlie as other should not execute", charliePerms.third)

        // Root -> can read & write regardless of mode
        val rootPerms = PermissionHelper.evaluatePosixPermissions(mode, fileUid, "alice", fileGid, "alice", root)
        assertTrue("Root should read", rootPerms.first)
        assertTrue("Root should write", rootPerms.second)
        assertTrue("Root should execute when exec bit set", rootPerms.third)
    }
}
