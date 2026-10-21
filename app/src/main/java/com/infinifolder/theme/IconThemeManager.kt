package com.infinifolder.theme

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import com.infinifolder.archive.ArchiveFormats
import com.infinifolder.archive.ArchiveMimeTypes
import com.infinifolder.model.ResourceMetadata
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Paths
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class IconThemeManager(private val context: Context) {

    private val shared: SharedPreferences = context.getSharedPreferences("browser", Context.MODE_PRIVATE)
    private val importedThemesDir = File(context.filesDir, "icon_themes").apply { mkdirs() }
    private val themeCache = ConcurrentHashMap<String, FreeDesktopIconTheme>()
    private var activeTheme: FreeDesktopIconTheme? = null

    init {
        val savedThemeId = shared.getString("active_icon_theme", null)
        if (!savedThemeId.isNullOrBlank()) {
            loadTheme(savedThemeId)
        }
    }

    fun hasActiveTheme(): Boolean = activeTheme != null

    fun getActiveThemeInfo(): IconThemeInfo? = activeTheme?.info

    fun getActiveThemeId(): String? = shared.getString("active_icon_theme", null)

    @Synchronized
    fun setActiveTheme(themeId: String?) {
        if (themeId.isNullOrBlank()) {
            shared.edit().remove("active_icon_theme").apply()
            activeTheme = null
        } else {
            shared.edit().putString("active_icon_theme", themeId).apply()
            loadTheme(themeId)
        }
    }

    @Synchronized
    private fun loadTheme(themeId: String) {
        val allThemes = listAvailableThemes()
        val targetInfo = allThemes.firstOrNull { it.id == themeId || it.name.equals(themeId, ignoreCase = true) }
        if (targetInfo == null) {
            activeTheme = null
            return
        }

        activeTheme = themeCache.getOrPut(targetInfo.id) {
            FreeDesktopIconTheme(targetInfo) { inheritName ->
                resolveInheritedTheme(inheritName, allThemes)
            }
        }
    }

    private fun resolveInheritedTheme(inheritName: String, allThemes: List<IconThemeInfo>): FreeDesktopIconTheme? {
        val found = allThemes.firstOrNull { it.id.equals(inheritName, ignoreCase = true) || it.name.equals(inheritName, ignoreCase = true) }
            ?: return null
        return themeCache.getOrPut(found.id) {
            FreeDesktopIconTheme(found) { nextInherit ->
                resolveInheritedTheme(nextInherit, allThemes)
            }
        }
    }

    /**
     * Lists all available icon themes, including imported themes in app storage,
     * Termux user themes, and system icon themes.
     */
    fun listAvailableThemes(): List<IconThemeInfo> {
        val themes = mutableListOf<IconThemeInfo>()
        val seenIds = mutableSetOf<String>()

        // 1. Scan imported themes in app storage
        scanDirectoryForThemes(importedThemesDir, isSystem = false, themes, seenIds)

        // 2. Scan Termux and standard Linux locations
        val systemPaths = listOf(
            "/data/data/com.termux/files/usr/share/icons",
            "/data/data/com.termux/files/home/.local/share/icons",
            "/data/data/com.termux/files/home/.icons",
            "/usr/share/icons",
            "/usr/local/share/icons"
        )
        for (path in systemPaths) {
            val dir = File(path)
            if (dir.isDirectory) {
                scanDirectoryForThemes(dir, isSystem = true, themes, seenIds)
            }
        }

        return themes.sortedWith(compareBy({ it.isSystem }, { it.name.lowercase(Locale.ROOT) }))
    }

    private fun scanDirectoryForThemes(
        baseDir: File,
        isSystem: Boolean,
        outList: MutableList<IconThemeInfo>,
        seenIds: MutableSet<String>
    ) {
        val entries = baseDir.listFiles() ?: return
        for (sub in entries) {
            if (!sub.isDirectory || sub.name.startsWith('.')) continue
            val indexTheme = File(sub, "index.theme")
            if (indexTheme.isFile) {
                val parsed = FreeDesktopThemeParser.parse(indexTheme, sub, isSystem)
                if (parsed != null && seenIds.add(parsed.id.lowercase(Locale.ROOT))) {
                    outList.add(parsed)
                }
            }
        }
    }

    /**
     * Imports an icon theme from a directory (e.g. extracted folder).
     */
    fun importThemeFromDirectory(sourceDir: File): IconThemeInfo {
        val indexTheme = findIndexThemeFile(sourceDir)
            ?: throw IllegalArgumentException("No index.theme file found in '${sourceDir.name}'")

        val themeRoot = indexTheme.parentFile ?: sourceDir
        val parsed = FreeDesktopThemeParser.parse(indexTheme, themeRoot, isSystem = false)
            ?: throw IllegalArgumentException("Invalid index.theme format")

        val targetDir = File(importedThemesDir, parsed.name.replace(Regex("""[^a-zA-Z0-9._-]"""), "_"))
        if (targetDir.canonicalPath != themeRoot.canonicalPath) {
            targetDir.deleteRecursively()
            themeRoot.copyRecursively(targetDir, overwrite = true)
        }

        val finalIndex = File(targetDir, "index.theme")
        return FreeDesktopThemeParser.parse(finalIndex, targetDir, isSystem = false)
            ?: throw IllegalStateException("Failed to parse imported theme")
    }

    /**
     * Imports an icon theme from an archive (.tar.gz, .tar.xz, .tar.bz2, .zip, etc.).
     */
    fun importThemeFromArchive(
        openStream: () -> InputStream,
        archiveFileName: String,
        progressCallback: ((String) -> Unit)? = null
    ): IconThemeInfo {
        progressCallback?.invoke("Extracting $archiveFileName...")
        val tempExtractDir = File(context.cacheDir, "theme_extract_${System.currentTimeMillis()}").apply { mkdirs() }
        try {
            extractArchive(openStream, archiveFileName, tempExtractDir)
            progressCallback?.invoke("Analyzing theme structure...")
            val indexTheme = findIndexThemeFile(tempExtractDir)
                ?: throw IllegalArgumentException("No index.theme file found in archive")

            val themeRoot = indexTheme.parentFile ?: tempExtractDir
            val parsed = FreeDesktopThemeParser.parse(indexTheme, themeRoot, isSystem = false)
                ?: throw IllegalArgumentException("Could not parse index.theme in archive")

            val targetDirName = parsed.name.replace(Regex("""[^a-zA-Z0-9._-]"""), "_").ifEmpty { "imported_theme" }
            val targetDir = File(importedThemesDir, targetDirName)
            targetDir.deleteRecursively()
            targetDir.mkdirs()

            progressCallback?.invoke("Installing ${parsed.name}...")
            themeRoot.copyRecursively(targetDir, overwrite = true)

            val finalIndex = File(targetDir, "index.theme")
            val result = FreeDesktopThemeParser.parse(finalIndex, targetDir, isSystem = false)
                ?: throw IllegalStateException("Theme verification failed after install")

            themeCache.remove(result.id)
            progressCallback?.invoke("Theme '${result.name}' installed successfully!")
            return result
        } finally {
            tempExtractDir.deleteRecursively()
        }
    }

    fun deleteImportedTheme(themeId: String): Boolean {
        val target = File(importedThemesDir, themeId)
        if (target.isDirectory && target.parentFile == importedThemesDir) {
            themeCache.remove(themeId)
            if (activeTheme?.info?.id == themeId) {
                setActiveTheme(null)
            }
            return target.deleteRecursively()
        }
        return false
    }

    private fun findIndexThemeFile(dir: File, depth: Int = 0): File? {
        if (depth > 3 || !dir.isDirectory) return null
        val direct = File(dir, "index.theme")
        if (direct.isFile) return direct
        val subdirs = dir.listFiles() ?: return null
        for (sub in subdirs) {
            if (sub.isDirectory && !sub.name.startsWith('.')) {
                val found = findIndexThemeFile(sub, depth + 1)
                if (found != null) return found
            }
        }
        return null
    }

    private fun extractArchive(openStream: () -> InputStream, archiveName: String, destination: File) {
        val lowerName = archiveName.lowercase(Locale.ROOT)
        val bis = BufferedInputStream(openStream())
        val deferredSymlinks = mutableMapOf<File, String>()

        when {
            lowerName.endsWith(".tar.gz") || lowerName.endsWith(".tgz") -> {
                extractTar(TarArchiveInputStream(GzipCompressorInputStream(bis)), destination, deferredSymlinks)
            }
            lowerName.endsWith(".tar.xz") || lowerName.endsWith(".txz") -> {
                extractTar(TarArchiveInputStream(XZCompressorInputStream(bis)), destination, deferredSymlinks)
            }
            lowerName.endsWith(".tar.bz2") || lowerName.endsWith(".tbz2") -> {
                extractTar(TarArchiveInputStream(BZip2CompressorInputStream(bis)), destination, deferredSymlinks)
            }
            lowerName.endsWith(".tar") -> {
                extractTar(TarArchiveInputStream(bis), destination, deferredSymlinks)
            }
            lowerName.endsWith(".zip") -> {
                extractZip(ZipArchiveInputStream(bis), destination)
            }
            else -> {
                // Try zip first, then tar
                try {
                    extractZip(ZipArchiveInputStream(bis), destination)
                } catch (_: Exception) {
                    val retryBis = BufferedInputStream(openStream())
                    extractTar(TarArchiveInputStream(retryBis), destination, deferredSymlinks)
                }
            }
        }

        // Resolve deferred symbolic links
        for ((linkFile, targetPath) in deferredSymlinks) {
            try {
                if (android.os.Build.VERSION.SDK_INT >= 26) {
                    Files.createSymbolicLink(linkFile.toPath(), Paths.get(targetPath))
                } else {
                    // Fallback copy target if symlinks unsupported
                    val target = File(linkFile.parentFile, targetPath)
                    if (target.isFile) target.copyTo(linkFile, overwrite = true)
                }
            } catch (_: Exception) {
                // If symlink creation fails on filesystem, copy target file directly
                val target = File(linkFile.parentFile, targetPath)
                if (target.isFile) {
                    try { target.copyTo(linkFile, overwrite = true) } catch (_: Exception) {}
                }
            }
        }
    }

    private fun extractTar(tarIn: TarArchiveInputStream, destination: File, symlinks: MutableMap<File, String>) {
        tarIn.use { input ->
            var entry: TarArchiveEntry? = input.nextEntry
            while (entry != null) {
                val cleanName = entry.name.trimStart('/', '\\')
                if (cleanName.isNotEmpty()) {
                    val destFile = File(destination, cleanName)
                    // Security guard against path traversal
                    if (destFile.canonicalPath.startsWith(destination.canonicalPath)) {
                        if (entry.isDirectory) {
                            destFile.mkdirs()
                        } else if (entry.isSymbolicLink && entry.linkName != null) {
                            destFile.parentFile?.mkdirs()
                            symlinks[destFile] = entry.linkName
                        } else {
                            destFile.parentFile?.mkdirs()
                            FileOutputStream(destFile).use { out ->
                                input.copyTo(out)
                            }
                        }
                    }
                }
                entry = input.nextEntry
            }
        }
    }

    private fun extractZip(zipIn: ZipArchiveInputStream, destination: File) {
        zipIn.use { input ->
            var entry = input.nextEntry
            while (entry != null) {
                val cleanName = entry.name.trimStart('/', '\\')
                if (cleanName.isNotEmpty()) {
                    val destFile = File(destination, cleanName)
                    if (destFile.canonicalPath.startsWith(destination.canonicalPath)) {
                        if (entry.isDirectory) {
                            destFile.mkdirs()
                        } else {
                            destFile.parentFile?.mkdirs()
                            FileOutputStream(destFile).use { out ->
                                input.copyTo(out)
                            }
                        }
                    }
                }
                entry = input.nextEntry
            }
        }
    }

    /**
     * Resolves an icon for a file by examining its MIME type, extension, and generic fallbacks.
     */
    fun resolveFileIcon(meta: ResourceMetadata, targetSizePx: Int): Bitmap? {
        val theme = activeTheme ?: return null
        val candidates = buildMimeIconCandidates(meta)
        for (candidate in candidates) {
            val bmp = theme.getIconBitmap(candidate, targetSizePx)
            if (bmp != null) return bmp
        }
        return null
    }

    /**
     * Resolves an icon for a directory by checking standard folder names (Downloads, Documents, etc.)
     * and generic folder fallbacks.
     */
    fun resolveFolderIcon(folderName: String, targetSizePx: Int): Bitmap? {
        val theme = activeTheme ?: return null
        val candidates = buildFolderIconCandidates(folderName)
        for (candidate in candidates) {
            val bmp = theme.getIconBitmap(candidate, targetSizePx)
            if (bmp != null) return bmp
        }
        return null
    }

    /**
     * Resolves a place icon (e.g. internal storage, application public, home, trash).
     */
    fun resolvePlaceIcon(placeId: String, targetSizePx: Int): Bitmap? {
        val theme = activeTheme ?: return null
        val lower = placeId.lowercase(Locale.ROOT)
        val candidates = when {
            lower.startsWith("internal_shared") -> listOf("drive-harddisk", "drive-removable-media", "folder")
            lower.startsWith("application_public") -> listOf("applications-other", "system-run", "folder")
            lower.startsWith("sftp") || lower.startsWith("ftp") || lower.startsWith("smb") || lower.startsWith("network") ->
                listOf("network-server", "folder-remote", "network-workgroup", "folder")
            lower.startsWith("user_home") || lower.startsWith("home") -> listOf("user-home", "folder-home", "folder")
            lower.startsWith("trash") -> listOf("user-trash", "user-trash-full", "folder")
            else -> listOf("folder")
        }
        for (candidate in candidates) {
            val bmp = theme.getIconBitmap(candidate, targetSizePx)
            if (bmp != null) return bmp
        }
        return null
    }

    companion object {
        fun buildMimeIconCandidates(meta: ResourceMetadata): List<String> {
            val list = mutableListOf<String>()
            val mime = meta.mimeType.lowercase(Locale.ROOT).substringBefore(';').trim()
            val ext = meta.name.substringAfterLast('.', "").lowercase(Locale.ROOT)

            // 1. Exact MIME with '-'
            if (mime.isNotEmpty() && mime != "application/octet-stream") {
                list.add(mime.replace('/', '-'))
            }

            // 2. Specific extension mappings
            when (ext) {
                "pdf" -> list.add("application-pdf")
                "apk", "xapk", "apkm", "aab" -> {
                    list.add("android-package-archive")
                    list.add("application-vnd.android.package-archive")
                    list.add("package-x-apk")
                    list.add("package-x-generic")
                }
                "jar", "war", "ear" -> {
                    list.add("application-x-java-archive")
                    list.add("application-java-archive")
                    list.add("application-x-jar")
                    list.add("java")
                    list.add("package-x-generic")
                }
                "love" -> {
                    list.add("application-x-love-game")
                    list.add("love")
                    list.add("package-x-generic")
                }
                "deb" -> {
                    list.add("application-x-deb")
                    list.add("package-x-generic")
                }
                "zip", "7z", "tar", "gz", "xz", "bz2", "tgz", "txz" -> {
                    list.add("application-zip")
                    list.add("application-x-archive")
                    list.add("package-x-generic")
                }
                "iso", "img", "vdi", "qcow2", "vmdk" -> {
                    list.add("application-x-cd-image")
                    list.add("media-optical")
                    list.add("drive-harddisk")
                }
                "py" -> list.add("text-x-python")
                "sh", "bash" -> list.add("application-x-shellscript")
                "kt", "kts" -> list.add("text-x-kotlin")
                "java" -> list.add("text-x-java")
                "c" -> list.add("text-x-c")
                "cpp", "cc", "cxx" -> list.add("text-x-c++")
                "h", "hpp" -> list.add("text-x-chdr")
                "rs" -> list.add("text-x-rust")
                "go" -> list.add("text-x-go")
                "js" -> list.add("application-javascript")
                "ts" -> list.add("application-typescript")
                "json" -> list.add("application-json")
                "xml" -> list.add("application-xml")
                "html", "htm" -> list.add("text-html")
                "css" -> list.add("text-css")
                "md", "markdown" -> list.add("text-markdown")
                "doc", "docx" -> {
                    list.add("application-msword")
                    list.add("application-vnd.openxmlformats-officedocument.wordprocessingml.document")
                    list.add("x-office-document")
                }
                "xls", "xlsx" -> {
                    list.add("application-vnd.ms-excel")
                    list.add("application-vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                    list.add("x-office-spreadsheet")
                }
                "ppt", "pptx" -> {
                    list.add("application-vnd.ms-powerpoint")
                    list.add("application-vnd.openxmlformats-officedocument.presentationml.presentation")
                    list.add("x-office-presentation")
                }
                "mp3", "flac", "wav", "ogg", "m4a", "aac", "opus" -> {
                    list.add("audio-x-generic")
                    list.add("audio-mpeg")
                }
                "mp4", "mkv", "avi", "mov", "webm", "flv", "wmv" -> {
                    list.add("video-x-generic")
                    list.add("video-mp4")
                }
                "jpg", "jpeg", "png", "gif", "webp", "bmp", "svg" -> {
                    list.add("image-x-generic")
                }
            }

            // 3. Category fallbacks
            if (mime.startsWith("image/")) list.add("image-x-generic")
            if (mime.startsWith("video/")) list.add("video-x-generic")
            if (mime.startsWith("audio/")) list.add("audio-x-generic")
            if (mime.startsWith("text/")) list.add("text-x-generic")
            if (mime.startsWith("font/")) list.add("font-x-generic")
            if (ArchiveFormats.isDefaultBrowsableArchive(meta.name, meta.mimeType)) list.add("package-x-generic")

            // 4. Generic fallbacks
            list.add("text-plain")
            list.add("application-octet-stream")
            list.add("unknown")
            return list.distinct()
        }

        fun buildFolderIconCandidates(folderName: String): List<String> {
            val list = mutableListOf<String>()
            val lower = folderName.lowercase(Locale.ROOT)

            when (lower) {
                "download", "downloads" -> {
                    list.add("folder-download")
                    list.add("folder-downloads")
                }
                "document", "documents", "docs" -> {
                    list.add("folder-documents")
                }
                "music", "audio", "podcasts" -> {
                    list.add("folder-music")
                    list.add("folder-audio")
                }
                "picture", "pictures", "photo", "photos", "images", "dcim" -> {
                    list.add("folder-pictures")
                    list.add("folder-images")
                }
                "video", "videos", "movie", "movies" -> {
                    list.add("folder-videos")
                    list.add("folder-movies")
                }
                "desktop" -> list.add("user-desktop")
                "android", ".android" -> list.add("folder-android")
                "public" -> {
                    list.add("folder-publicshare")
                    list.add("folder-remote")
                }
                "home", "root" -> list.add("user-home")
            }

            // Standard folder fallbacks
            list.add("folder")
            list.add("inode-directory")
            return list.distinct()
        }
    }
}
