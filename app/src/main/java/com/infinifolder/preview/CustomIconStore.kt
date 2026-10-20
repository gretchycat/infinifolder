package com.infinifolder.preview

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.infinifolder.archive.ArchiveMimeTypes
import com.infinifolder.model.ResourceMetadata
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages custom icons for MIME types (applied across all files of that MIME type)
 * and helper utilities for encoding/decoding custom icons in `.lnk` shortcut files.
 */
class CustomIconStore(baseDir: File) {
    private val iconsDir = File(baseDir, "mime_icons").apply { mkdirs() }
    private val indexFile = File(iconsDir, "index.json")
    private val mimeToFileName = ConcurrentHashMap<String, String>()
    private val mimeBitmaps = ConcurrentHashMap<String, Bitmap>()

    init {
        loadIndex()
    }

    private fun loadIndex() {
        try {
            if (!indexFile.exists()) return
            val json = JSONObject(indexFile.readText(Charsets.UTF_8))
            val keys = json.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val fileName = json.optString(k, "")
                if (fileName.isNotEmpty() && File(iconsDir, fileName).isFile) {
                    mimeToFileName[k] = fileName
                }
            }
        } catch (_: Exception) {}
    }

    @Synchronized
    private fun saveIndex() {
        try {
            iconsDir.mkdirs()
            val json = JSONObject()
            for ((k, v) in mimeToFileName) {
                json.put(k, v)
            }
            indexFile.writeText(json.toString(2), Charsets.UTF_8)
        } catch (_: Exception) {}
    }

    @Synchronized
    fun setMimeIcon(mimeKey: String, extension: String, bytes: ByteArray, decodedBitmap: Bitmap? = null): File {
        iconsDir.mkdirs()
        val normalizedMime = normalizeMimeKey(mimeKey)
        val cleanExt = extension.lowercase().trimStart('.').ifEmpty { "png" }
        val safeStem = normalizedMime.replace(Regex("[^a-z0-9._-]"), "_")
        val fileName = "${safeStem}.${cleanExt}"
        val previousName = mimeToFileName[normalizedMime]
        if (previousName != null && previousName != fileName) {
            try { File(iconsDir, previousName).delete() } catch (_: Exception) {}
        }
        val outFile = File(iconsDir, fileName)
        outFile.writeBytes(bytes)
        mimeToFileName[normalizedMime] = fileName
        if (decodedBitmap != null) {
            mimeBitmaps[normalizedMime] = decodedBitmap
        } else {
            val bmp = try { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) } catch (_: Throwable) { null }
            if (bmp != null) {
                mimeBitmaps[normalizedMime] = bmp
            } else {
                mimeBitmaps.remove(normalizedMime)
            }
        }
        saveIndex()
        return outFile
    }

    fun getCachedMimeBitmap(mimeKey: String): Bitmap? =
        mimeBitmaps[normalizeMimeKey(mimeKey)]

    fun getMimeIconBitmap(mimeKey: String, fallbackDecoder: ((File) -> Bitmap?)? = null): Bitmap? {
        val normalizedMime = normalizeMimeKey(mimeKey)
        mimeBitmaps[normalizedMime]?.let { return it }
        val file = getMimeIconFile(normalizedMime) ?: return null
        val decoded = (try { BitmapFactory.decodeFile(file.absolutePath) } catch (_: Throwable) { null })
            ?: fallbackDecoder?.invoke(file)
        if (decoded != null) {
            mimeBitmaps[normalizedMime] = decoded
        }
        return decoded
    }

    fun getMimeIconFile(mimeKey: String): File? {
        val normalizedMime = normalizeMimeKey(mimeKey)
        val fileName = mimeToFileName[normalizedMime] ?: return null
        val file = File(iconsDir, fileName)
        return if (file.isFile) file else null
    }

    fun hasMimeIcon(mimeKey: String): Boolean {
        val normalizedMime = normalizeMimeKey(mimeKey)
        return mimeBitmaps.containsKey(normalizedMime) || getMimeIconFile(normalizedMime) != null
    }

    companion object {
        fun normalizeMimeKey(mimeType: String): String =
            mimeType.lowercase().substringBefore(';').trim().ifEmpty { "application/octet-stream" }

        /**
         * Resolves a canonical MIME key for a file's metadata so all files of the same
         * type share the same custom icon.
         */
        fun resolveMimeKey(metadata: ResourceMetadata): String =
            resolveMimeKey(metadata.name, metadata.mimeType)

        fun resolveMimeKey(name: String, mimeType: String?): String {
            val clean = mimeType?.lowercase()?.substringBefore(';')?.trim().orEmpty()
            if (clean.isNotEmpty() && clean != "application/octet-stream") {
                return clean
            }
            val guessed = ArchiveMimeTypes.guessMimeType(name, false).lowercase()
            if (guessed.isNotEmpty() && guessed != "application/octet-stream") {
                return guessed
            }
            val ext = name.substringAfterLast('.', "").lowercase()
            return if (ext.isNotEmpty()) "application/x-$ext" else "application/octet-stream"
        }

        fun encodeBitmapToPngBytes(bitmap: Bitmap): ByteArray? = try {
            val out = ByteArrayOutputStream()
            if (bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                val bytes = out.toByteArray()
                bytes.takeIf { it.isNotEmpty() }
            } else null
        } catch (_: Exception) {
            null
        }

        fun encodeBitmapToBase64Png(bitmap: Bitmap): String? =
            encodeBitmapToPngBytes(bitmap)?.let { Base64.getEncoder().encodeToString(it) }

        fun encodeBytesToBase64(bytes: ByteArray): String =
            Base64.getEncoder().encodeToString(bytes)

        fun decodeBase64ToBytes(base64: String?): ByteArray? {
            if (base64.isNullOrBlank()) return null
            return try {
                Base64.getDecoder().decode(base64.trim())
            } catch (_: Exception) {
                null
            }
        }

        fun decodeBase64Bitmap(base64: String?): Bitmap? {
            val bytes = decodeBase64ToBytes(base64) ?: return null
            return try {
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            } catch (_: Exception) {
                null
            }
        }
    }
}
