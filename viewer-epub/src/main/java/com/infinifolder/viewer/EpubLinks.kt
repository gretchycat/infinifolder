package com.infinifolder.viewer

import java.io.File

/** Exact chapter matching avoids basename collisions and keeps anchors on the current chapter. */
internal object EpubLinks {
    fun chapterIndex(files: List<File>, currentIndex: Int, path: String): Int? {
        val current = files.getOrNull(currentIndex) ?: return null
        val target = when {
            path.isEmpty() -> current
            File(path).isAbsolute -> File(path)
            else -> File(current.parentFile, path)
        }.canonicalFile
        if (current.canonicalFile == target) return currentIndex
        return files.indexOfFirst { it.canonicalFile == target }.takeIf { it >= 0 }
    }
}
