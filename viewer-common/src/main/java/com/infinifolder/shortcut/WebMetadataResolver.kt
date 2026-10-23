package com.infinifolder.shortcut

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.infinifolder.preview.CustomIconStore
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

data class WebMetadata(
    val normalizedUrl: String,
    val title: String?,
    val faviconBase64: String?,
    val faviconBitmap: Bitmap?
)

object WebMetadataResolver {
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36"
    private const val CONNECT_TIMEOUT_MS = 5000
    private const val READ_TIMEOUT_MS = 5000
    private const val MAX_HTML_BYTES = 96 * 1024
    private const val MAX_ICON_BYTES = 2 * 1024 * 1024

    private val SCHEME_REGEX = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:.*")
    private val DOMAIN_LIKE_REGEX = Regex("^[a-zA-Z0-9-]+(\\.[a-zA-Z0-9-]+)+(:\\d+)?(/.*)?$")
    private val TITLE_TAG_REGEX = Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val LINK_TAG_REGEX = Regex("<link\\b[^>]*>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val META_TAG_REGEX = Regex("<meta\\b[^>]*>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val ATTR_REGEX = Regex("([a-zA-Z_:][-a-zA-Z0-9_:.]*)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s\"'=<>`]+))")

    fun normalizeInputUri(rawInput: String): String {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) return ""
        if (trimmed.startsWith("/")) {
            return "file://$trimmed"
        }
        if (SCHEME_REGEX.matches(trimmed)) {
            val colonIdx = trimmed.indexOf(':')
            val scheme = trimmed.substring(0, colonIdx).lowercase()
            val rest = trimmed.substring(colonIdx)
            return "$scheme$rest"
        }
        if (trimmed.startsWith("www.", ignoreCase = true) || DOMAIN_LIKE_REGEX.matches(trimmed)) {
            return "https://$trimmed"
        }
        return trimmed
    }

    fun isWebUri(uriString: String?): Boolean {
        if (uriString.isNullOrBlank()) return false
        val normalized = normalizeInputUri(uriString)
        return normalized.startsWith("http://", ignoreCase = true) ||
            normalized.startsWith("https://", ignoreCase = true)
    }

    fun defaultNameForUri(uriString: String): String {
        val normalized = normalizeInputUri(uriString)
        return try {
            if (isWebUri(normalized)) {
                val url = URL(normalized)
                val host = url.host.removePrefix("www.")
                val path = url.path?.trim('/') ?: ""
                val raw = if (path.isNotEmpty()) {
                    val lastSeg = path.substringAfterLast('/')
                    "$host - $lastSeg"
                } else {
                    host
                }
                sanitizeFileName(raw, host)
            } else {
                val afterSlash = normalized.trimEnd('/').substringAfterLast('/')
                    .substringAfterLast(':')
                    .substringBefore('?')
                    .substringBefore('#')
                sanitizeFileName(afterSlash, "Link")
            }
        } catch (_: Exception) {
            sanitizeFileName(normalized.substringAfterLast('/'), "Link")
        }
    }

    fun sanitizeFileName(rawName: String?, fallback: String = "Link"): String {
        val base = (rawName ?: "")
            .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .trim('.')
            .trim()
        if (base.isEmpty()) {
            val cleanFallback = fallback
                .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
                .replace(Regex("\\s+"), " ")
                .trim()
                .trim('.')
                .ifEmpty { "Link" }
            return cleanFallback.take(100)
        }
        return base.take(100).trim()
    }

    fun extractHtmlTitle(html: String): String? {
        val titleMatch = TITLE_TAG_REGEX.find(html)
        if (titleMatch != null) {
            val decoded = decodeHtmlEntities(titleMatch.groupValues[1])
                .replace(Regex("\\s+"), " ")
                .trim()
            if (decoded.isNotEmpty()) return decoded
        }

        for (metaMatch in META_TAG_REGEX.findAll(html)) {
            val attrs = parseAttributes(metaMatch.value)
            val prop = (attrs["property"] ?: attrs["name"] ?: "").lowercase()
            if (prop == "og:title" || prop == "twitter:title" || prop == "application-name") {
                val content = attrs["content"]?.let(::decodeHtmlEntities)?.replace(Regex("\\s+"), " ")?.trim()
                if (!content.isNullOrEmpty()) return content
            }
        }
        return null
    }

    private data class IconCandidate(
        val url: String,
        val score: Int
    )

    fun extractFaviconUrls(html: String, baseUrlString: String): List<String> {
        val candidates = mutableListOf<IconCandidate>()
        for (linkMatch in LINK_TAG_REGEX.findAll(html)) {
            val attrs = parseAttributes(linkMatch.value)
            val rel = (attrs["rel"] ?: "").lowercase()
            val href = attrs["href"]?.trim() ?: continue
            if (href.isEmpty()) continue

            val relTokens = rel.split(Regex("\\s+")).filter { it.isNotEmpty() }
            val isIcon = relTokens.any {
                it == "icon" ||
                    it == "apple-touch-icon" ||
                    it == "apple-touch-icon-precomposed" ||
                    it == "fluid-icon"
            } || rel.contains("shortcut icon")
            if (!isIcon) continue

            val resolvedUrl = resolveUrl(baseUrlString, href) ?: continue
            val lowerHref = resolvedUrl.lowercase()
            if (lowerHref.endsWith(".svg") || attrs["type"]?.contains("svg", ignoreCase = true) == true) {
                // BitmapFactory does not decode SVG natively; keep only as low-priority fallback
                continue
            }

            var score = 10
            if (relTokens.contains("apple-touch-icon") || relTokens.contains("apple-touch-icon-precomposed")) {
                score += 40
            } else if (relTokens.contains("icon") && !rel.contains("mask-icon")) {
                score += 25
            }

            val sizes = attrs["sizes"]?.lowercase() ?: ""
            val maxDim = Regex("(\\d+)x(\\d+)").findAll(sizes)
                .mapNotNull { it.groupValues[1].toIntOrNull() }
                .maxOrNull()
            if (maxDim != null) {
                score += when {
                    maxDim in 64..256 -> 35
                    maxDim in 32..63 -> 25
                    maxDim > 256 -> 20
                    else -> 10
                }
            }

            if (lowerHref.endsWith(".png") || attrs["type"]?.contains("png", ignoreCase = true) == true) {
                score += 15
            } else if (lowerHref.endsWith(".webp")) {
                score += 10
            } else if (lowerHref.endsWith(".ico")) {
                score += 5
            }

            candidates += IconCandidate(resolvedUrl, score)
        }

        val sorted = candidates
            .sortedByDescending { it.score }
            .map { it.url }
            .toMutableList()

        try {
            val base = URL(baseUrlString)
            val origin = buildString {
                append(base.protocol)
                append("://")
                append(base.host)
                if (base.port != -1 && base.port != base.defaultPort) {
                    append(":").append(base.port)
                }
            }
            sorted += "$origin/favicon.ico"
            sorted += "$origin/apple-touch-icon.png"
            sorted += "$origin/favicon.png"
        } catch (_: Exception) {}

        return sorted.distinct()
    }

    fun resolveUrl(baseUrlString: String, href: String): String? {
        val trimmed = href.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.startsWith("data:image/", ignoreCase = true)) return trimmed
        return try {
            val base = URL(baseUrlString)
            when {
                trimmed.startsWith("//") -> "${base.protocol}:$trimmed"
                else -> URL(base, trimmed).toString()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun parseAttributes(tag: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        for (match in ATTR_REGEX.findAll(tag)) {
            val key = match.groupValues[1].lowercase()
            val value = match.groupValues[2].takeIf { it.isNotEmpty() }
                ?: match.groupValues[3].takeIf { it.isNotEmpty() }
                ?: match.groupValues[4]
            map[key] = decodeHtmlEntities(value)
        }
        return map
    }

    fun decodeHtmlEntities(input: String): String {
        if (!input.contains('&')) return input
        var result = input
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&#39;", "'")
            .replace("&#039;", "'")
            .replace("&nbsp;", " ")
            .replace("&ndash;", "–")
            .replace("&mdash;", "—")
            .replace("&bull;", "•")
            .replace("&middot;", "·")
            .replace("&copy;", "©")
            .replace("&reg;", "®")
            .replace("&trade;", "™")

        result = Regex("&#(\\d+);").replace(result) { m ->
            val code = m.groupValues[1].toIntOrNull()
            if (code != null && code in 1..0x10FFFF) String(Character.toChars(code)) else m.value
        }
        result = Regex("&#[xX]([0-9a-fA-F]+);").replace(result) { m ->
            val code = m.groupValues[1].toIntOrNull(16)
            if (code != null && code in 1..0x10FFFF) String(Character.toChars(code)) else m.value
        }
        return result
    }

    fun fetchWebMetadata(rawUrl: String, maxIconPx: Int = 128): WebMetadata {
        val normalized = normalizeInputUri(rawUrl)
        if (!isWebUri(normalized)) {
            return WebMetadata(normalized, null, null, null)
        }

        var finalPageUrl = normalized
        var html: String? = null
        var directImageBytes: ByteArray? = null

        try {
            val (resolvedUrl, contentType, bytes) = openUrlBytes(normalized, MAX_HTML_BYTES)
            finalPageUrl = resolvedUrl
            if (bytes != null && bytes.isNotEmpty()) {
                if (contentType?.startsWith("image/", ignoreCase = true) == true) {
                    directImageBytes = bytes
                } else {
                    html = bytes.toString(Charsets.UTF_8)
                }
            }
        } catch (_: Exception) {}

        if (directImageBytes != null) {
            val bmp = decodeAndScaleBitmap(directImageBytes, maxIconPx)
            val b64 = bmp?.let { CustomIconStore.encodeBitmapToBase64Png(it) }
            return WebMetadata(finalPageUrl, defaultNameForUri(finalPageUrl), b64, bmp)
        }

        val title = html?.let(::extractHtmlTitle)
        val iconUrls = extractFaviconUrls(html ?: "", finalPageUrl)

        var faviconBitmap: Bitmap? = null
        for (iconUrl in iconUrls.take(6)) {
            val bmp = fetchAndDecodeIcon(iconUrl, maxIconPx)
            if (bmp != null) {
                faviconBitmap = bmp
                break
            }
        }

        val faviconBase64 = faviconBitmap?.let { CustomIconStore.encodeBitmapToBase64Png(it) }
        return WebMetadata(
            normalizedUrl = finalPageUrl,
            title = title,
            faviconBase64 = faviconBase64,
            faviconBitmap = faviconBitmap
        )
    }

    private fun fetchAndDecodeIcon(iconUrl: String, maxIconPx: Int): Bitmap? {
        return try {
            if (iconUrl.startsWith("data:image/", ignoreCase = true)) {
                val base64Part = iconUrl.substringAfter("base64,", "")
                if (base64Part.isEmpty()) return null
                val bytes = Base64.decode(base64Part, Base64.DEFAULT)
                return decodeAndScaleBitmap(bytes, maxIconPx)
            }
            val (_, _, bytes) = openUrlBytes(iconUrl, MAX_ICON_BYTES)
            if (bytes == null || bytes.isEmpty()) return null
            decodeAndScaleBitmap(bytes, maxIconPx)
        } catch (_: Exception) {
            null
        }
    }

    private fun decodeAndScaleBitmap(bytes: ByteArray, maxIconPx: Int): Bitmap? {
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        if (decoded.width <= 0 || decoded.height <= 0) return null
        if (decoded.width <= maxIconPx && decoded.height <= maxIconPx) return decoded
        val scale = maxIconPx.toFloat() / maxOf(decoded.width, decoded.height).toFloat()
        val targetW = (decoded.width * scale).toInt().coerceAtLeast(1)
        val targetH = (decoded.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(decoded, targetW, targetH, true)
    }

    private fun openUrlBytes(urlString: String, maxBytes: Int): Triple<String, String?, ByteArray?> {
        var currentUrl = urlString
        var redirects = 0
        while (redirects < 5) {
            val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = true
                requestMethod = "GET"
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "text/html,application/xhtml+xml,image/*,*/*;q=0.8")
            }
            try {
                val code = conn.responseCode
                if (code in 300..399) {
                    val location = conn.getHeaderField("Location") ?: break
                    val nextUrl = resolveUrl(currentUrl, location) ?: break
                    currentUrl = nextUrl
                    redirects++
                    continue
                }
                if (code !in 200..299) {
                    return Triple(currentUrl, null, null)
                }
                val finalUrl = conn.url?.toString() ?: currentUrl
                val contentType = conn.contentType
                val bytes = conn.inputStream.use { readCappedBytes(it, maxBytes) }
                return Triple(finalUrl, contentType, bytes)
            } finally {
                try { conn.disconnect() } catch (_: Exception) {}
            }
        }
        return Triple(currentUrl, null, null)
    }

    private fun readCappedBytes(input: InputStream, maxBytes: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0
        while (total < maxBytes) {
            val toRead = minOf(buf.size, maxBytes - total)
            val read = input.read(buf, 0, toRead)
            if (read <= 0) break
            out.write(buf, 0, read)
            total += read
        }
        return out.toByteArray()
    }
}
