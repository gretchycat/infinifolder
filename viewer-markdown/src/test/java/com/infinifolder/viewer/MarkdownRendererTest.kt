package com.infinifolder.viewer

import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownRendererTest {

    @Test
    fun testHeadings() {
        val md = "# Heading 1\n## Heading 2\n### Heading 3"
        val html = MarkdownRenderer.renderToHtml(md)
        assertTrue(html.contains("<h1>Heading 1</h1>"))
        assertTrue(html.contains("<h2>Heading 2</h2>"))
        assertTrue(html.contains("<h3>Heading 3</h3>"))
    }

    @Test
    fun testCodeBlocks() {
        val md = "```kotlin\nfun main() {\n    println(\"Hello\")\n}\n```"
        val html = MarkdownRenderer.renderToHtml(md)
        assertTrue(html.contains("<pre><code class=\"language-kotlin\">"))
        assertTrue(html.contains("println(&quot;Hello&quot;)"))
    }

    @Test
    fun testInlineCodeAndFormatting() {
        val md = "This is **bold**, *italic*, and `val x = 1`."
        val html = MarkdownRenderer.renderToHtml(md)
        assertTrue(html.contains("<strong>bold</strong>"))
        assertTrue(html.contains("<em>italic</em>"))
        assertTrue(html.contains("<code>val x = 1</code>"))
    }

    @Test
    fun testLists() {
        val md = "- Item 1\n- Item 2\n- Item 3"
        val html = MarkdownRenderer.renderToHtml(md)
        assertTrue(html.contains("<ul>"))
        assertTrue(html.contains("<li>Item 1</li>"))
        assertTrue(html.contains("<li>Item 2</li>"))
        assertTrue(html.contains("<li>Item 3</li>"))
        assertTrue(html.contains("</ul>"))
    }

    @Test
    fun testNumberedLists() {
        val md = "1. First\n2. Second"
        val html = MarkdownRenderer.renderToHtml(md)
        assertTrue(html.contains("<ol>"))
        assertTrue(html.contains("<li>First</li>"))
        assertTrue(html.contains("<li>Second</li>"))
        assertTrue(html.contains("</ol>"))
    }

    @Test
    fun testTables() {
        val md = """
            | Header 1 | Header 2 |
            | --- | --- |
            | Cell 1 | Cell 2 |
        """.trimIndent()
        val html = MarkdownRenderer.renderToHtml(md)
        assertTrue(html.contains("<table>"))
        assertTrue(html.contains("<th>Header 1</th>"))
        assertTrue(html.contains("<th>Header 2</th>"))
        assertTrue(html.contains("<td>Cell 1</td>"))
        assertTrue(html.contains("<td>Cell 2</td>"))
        assertTrue(html.contains("</table>"))
    }

    @Test
    fun testBlockquote() {
        val md = "> This is a quote"
        val html = MarkdownRenderer.renderToHtml(md)
        assertTrue(html.contains("<blockquote>"))
        assertTrue(html.contains("This is a quote"))
        assertTrue(html.contains("</blockquote>"))
    }

    @Test
    fun testHtmlSanitization() {
        val md = "Test <script>alert('xss')</script> end"
        val html = MarkdownRenderer.renderToHtml(md)
        assertTrue(!html.contains("<script>"))
        assertTrue(html.contains("&lt;script&gt;"))
    }
}
