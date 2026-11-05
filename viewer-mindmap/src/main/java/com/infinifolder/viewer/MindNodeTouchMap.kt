package com.infinifolder.viewer

import android.graphics.Color
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream

internal data class MindNodeTouchMap(val title: String, val roots: List<MindNodeTouchNode>) {
    val nodeCount: Int get() = roots.sumOf { it.count() }
}

internal data class MindNodeTouchNode(
    val title: String,
    val x: Float,
    val y: Float,
    val color: Int,
    val strokeWidth: Float,
    val maxTitleWidth: Float,
    val children: List<MindNodeTouchNode>
) {
    fun count(): Int = 1 + children.sumOf { it.count() }
}

/** Safe reader for MindNode Touch's XML plist subset. It deliberately rejects binary plists. */
internal object MindNodeTouchParser {
    private const val MAX_FILE_BYTES = 8 * 1024 * 1024
    private const val MAX_NODES = 10_000
    private const val MAX_DEPTH = 64
    private var parsedNodes = 0

    @Synchronized fun parse(input: InputStream): MindNodeTouchMap {
        parsedNodes = 0
        val bytes = input.use { source ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = source.read(buffer)
                if (count < 0) break
                if (out.size() > MAX_FILE_BYTES - count) throw IllegalArgumentException("MindNode file is larger than 8 MiB")
                out.write(buffer, 0, count)
            }
            out.toByteArray()
        }
        val parser = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
            setInput(ByteArrayInputStream(bytes), null)
        }
        var event = nextTagEvent(parser)
        require(event == XmlPullParser.START_TAG && parser.name == "plist") { "Not an XML property list" }
        event = nextTagEvent(parser)
        val dictionary = if (event == XmlPullParser.START_TAG && parser.name == "dict") readDict(parser)
            else throw IllegalArgumentException("MindNode property list has no root dictionary")
        val roots = (dictionary["mainNodes"] as? List<*>)?.mapNotNull { (it as? Map<*, *>)?.let { node -> readNode(node, 0) } }
            ?: throw IllegalArgumentException("MindNode property list has no mainNodes")
        require(roots.isNotEmpty()) { "MindNode map contains no topics" }
        val title = dictionary["title"] as? String ?: "MindNode Map"
        return MindNodeTouchMap(title, roots)
    }

    private fun readNode(data: Map<*, *>, depth: Int): MindNodeTouchNode {
        require(depth <= MAX_DEPTH) { "MindNode nesting limit exceeded" }
        parsedNodes++
        require(parsedNodes <= MAX_NODES) { "MindNode has too many topics" }
        val location = parsePair(data["location"] as? String)
        val children = (data["subnodes"] as? List<*>)?.mapNotNull { (it as? Map<*, *>)?.let { node -> readNode(node, depth + 1) } }.orEmpty()
        return MindNodeTouchNode(
            title = (data["title"] as? String)?.trimEnd()?.ifBlank { "(Untitled)" } ?: "(Untitled)",
            x = location.first, y = location.second,
            color = parseColor(data["strokeColor"] as? String),
            strokeWidth = ((data["strokeWidth"] as? Number)?.toFloat() ?: 3f).coerceIn(1f, 16f),
            maxTitleWidth = ((data["constrainedMaxTitleWidth"] as? Number)?.toFloat() ?: 290f).coerceIn(40f, 1000f),
            children = children
        )
    }

    private fun readDict(parser: XmlPullParser): Map<String, Any?> {
        val output = linkedMapOf<String, Any?>()
        while (true) {
            val event = nextTagEvent(parser)
            if (event == XmlPullParser.END_TAG && parser.name == "dict") return output
            if (event != XmlPullParser.START_TAG || parser.name != "key") {
                throw IllegalArgumentException("Malformed MindNode property-list dictionary")
            }
            val key = parser.nextText()
            val valueEvent = nextTagEvent(parser)
            if (valueEvent != XmlPullParser.START_TAG) throw IllegalArgumentException("Missing value for MindNode key '$key'")
            output[key] = readValue(parser)
        }
    }

    private fun readValue(parser: XmlPullParser): Any? = when (parser.name) {
        "dict" -> readDict(parser)
        "array" -> readArray(parser)
        "string", "date", "data" -> parser.nextText()
        "integer" -> parser.nextText().trim().toLongOrNull()
        "real" -> parser.nextText().trim().toDoubleOrNull()
        "true" -> { parser.nextText(); true }
        "false" -> { parser.nextText(); false }
        else -> { parser.nextText(); null }
    }

    private fun readArray(parser: XmlPullParser): List<Any?> {
        val output = mutableListOf<Any?>()
        while (true) {
            val event = nextTagEvent(parser)
            if (event == XmlPullParser.END_TAG && parser.name == "array") return output
            if (event != XmlPullParser.START_TAG) throw IllegalArgumentException("Malformed MindNode property-list array")
            output += readValue(parser)
        }
    }

    /** Skip the Apple plist DOCTYPE and formatting nodes without resolving external entities. */
    private fun nextTagEvent(parser: XmlPullParser): Int {
        while (true) {
            val event = parser.next()
            if (event == XmlPullParser.START_TAG || event == XmlPullParser.END_TAG || event == XmlPullParser.END_DOCUMENT) return event
        }
    }

    private fun parsePair(value: String?): Pair<Float, Float> {
        val parts = value.orEmpty().removePrefix("{").removeSuffix("}").split(',')
        return (parts.getOrNull(0)?.trim()?.toFloatOrNull() ?: 0f) to
            (parts.getOrNull(1)?.trim()?.toFloatOrNull() ?: 0f)
    }

    private fun parseColor(value: String?): Int {
        val parts = value.orEmpty().removePrefix("{").removeSuffix("}").split(',').mapNotNull { it.trim().toFloatOrNull() }
        if (parts.size < 3) return 0xFF666666.toInt()
        val rgb = parts.take(3).map { (it.coerceIn(0f, 1f) * 255f).toInt() }
        val alpha = (parts.getOrNull(3) ?: 1f).coerceIn(0f, 1f)
        return Color.argb((alpha * 255f).toInt(), rgb[0], rgb[1], rgb[2])
    }

}
