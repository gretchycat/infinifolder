package com.infinifolder.theme

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.util.LruCache
import com.infinifolder.archive.ArchiveFormats
import com.infinifolder.archive.ArchiveMimeTypes
import com.infinifolder.model.ResourceEntry
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
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

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

    private val themeCandidateCache = LruCache<String, Boolean>(200)

    /**
     * Checks if an archive or directory actually contains a FreeDesktop index.theme file.
     * Prevents arbitrary zip files from displaying "Install as icon theme".
     */
    fun isIconTheme(entry: ResourceEntry, openStream: (() -> InputStream)? = null): Boolean {
        val cacheKey = "${entry.uri}:${entry.metadata.sizeBytes}:${entry.metadata.modifiedAtMillis}"
        themeCandidateCache.get(cacheKey)?.let { return it }

        val name = entry.metadata.name.lowercase(Locale.ROOT)

        if (entry.metadata.isDirectory) {
            val isTheme = if (entry.uri.scheme == "file") {
                val f = File(entry.uri.path ?: "")
                findIndexThemeFile(f) != null
            } else {
                name.contains("icon") || name.contains("theme")
            }
            themeCandidateCache.put(cacheKey, isTheme)
            return isTheme
        }

        val isArchive = name.endsWith(".zip") || name.endsWith(".tar.gz") || name.endsWith(".tgz") ||
            name.endsWith(".tar.xz") || name.endsWith(".txz") || name.endsWith(".tar.bz2") || name.endsWith(".tar")
        if (!isArchive) {
            themeCandidateCache.put(cacheKey, false)
            return false
        }

        // Fast inspection for local files
        if (entry.uri.scheme == "file") {
            val file = File(entry.uri.path ?: "")
            if (!file.isFile) return false

            if (name.endsWith(".zip")) {
                try {
                    ZipFile(file).use { zf ->
                        val hasIndex = zf.entries().asSequence().any {
                            val n = it.name.trimStart('/')
                            n.equals("index.theme", ignoreCase = true) || n.endsWith("/index.theme", ignoreCase = true)
                        }
                        themeCandidateCache.put(cacheKey, hasIndex)
                        return hasIndex
                    }
                } catch (_: Exception) {
                    themeCandidateCache.put(cacheKey, false)
                    return false
                }
            }

            // Tar archives (uncompressed or compressed)
            try {
                val bis = BufferedInputStream(file.inputStream())
                val tarIn: TarArchiveInputStream = when {
                    name.endsWith(".tar.gz") || name.endsWith(".tgz") -> TarArchiveInputStream(GzipCompressorInputStream(bis))
                    name.endsWith(".tar.xz") || name.endsWith(".txz") -> TarArchiveInputStream(XZCompressorInputStream(bis))
                    name.endsWith(".tar.bz2") -> TarArchiveInputStream(BZip2CompressorInputStream(bis))
                    else -> TarArchiveInputStream(bis)
                }
                tarIn.use { input ->
                    var count = 0
                    var tarEntry: TarArchiveEntry? = input.nextEntry
                    while (tarEntry != null && count < 100) {
                        val n = tarEntry.name.trimStart('/')
                        if (n.equals("index.theme", ignoreCase = true) || n.endsWith("/index.theme", ignoreCase = true)) {
                            themeCandidateCache.put(cacheKey, true)
                            return true
                        }
                        count++
                        tarEntry = input.nextEntry
                    }
                }
            } catch (_: Exception) {}
            themeCandidateCache.put(cacheKey, false)
            return false
        }

        // Non-local streams (SAF / SFTP / SMB / FTP)
        if (openStream != null) {
            try {
                if (name.endsWith(".zip")) {
                    ZipInputStream(BufferedInputStream(openStream())).use { zis ->
                        var count = 0
                        var ze = zis.nextEntry
                        while (ze != null && count < 100) {
                            val n = ze.name.trimStart('/')
                            if (n.equals("index.theme", ignoreCase = true) || n.endsWith("/index.theme", ignoreCase = true)) {
                                themeCandidateCache.put(cacheKey, true)
                                return true
                            }
                            count++
                            ze = zis.nextEntry
                        }
                    }
                } else {
                    val bis = BufferedInputStream(openStream())
                    val tarIn: TarArchiveInputStream = when {
                        name.endsWith(".tar.gz") || name.endsWith(".tgz") -> TarArchiveInputStream(GzipCompressorInputStream(bis))
                        name.endsWith(".tar.xz") || name.endsWith(".txz") -> TarArchiveInputStream(XZCompressorInputStream(bis))
                        name.endsWith(".tar.bz2") -> TarArchiveInputStream(BZip2CompressorInputStream(bis))
                        else -> TarArchiveInputStream(bis)
                    }
                    tarIn.use { input ->
                        var count = 0
                        var tarEntry: TarArchiveEntry? = input.nextEntry
                        while (tarEntry != null && count < 100) {
                            val n = tarEntry.name.trimStart('/')
                            if (n.equals("index.theme", ignoreCase = true) || n.endsWith("/index.theme", ignoreCase = true)) {
                                themeCandidateCache.put(cacheKey, true)
                                return true
                            }
                            count++
                            tarEntry = input.nextEntry
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        themeCandidateCache.put(cacheKey, false)
        return false
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
        fun buildMimeIconCandidates(meta: ResourceMetadata): List<String> =
            com.infinifolder.mime.MimeRegistry.iconCandidates(com.infinifolder.mime.MimeRegistry.effective(meta.name, meta.mimeType))

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
