package com.infinifolder.audio.engine

import android.media.MediaDataSource
import io.github.gretchycat.infinistream.ByteSource
import io.github.gretchycat.infinistream.PositionalReadable

/** Android output edge: the supplied source contains only library-decoded PCM WAVE. */
class PcmMediaDataSource(private val source: ByteSource) : MediaDataSource() {
    init { require(source is PositionalReadable && source.metadata.length != null && source.metadata.content.mimeType == "audio/wav") }
    override fun getSize(): Long = source.metadata.length!!
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int =
        (source as PositionalReadable).readAt(position, buffer, offset, size)
    override fun close() = source.close()
}
