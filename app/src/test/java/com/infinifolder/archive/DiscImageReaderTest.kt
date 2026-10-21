package com.infinifolder.archive

import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

class DiscImageReaderTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testIso9660AndJolietAndBzip2() {
        val rawIso = buildSyntheticJolietIso(
            dirName = "Docs Folder",
            fileName = "Hello Joliet World.txt",
            content = "Hello from Joliet ISO 9660 disc image!".toByteArray(StandardCharsets.UTF_8)
        )
        val isoFile = tempFolder.newFile("test.iso")
        isoFile.writeBytes(rawIso)

        val reader = ArchiveReader(tempFolder.newFolder("temp_iso"))
        val tree = reader.buildTree(null, "test.iso", { isoFile.inputStream() }, isoFile)
        val rootItems = tree.list("")
        assertEquals(1, rootItems.size)
        assertEquals("Docs Folder", rootItems[0].name)
        assertTrue(rootItems[0].isDirectory)

        val childItems = tree.list("Docs Folder")
        assertEquals(1, childItems.size)
        assertEquals("Hello Joliet World.txt", childItems[0].name)

        val text = reader.openEntryStream("test.iso", "Docs Folder/Hello Joliet World.txt", { isoFile.inputStream() }, isoFile)
            .bufferedReader().readText()
        assertEquals("Hello from Joliet ISO 9660 disc image!", text)

        // Also test bzip2-compressed .iso.bz2
        val bz2File = tempFolder.newFile("test.iso.bz2")
        bz2File.outputStream().use { fos ->
            BZip2CompressorOutputStream(fos).use { bzos ->
                bzos.write(rawIso)
            }
        }
        val bz2Tree = reader.buildTree(null, "test.iso.bz2", { bz2File.inputStream() }, bz2File)
        assertEquals("Hello Joliet World.txt", bz2Tree.list("Docs Folder").single().name)
        val extracted = tempFolder.newFile("extracted_joliet.txt")
        reader.extractEntry("test.iso.bz2", "Docs Folder/Hello Joliet World.txt", extracted, { bz2File.inputStream() }, bz2File)
        assertEquals("Hello from Joliet ISO 9660 disc image!", extracted.readText())
    }

    @Test
    fun testFat16WithVfatLongFileNamesAndGzip() {
        val rawFat = buildSyntheticFat16WithVfat(
            longName = "LongFileName_VFAT_Document.txt",
            content = "VFAT Long File Name content inside FAT16!".toByteArray(StandardCharsets.UTF_8)
        )
        val gzFile = tempFolder.newFile("disk.vfat.gz")
        gzFile.outputStream().use { fos ->
            GzipCompressorOutputStream(fos).use { gzos ->
                gzos.write(rawFat)
            }
        }

        val reader = ArchiveReader(tempFolder.newFolder("temp_fat"))
        val tree = reader.buildTree(null, "disk.vfat.gz", { gzFile.inputStream() }, gzFile)
        val rootItems = tree.list("")
        assertEquals(1, rootItems.size)
        assertEquals("LongFileName_VFAT_Document.txt", rootItems[0].name)

        val text = reader.openEntryStream("disk.vfat.gz", "LongFileName_VFAT_Document.txt", { gzFile.inputStream() }, gzFile)
            .bufferedReader().readText()
        assertEquals("VFAT Long File Name content inside FAT16!", text)
    }

    @Test
    fun testExFatImage() {
        val rawExFat = buildSyntheticExFat(
            fileName = "exFAT_Sample_File.txt",
            content = "Payload stored inside exFAT cluster heap!".toByteArray(StandardCharsets.UTF_8)
        )
        val exFatFile = tempFolder.newFile("sdcard.exfat")
        exFatFile.writeBytes(rawExFat)

        val reader = ArchiveReader(tempFolder.newFolder("temp_exfat"))
        val tree = reader.buildTree(null, "sdcard.exfat", { exFatFile.inputStream() }, exFatFile)
        val rootItems = tree.list("")
        assertEquals(1, rootItems.size)
        assertEquals("exFAT_Sample_File.txt", rootItems[0].name)

        val text = reader.openEntryStream("sdcard.exfat", "exFAT_Sample_File.txt", { exFatFile.inputStream() }, exFatFile)
            .bufferedReader().readText()
        assertEquals("Payload stored inside exFAT cluster heap!", text)
    }

    @Test
    fun testExt4ExtentTreeAndMbrPartitionAndBzip2() {
        val rawExt4 = buildSyntheticExt4(
            fileName = "ext4_extent_hello.txt",
            content = "Hello from an ext4 extent-based inode!".toByteArray(StandardCharsets.UTF_8)
        )

        // Wrap the ext4 filesystem inside an MBR-partitioned disk image at LBA 8 (offset 4096)
        val mbrDisk = ByteArray(4096 + rawExt4.size)
        // Partition 1 entry at offset 446
        val partEntry = ByteBuffer.wrap(mbrDisk, 446, 16).slice().order(ByteOrder.LITTLE_ENDIAN)
        mbrDisk[446] = 0x80.toByte()
        mbrDisk[446 + 4] = 0x83.toByte() // Linux filesystem
        partEntry.putInt(8, 8) // startLba = 8 (4096 bytes)
        partEntry.putInt(12, rawExt4.size / 512)
        mbrDisk[510] = 0x55.toByte()
        mbrDisk[511] = 0xAA.toByte()
        System.arraycopy(rawExt4, 0, mbrDisk, 4096, rawExt4.size)

        val bz2Img = tempFolder.newFile("rootfs.ext4.bz2")
        bz2Img.outputStream().use { fos ->
            BZip2CompressorOutputStream(fos).use { bzos ->
                bzos.write(mbrDisk)
            }
        }

        val reader = ArchiveReader(tempFolder.newFolder("temp_ext4"))
        val tree = reader.buildTree(null, "rootfs.ext4.bz2", { bz2Img.inputStream() }, bz2Img)
        val rootItems = tree.list("")
        assertEquals(1, rootItems.size)
        assertEquals("ext4_extent_hello.txt", rootItems[0].name)

        val text = reader.openEntryStream("rootfs.ext4.bz2", "ext4_extent_hello.txt", { bz2Img.inputStream() }, bz2Img)
            .bufferedReader().readText()
        assertEquals("Hello from an ext4 extent-based inode!", text)
    }

    // =========================================================================
    // Synthetic Filesystem Image Builders for Unit Tests
    // =========================================================================

    private fun buildSyntheticJolietIso(dirName: String, fileName: String, content: ByteArray): ByteArray {
        // Sectors:
        // 0..15: System area
        // 16: Primary Volume Descriptor
        // 17: Joliet Supplementary Volume Descriptor
        // 18: Terminator
        // 19: Root Directory (Joliet)
        // 20: Subdirectory (Joliet)
        // 21: File data
        val img = ByteArray(24 * 2048)

        fun writeIsoRecord(target: ByteArray, pos: Int, lba: Int, dataLen: Int, isDir: Boolean, nameBytes: ByteArray): Int {
            val recLen = (33 + nameBytes.size + (if (nameBytes.size % 2 == 0) 1 else 0))
            target[pos] = recLen.toByte()
            val le = ByteBuffer.wrap(target, pos, recLen).slice().order(ByteOrder.LITTLE_ENDIAN)
            le.putInt(2, lba)
            le.putInt(10, dataLen)
            target[pos + 18] = 126.toByte() // 2026
            target[pos + 19] = 10.toByte()
            target[pos + 20] = 20.toByte()
            target[pos + 25] = (if (isDir) 0x02 else 0x00).toByte()
            target[pos + 32] = nameBytes.size.toByte()
            System.arraycopy(nameBytes, 0, target, pos + 33, nameBytes.size)
            return recLen
        }

        // Sector 16: PVD
        val pvdOff = 16 * 2048
        img[pvdOff] = 1
        System.arraycopy("CD001".toByteArray(StandardCharsets.US_ASCII), 0, img, pvdOff + 1, 5)
        img[pvdOff + 6] = 1
        writeIsoRecord(img, pvdOff + 156, 19, 2048, true, byteArrayOf(0))

        // Sector 17: Joliet SVD
        val svdOff = 17 * 2048
        img[svdOff] = 2
        System.arraycopy("CD001".toByteArray(StandardCharsets.US_ASCII), 0, img, svdOff + 1, 5)
        img[svdOff + 6] = 1
        // Joliet UCS-2 Level 3 escape sequence "%/E" at bytes 88..90
        img[svdOff + 88] = 0x25
        img[svdOff + 89] = 0x2F
        img[svdOff + 90] = 0x45
        writeIsoRecord(img, svdOff + 156, 19, 2048, true, byteArrayOf(0))

        // Sector 18: Terminator
        val termOff = 18 * 2048
        img[termOff] = 255.toByte()
        System.arraycopy("CD001".toByteArray(StandardCharsets.US_ASCII), 0, img, termOff + 1, 5)

        // Sector 19: Root directory
        var pos = 19 * 2048
        pos += writeIsoRecord(img, pos, 19, 2048, true, byteArrayOf(0))
        pos += writeIsoRecord(img, pos, 19, 2048, true, byteArrayOf(1))
        writeIsoRecord(img, pos, 20, 2048, true, dirName.toByteArray(StandardCharsets.UTF_16BE))

        // Sector 20: Subdirectory
        var subPos = 20 * 2048
        subPos += writeIsoRecord(img, subPos, 20, 2048, true, byteArrayOf(0))
        subPos += writeIsoRecord(img, subPos, 19, 2048, true, byteArrayOf(1))
        writeIsoRecord(img, subPos, 21, content.size, false, "$fileName;1".toByteArray(StandardCharsets.UTF_16BE))

        // Sector 21: File content
        System.arraycopy(content, 0, img, 21 * 2048, content.size)
        return img
    }

    private fun buildSyntheticFat16WithVfat(longName: String, content: ByteArray): ByteArray {
        val bytesPerSector = 512
        val reservedSectors = 1
        val numFats = 1
        val sectorsPerFat = 32
        val rootEntryCount = 64 // 4 sectors
        val totalSectors = 5000 // > 4085 clusters -> FAT16
        val img = ByteArray(totalSectors * bytesPerSector)
        val buf = ByteBuffer.wrap(img).order(ByteOrder.LITTLE_ENDIAN)

        img[0] = 0xEB.toByte()
        img[1] = 0x3C.toByte()
        img[2] = 0x90.toByte()
        buf.putShort(11, bytesPerSector.toShort())
        img[13] = 1 // 1 sector per cluster
        buf.putShort(14, reservedSectors.toShort())
        img[16] = numFats.toByte()
        buf.putShort(17, rootEntryCount.toShort())
        buf.putShort(19, totalSectors.toShort())
        buf.putShort(22, sectorsPerFat.toShort())
        System.arraycopy("FAT16   ".toByteArray(StandardCharsets.US_ASCII), 0, img, 54, 8)
        img[510] = 0x55.toByte()
        img[511] = 0xAA.toByte()

        // FAT table at sector 1: cluster 2 -> EOF (0xFFFF)
        val fatOff = reservedSectors * bytesPerSector
        buf.putShort(fatOff + 2 * 2, 0xFFFF.toShort())

        // Root directory at sector 33
        val rootOff = (reservedSectors + numFats * sectorsPerFat) * bytesPerSector
        val chunks = longName.chunked(13)
        var entryPos = rootOff
        for (i in chunks.indices.reversed()) {
            val seq = (i + 1) or (if (i == chunks.lastIndex) 0x40 else 0x00)
            img[entryPos] = seq.toByte()
            img[entryPos + 11] = 0x0F.toByte()
            val part = chunks[i]
            val offsets = intArrayOf(1, 3, 5, 7, 9, 14, 16, 18, 20, 22, 24, 28, 30)
            for (cIdx in 0 until 13) {
                val code = when {
                    cIdx < part.length -> part[cIdx].code
                    cIdx == part.length -> 0x0000
                    else -> 0xFFFF
                }
                buf.putShort(entryPos + offsets[cIdx], code.toShort())
            }
            entryPos += 32
        }

        // Short entry
        System.arraycopy("LONGFI~1TXT".toByteArray(StandardCharsets.US_ASCII), 0, img, entryPos, 11)
        img[entryPos + 11] = 0x20.toByte()
        buf.putShort(entryPos + 26, 2) // firstCluster = 2
        buf.putInt(entryPos + 28, content.size)

        // Data area starts at sector 37 (1 + 32 + 4)
        val dataOff = (reservedSectors + numFats * sectorsPerFat + 4) * bytesPerSector
        System.arraycopy(content, 0, img, dataOff, content.size)
        return img
    }

    private fun buildSyntheticExFat(fileName: String, content: ByteArray): ByteArray {
        val img = ByteArray(16 * 512)
        val buf = ByteBuffer.wrap(img).order(ByteOrder.LITTLE_ENDIAN)
        System.arraycopy("EXFAT   ".toByteArray(StandardCharsets.US_ASCII), 0, img, 3, 8)
        buf.putInt(80, 1) // fatOffsetSectors = 1
        buf.putInt(84, 1) // fatLengthSectors = 1
        buf.putInt(88, 2) // clusterHeapOffsetSectors = 2
        buf.putInt(92, 10) // clusterCount = 10
        buf.putInt(96, 2) // rootCluster = 2
        img[108] = 9 // 512 bytes per sector
        img[109] = 0 // 1 sector per cluster

        // FAT at sector 1: cluster 2 -> EOF, cluster 3 -> EOF
        buf.putInt(512 + 2 * 4, 0xFFFFFFFF.toInt())
        buf.putInt(512 + 3 * 4, 0xFFFFFFFF.toInt())

        // Root directory at cluster 2 (sector 2, offset 1024)
        val nameChunks = fileName.chunked(15)
        val secondaryCount = 1 + nameChunks.size
        var pos = 1024
        // 0x85 File Directory Entry
        img[pos] = 0x85.toByte()
        img[pos + 1] = secondaryCount.toByte()
        buf.putShort(pos + 4, 0x0020.toShort()) // Archive attribute
        pos += 32

        // 0xC0 Stream Extension Entry
        img[pos] = 0xC0.toByte()
        img[pos + 1] = 0x03.toByte() // AllocationPossible | NoFatChain
        img[pos + 3] = fileName.length.toByte()
        buf.putLong(pos + 8, content.size.toLong()) // ValidDataLength
        buf.putInt(pos + 20, 3) // FirstCluster = 3
        buf.putLong(pos + 24, content.size.toLong()) // DataLength
        pos += 32

        // 0xC1 File Name Entries
        for (chunk in nameChunks) {
            img[pos] = 0xC1.toByte()
            val utf16 = chunk.toByteArray(StandardCharsets.UTF_16LE)
            System.arraycopy(utf16, 0, img, pos + 2, utf16.size)
            pos += 32
        }

        // File content at cluster 3 (sector 3, offset 1536)
        System.arraycopy(content, 0, img, 3 * 512, content.size)
        return img
    }

    private fun buildSyntheticExt4(fileName: String, content: ByteArray): ByteArray {
        val blockSize = 1024
        val img = ByteArray(16 * blockSize)
        val buf = ByteBuffer.wrap(img).order(ByteOrder.LITTLE_ENDIAN)

        // Superblock at offset 1024 (block 1)
        val sbOff = 1024
        buf.putInt(sbOff + 0, 32) // s_inodes_count
        buf.putInt(sbOff + 4, 16) // s_blocks_count_lo
        buf.putInt(sbOff + 20, 1) // s_first_data_block
        buf.putInt(sbOff + 24, 0) // s_log_block_size = 0 (1024 bytes)
        buf.putInt(sbOff + 32, 1024) // s_blocks_per_group
        buf.putInt(sbOff + 40, 32) // s_inodes_per_group
        buf.putShort(sbOff + 56, 0xEF53.toShort()) // s_magic
        buf.putInt(sbOff + 76, 1) // s_rev_level = 1
        buf.putShort(sbOff + 88, 128.toShort()) // s_inode_size = 128

        // Block Group Descriptor Table at block 2 (offset 2048)
        val gdtOff = 2048
        buf.putInt(gdtOff + 8, 3) // bg_inode_table_lo = block 3 (offset 3072)

        // Inode Table at block 3 (offset 3072)
        // Inode 2 (root directory) at offset 3072 + (2 - 1) * 128 = 3200
        val rootInodeOff = 3072 + 128
        buf.putShort(rootInodeOff + 0, 0x41ED.toShort()) // directory mode
        buf.putInt(rootInodeOff + 4, blockSize) // i_size_lo = 1024
        buf.putInt(rootInodeOff + 32, 0) // direct blocks (non-extent) for root dir
        buf.putInt(rootInodeOff + 40, 6) // direct block[0] = block 6

        // Inode 3 (file with ext4 extent tree!) at offset 3072 + (3 - 1) * 128 = 3328
        val fileInodeOff = 3072 + 2 * 128
        buf.putShort(fileInodeOff + 0, 0x81A4.toShort()) // regular file mode
        buf.putInt(fileInodeOff + 4, content.size) // i_size_lo
        buf.putInt(fileInodeOff + 32, 0x00080000) // EXT4_EXTENTS_FL
        // Extent header at fileInodeOff + 40
        buf.putShort(fileInodeOff + 40, 0xF30A.toShort()) // eh_magic
        buf.putShort(fileInodeOff + 42, 1.toShort()) // eh_entries = 1
        buf.putShort(fileInodeOff + 44, 4.toShort()) // eh_max = 4
        buf.putShort(fileInodeOff + 46, 0.toShort()) // eh_depth = 0 (leaf)
        // Leaf extent at fileInodeOff + 52
        buf.putInt(fileInodeOff + 52, 0) // ee_block = 0
        buf.putShort(fileInodeOff + 56, 1.toShort()) // ee_len = 1 block
        buf.putShort(fileInodeOff + 58, 0.toShort()) // ee_start_hi = 0
        buf.putInt(fileInodeOff + 60, 7) // ee_start_lo = block 7

        // Block 6 (offset 6144): Root directory entries
        val dirOff = 6 * blockSize
        val nameBytes = fileName.toByteArray(StandardCharsets.UTF_8)
        buf.putInt(dirOff + 0, 3) // inode 3
        buf.putShort(dirOff + 4, blockSize.toShort()) // rec_len = 1024
        img[dirOff + 6] = nameBytes.size.toByte()
        img[dirOff + 7] = 1 // regular file
        System.arraycopy(nameBytes, 0, img, dirOff + 8, nameBytes.size)

        // Block 7 (offset 7168): File content
        System.arraycopy(content, 0, img, 7 * blockSize, content.size)
        return img
    }
}
