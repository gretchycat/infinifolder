package com.infinifolder.theme

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.util.LruCache
import com.caverock.androidsvg.PreserveAspectRatio
import com.caverock.androidsvg.RenderOptions
import com.caverock.androidsvg.SVG
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class FreeDesktopIconTheme(
    val info: IconThemeInfo,
    private val fallbackResolver: ((String) -> FreeDesktopIconTheme?)? = null
) {
    // Memory cache for decoded bitmaps (max ~300 bitmaps)
    private val bitmapCache = object : LruCache<String, Bitmap>(300) {}

    // File path lookup cache: key is "$iconName:$targetPx"
    private val pathCache = ConcurrentHashMap<String, File>()
    private val missingPaths = ConcurrentHashMap.newKeySet<String>()

    fun findIconFile(iconName: String, targetSizePx: Int): File? {
        return findIconFile(iconName, targetSizePx, mutableSetOf())
    }

    private fun findIconFile(iconName: String, targetSizePx: Int, visited: MutableSet<String>): File? {
        val topLevel = visited.isEmpty()
        if (!visited.add(info.rootDir.absolutePath)) return null
        val cacheKey = "$iconName:$targetSizePx"
        pathCache[cacheKey]?.let { return it }
        if (topLevel && cacheKey in missingPaths) return null

        // 1. Search within this theme's directories sorted by best matching size
        val sortedDirs = info.directories.sortedWith(
            compareBy<ThemeDirectory> { it.sizeDistance(targetSizePx) }
                .thenBy { if (it.type == DirectoryType.Scalable) 0 else 1 }
        )

        for (dir in sortedDirs) {
            val dirFile = File(info.rootDir, dir.name)
            if (!dirFile.isDirectory) continue

            // Try .svg first (vector), then .png
            val svg = File(dirFile, "$iconName.svg")
            if (svg.isFile && svg.length() > 0) {
                pathCache[cacheKey] = svg
                return svg
            }
            val png = File(dirFile, "$iconName.png")
            if (png.isFile && png.length() > 0) {
                pathCache[cacheKey] = png
                return png
            }
        }

        // Themes without a directory list occur in hand-built packs; avoid a full
        // recursive walk on every missing icon for standard themes.
        if (info.directories.isEmpty()) {
            val loose = searchLoose(info.rootDir, "$iconName.svg") ?: searchLoose(info.rootDir, "$iconName.png")
            if (loose != null) {
                pathCache[cacheKey] = loose
                return loose
            }
        }

        // 3. Fallback to inherited themes
        if (fallbackResolver != null) {
            for (inheritName in info.inherits) {
                val fallback = fallbackResolver.invoke(inheritName)
                if (fallback != null && fallback != this) {
                    val inheritedFile = fallback.findIconFile(iconName, targetSizePx, visited)
                    if (inheritedFile != null) {
                        pathCache[cacheKey] = inheritedFile
                        return inheritedFile
                    }
                }
            }
        }

        if (topLevel) missingPaths.add(cacheKey)
        return null
    }

    private fun searchLoose(dir: File, fileName: String, depth: Int = 0): File? {
        if (depth > 4 || !dir.isDirectory) return null
        val direct = File(dir, fileName)
        if (direct.isFile && direct.length() > 0) return direct
        val subdirs = dir.listFiles() ?: return null
        for (sub in subdirs) {
            if (sub.isDirectory && !sub.name.startsWith('.')) {
                val found = searchLoose(sub, fileName, depth + 1)
                if (found != null) return found
            }
        }
        return null
    }

    fun getIconBitmap(iconName: String, targetSizePx: Int): Bitmap? {
        val cacheKey = "${info.id}:$iconName:$targetSizePx"
        synchronized(bitmapCache) {
            bitmapCache.get(cacheKey)?.let { return it }
        }

        val file = findIconFile(iconName, targetSizePx) ?: return null
        val resolvedFile = try { file.canonicalFile } catch (_: Exception) { file }
        val bitmap = when {
            resolvedFile.name.endsWith(".svg", ignoreCase = true) -> renderSvg(resolvedFile, targetSizePx)
            resolvedFile.name.endsWith(".png", ignoreCase = true) -> renderPng(resolvedFile, targetSizePx)
            else -> null
        }

        if (bitmap != null) {
            synchronized(bitmapCache) {
                bitmapCache.put(cacheKey, bitmap)
            }
        }
        return bitmap
    }

    fun clearCache() {
        synchronized(bitmapCache) {
            bitmapCache.evictAll()
        }
        pathCache.clear()
        missingPaths.clear()
    }

    companion object {
        fun renderSvg(file: File, targetPx: Int): Bitmap? = try {
            file.inputStream().buffered().use { stream ->
                val svg = SVG.getFromInputStream(stream)
                val rawViewBox = svg.documentViewBox
                val naturalWidth = when {
                    rawViewBox != null && rawViewBox.width() > 0f -> rawViewBox.width()
                    svg.documentWidth > 0f -> svg.documentWidth
                    else -> targetPx.toFloat()
                }
                val naturalHeight = when {
                    rawViewBox != null && rawViewBox.height() > 0f -> rawViewBox.height()
                    svg.documentHeight > 0f -> svg.documentHeight
                    else -> targetPx.toFloat()
                }
                if (rawViewBox == null) {
                    svg.setDocumentViewBox(0f, 0f, naturalWidth, naturalHeight)
                }
                svg.setDocumentWidth("100%")
                svg.setDocumentHeight("100%")

                val bitmap = Bitmap.createBitmap(targetPx, targetPx, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                val options = RenderOptions.create().preserveAspectRatio(PreserveAspectRatio.LETTERBOX)
                svg.renderToCanvas(canvas, options)
                bitmap
            }
        } catch (_: Exception) {
            null
        }

        fun renderPng(file: File, targetPx: Int): Bitmap? = try {
            val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, boundsOptions)
            val origW = boundsOptions.outWidth
            val origH = boundsOptions.outHeight
            if (origW <= 0 || origH <= 0) null
            else {
                var inSample = 1
                val maxDim = maxOf(origW, origH)
                while (maxDim / (inSample * 2) >= targetPx) {
                    inSample *= 2
                }
                val decodeOptions = BitmapFactory.Options().apply {
                    inSampleSize = inSample
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                val decoded = BitmapFactory.decodeFile(file.absolutePath, decodeOptions)
                if (decoded == null) null
                else if (decoded.width == targetPx && decoded.height == targetPx) decoded
                else Bitmap.createScaledBitmap(decoded, targetPx, targetPx, true)
            }
        } catch (_: Exception) {
            null
        }
    }
}
