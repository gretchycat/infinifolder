package com.infinifolder.viewer

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class EpubLinksTest {
    private val root = File(System.getProperty("java.io.tmpdir"), "epub-link-tests").absoluteFile
    private val chapters = listOf(File(root, "part-one/chapter.xhtml"), File(root, "part-two/chapter.xhtml"),
        File(root, "part-two/with+space chapter.xhtml"))
    @Test fun sameChapterAnchorRetainsCurrentChapterInsteadOfJumpingToFirst() {
        assertEquals(1, EpubLinks.chapterIndex(chapters, 1, ""))
    }
    @Test fun absolutePathsDistinguishEqualBasenames() {
        assertEquals(1, EpubLinks.chapterIndex(chapters, 0, chapters[1].absolutePath))
    }
    @Test fun relativeLinksResolveAgainstCurrentChapterDirectory() {
        assertEquals(0, EpubLinks.chapterIndex(chapters, 1, "../part-one/chapter.xhtml"))
        assertEquals(2, EpubLinks.chapterIndex(chapters, 1, "with+space chapter.xhtml"))
    }
    @Test fun unrelatedSuffixesAndFilesDoNotMatchAChapter() {
        assertNull(EpubLinks.chapterIndex(chapters, 0, File(root, "other/chapter.xhtml").absolutePath))
        assertNull(EpubLinks.chapterIndex(chapters, 0, "image.svg"))
    }
    @Test fun missingCurrentChapterIsHandledWithoutException() {
        assertNull(EpubLinks.chapterIndex(emptyList(), 0, ""))
        assertNull(EpubLinks.chapterIndex(chapters, -1, ""))
    }
}
