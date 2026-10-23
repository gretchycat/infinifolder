package com.infinifolder.theme

import java.io.File

enum class DirectoryType {
    Fixed, Scalable, Threshold
}

data class ThemeDirectory(
    val name: String,
    val size: Int,
    val scale: Int = 1,
    val context: String = "",
    val type: DirectoryType = DirectoryType.Fixed,
    val minSize: Int = size,
    val maxSize: Int = size,
    val threshold: Int = 2
) {
    fun matchesSize(targetPx: Int): Boolean = when (type) {
        DirectoryType.Fixed -> size == targetPx
        DirectoryType.Scalable -> targetPx in minSize..maxSize
        DirectoryType.Threshold -> targetPx in (size - threshold)..(size + threshold)
    }

    fun sizeDistance(targetPx: Int): Int = when (type) {
        DirectoryType.Fixed -> Math.abs(size - targetPx)
        DirectoryType.Scalable -> if (targetPx in minSize..maxSize) 0 else if (targetPx < minSize) minSize - targetPx else targetPx - maxSize
        DirectoryType.Threshold -> if (targetPx in (size - threshold)..(size + threshold)) 0 else Math.abs(size - targetPx)
    }
}

data class IconThemeInfo(
    val id: String,
    val name: String,
    val comment: String = "",
    val rootDir: File,
    val inherits: List<String> = emptyList(),
    val directories: List<ThemeDirectory> = emptyList(),
    val isSystem: Boolean = false
)

object FreeDesktopThemeParser {

    fun parse(themeFile: File, rootDir: File = themeFile.parentFile ?: themeFile, isSystem: Boolean = false): IconThemeInfo? {
        if (!themeFile.isFile) return null
        val lines = try {
            themeFile.readLines(Charsets.UTF_8)
        } catch (_: Exception) {
            return null
        }

        var currentSection = ""
        var themeName: String? = null
        var comment = ""
        var inherits = mutableListOf<String>()
        var directoryNames = mutableListOf<String>()
        val dirConfigs = mutableMapOf<String, MutableMap<String, String>>()

        for (rawLine in lines) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith('#')) continue
            if (line.startsWith('[') && line.endsWith(']')) {
                currentSection = line.substring(1, line.length - 1).trim()
                continue
            }
            val eqIdx = line.indexOf('=')
            if (eqIdx <= 0) continue
            val key = line.substring(0, eqIdx).trim()
            val value = line.substring(eqIdx + 1).trim()

            if (currentSection.equals("Icon Theme", ignoreCase = true)) {
                when {
                    key.equals("Name", ignoreCase = true) && themeName == null -> themeName = value
                    key.equals("Comment", ignoreCase = true) && comment.isEmpty() -> comment = value
                    key.equals("Inherits", ignoreCase = true) -> {
                        inherits = value.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
                    }
                    key.equals("Directories", ignoreCase = true) -> {
                        directoryNames = value.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
                    }
                }
            } else if (currentSection.isNotEmpty()) {
                val map = dirConfigs.getOrPut(currentSection) { mutableMapOf() }
                map[key.lowercase()] = value
            }
        }

        val finalName = themeName?.ifBlank { null } ?: rootDir.name
        val parsedDirs = mutableListOf<ThemeDirectory>()

        for (dirName in directoryNames) {
            val config = dirConfigs[dirName]
                ?: dirConfigs.entries.firstOrNull { it.key.equals(dirName, ignoreCase = true) }?.value
                ?: emptyMap()

            val size = config["size"]?.toIntOrNull() ?: extractSizeFromName(dirName) ?: 48
            val scale = config["scale"]?.toIntOrNull() ?: 1
            val context = config["context"] ?: ""
            val typeStr = config["type"]?.lowercase() ?: if (dirName.startsWith("scalable", ignoreCase = true)) "scalable" else "fixed"
            val type = when (typeStr) {
                "scalable" -> DirectoryType.Scalable
                "threshold" -> DirectoryType.Threshold
                else -> DirectoryType.Fixed
            }
            val minSize = config["minsize"]?.toIntOrNull() ?: if (type == DirectoryType.Scalable) 8 else size
            val maxSize = config["maxsize"]?.toIntOrNull() ?: if (type == DirectoryType.Scalable) 512 else size
            val threshold = config["threshold"]?.toIntOrNull() ?: 2

            parsedDirs.add(
                ThemeDirectory(
                    name = dirName,
                    size = size,
                    scale = scale,
                    context = context,
                    type = type,
                    minSize = minSize,
                    maxSize = maxSize,
                    threshold = threshold
                )
            )
        }

        return IconThemeInfo(
            id = rootDir.name,
            name = finalName,
            comment = comment,
            rootDir = rootDir,
            inherits = inherits,
            directories = parsedDirs,
            isSystem = isSystem
        )
    }

    private fun extractSizeFromName(name: String): Int? {
        val match = Regex("""(\d+)x(\d+)""").find(name)
        if (match != null) return match.groupValues[1].toIntOrNull()
        val numMatch = Regex("""\b(\d+)\b""").find(name)
        return numMatch?.groupValues?.get(1)?.toIntOrNull()
    }
}
