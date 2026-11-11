package com.infinifolder

import com.infinifolder.archive.BlockDevice
import com.infinifolder.archive.requireDeviceRange
import java.io.IOException

/** Android proxy reads must fill the requested range unless they reach the demonstrated EOF. */
internal object StorageProxyReads {
    fun read(device: BlockDevice, offset: Long, size: Int, data: ByteArray): Int {
        requireDeviceRange(offset, data, 0, size)
        val available = (device.size - offset).coerceAtLeast(0)
        val requested = minOf(size.toLong(), available).toInt()
        var total = 0
        while (total < requested) {
            val count = device.readAt(offset + total, data, total, requested - total)
            if (count <= 0 || count > requested - total) throw IOException("Invalid storage read progress before end of file")
            total += count
        }
        return total
    }
}
