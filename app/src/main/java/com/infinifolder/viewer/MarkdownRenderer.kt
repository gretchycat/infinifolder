package com.infinifolder.viewer

import java.util.regex.Pattern

/**
 * Pure-Kotlin lightweight Markdown-to-HTML parser.
 * Converts headings, bold/italic, code blocks, lists, tables, blockquotes,
 * links, and images with sandboxed styling.
 */
object MarkdownRenderer {

    fun renderToHtml(markdown: String, isDark: Boolean = true): String {
        val lines = markdown.split("\n")
        val sb = StringBuilder()

        var inCodeBlock = false
        var codeBlockLang = ""
        var inList = false
        var isOrderedList = false
        var inTable = false

        for (rawLine in lines) {
            val line = rawLine.trimEnd()

            // 1. Fenced Code Block
            if (line.trim().startsWith("```") || line.trim().startsWith("~~~")) {
                if (inCodeBlock) {
                    sb.append("</code></pre>\n")
                    inCodeBlock = false
                } else {
                    closeListIfOpen(sb, inList, isOrderedList)
                    inList = false
                    codeBlockLang = line.trim().removePrefix("```").removePrefix("~~~").trim()
                    sb.append("<pre><code class=\"language-$codeBlockLang\">")
                    inCodeBlock = true
                }
                continue
            }

            if (inCodeBlock) {
                sb.append(escapeHtml(rawLine)).append("\n")
                continue
            }

            // 2. Blockquotes
            if (line.startsWith(">")) {
                closeListIfOpen(sb, inList, isOrderedList)
                inList = false
                val qContent = line.substring(1).trim()
                sb.append("<blockquote>").append(formatInline(qContent)).append("</blockquote>\n")
                continue
            }

            // 3. Headings (# H1 ... ###### H6)
            val headingMatch = Regex("^(#{1,6})\\s+(.*)$").find(line)
            if (headingMatch != null) {
                closeListIfOpen(sb, inList, isOrderedList)
                inList = false
                val level = headingMatch.groupValues[1].length
                val text = formatInline(headingMatch.groupValues[2].trim())
                sb.append("<h$level>$text</h$level>\n")
                continue
            }

            // 4. Horizontal Rules (---, ***, ___)
            if (line.trim().matches(Regex("^(-{3,}|\\*{3,}|_{3,})$"))) {
                closeListIfOpen(sb, inList, isOrderedList)
                inList = false
                sb.append("<hr/>\n")
                continue
            }

            // 5. Unordered list (*, -, +)
            val uListMatch = Regex("^([*+-])\\s+(.*)$").find(line.trimStart())
            if (uListMatch != null) {
                if (!inList || isOrderedList) {
                    closeListIfOpen(sb, inList, isOrderedList)
                    sb.append("<ul>\n")
                    inList = true
                    isOrderedList = false
                }
                sb.append("<li>").append(formatInline(uListMatch.groupValues[2])).append("</li>\n")
                continue
            }

            // 6. Ordered list (1. 2.)
            val oListMatch = Regex("^(\\d+)\\.\\s+(.*)$").find(line.trimStart())
            if (oListMatch != null) {
                if (!inList || !isOrderedList) {
                    closeListIfOpen(sb, inList, isOrderedList)
                    sb.append("<ol>\n")
                    inList = true
                    isOrderedList = true
                }
                sb.append("<li>").append(formatInline(oListMatch.groupValues[2])).append("</li>\n")
                continue
            }

            // Close list if non-list line
            closeListIfOpen(sb, inList, isOrderedList)
            inList = false

            // 7. Table handling
            if (line.trim().startsWith("|") && line.trim().endsWith("|")) {
                val cells = line.trim().removePrefix("|").removeSuffix("|").split("|").map { it.trim() }
                if (!inTable) {
                    sb.append("<table>\n<thead><tr>")
                    cells.forEach { sb.append("<th>").append(formatInline(it)).append("</th>") }
                    sb.append("</tr></thead>\n<tbody>\n")
                    inTable = true
                } else if (cells.all { it.matches(Regex("^:?-+:?$")) }) {
                    // Table separator row, ignore
                } else {
                    sb.append("<tr>")
                    cells.forEach { sb.append("<td>").append(formatInline(it)).append("</td>") }
                    sb.append("</tr>\n")
                }
                continue
            } else if (inTable) {
                sb.append("</tbody></table>\n")
                inTable = false
            }

            // 8. Empty lines
            if (line.isBlank()) {
                continue
            }

            // 9. Standard paragraphs
            sb.append("<p>").append(formatInline(line)).append("</p>\n")
        }

        if (inCodeBlock) sb.append("</code></pre>\n")
        closeListIfOpen(sb, inList, isOrderedList)
        if (inTable) sb.append("</tbody></table>\n")

        val bg = if (isDark) "#141714" else "#F5F4EF"
        val fg = if (isDark) "#E0E5DC" else "#20231F"
        val codeBg = if (isDark) "#202620" else "#E5E8E0"
        val border = if (isDark) "#333C32" else "#D0D6CA"
        val linkColor = if (isDark) "#81C784" else "#2E7D32"

        return """
        <!DOCTYPE html>
        <html>
        <head>
            <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=3.0, user-scalable=yes">
            <style>
                body {
                    background-color: $bg;
                    color: $fg;
                    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
                    line-height: 1.6;
                    padding: 16px;
                    word-wrap: break-word;
                }
                h1, h2, h3, h4, h5, h6 {
                    color: $fg;
                    margin-top: 1.2em;
                    margin-bottom: 0.5em;
                    font-weight: 600;
                    border-bottom: 1px solid $border;
                    padding-bottom: 0.3em;
                }
                a { color: $linkColor; text-decoration: underline; }
                code {
                    background-color: $codeBg;
                    padding: 2px 6px;
                    border-radius: 4px;
                    font-family: monospace;
                    font-size: 0.9em;
                }
                pre {
                    background-color: $codeBg;
                    padding: 12px;
                    border-radius: 6px;
                    overflow-x: auto;
                    border: 1px solid $border;
                }
                pre code { background: none; padding: 0; }
                blockquote {
                    margin: 0.8em 0;
                    padding: 0.5em 1em;
                    color: ${if (isDark) "#9BA994" else "#687363"};
                    border-left: 4px solid $linkColor;
                    background: ${if (isDark) "#1A201A" else "#EDF0E8"};
                }
                table {
                    border-collapse: collapse;
                    width: 100%;
                    margin: 1em 0;
                }
                th, td {
                    border: 1px solid $border;
                    padding: 8px 12px;
                    text-align: left;
                }
                th { background-color: $codeBg; font-weight: bold; }
                img { max-width: 100%; height: auto; border-radius: 4px; margin: 8px 0; }
                hr { border: 0; height: 1px; background: $border; margin: 24px 0; }
                ul, ol { padding-left: 24px; margin: 8px 0; }
                li { margin-bottom: 4px; }
            </style>
        </head>
        <body>
            ${sb.toString()}
        </body>
        </html>
        """.trimIndent()
    }

    private fun closeListIfOpen(sb: StringBuilder, inList: Boolean, isOrdered: Boolean) {
        if (inList) {
            sb.append(if (isOrdered) "</ol>\n" else "</ul>\n")
        }
    }

    private fun formatInline(text: String): String {
        var res = escapeHtml(text)

        // Images: ![alt](url)
        res = res.replace(Regex("!\\[(.*?)\\]\\((.*?)\\)")) {
            val alt = it.groupValues[1]
            val url = it.groupValues[2]
            "<img src=\"$url\" alt=\"$alt\"/>"
        }

        // Links: [title](url)
        res = res.replace(Regex("\\[(.*?)\\]\\((.*?)\\)")) {
            val title = it.groupValues[1]
            val url = it.groupValues[2]
            "<a href=\"$url\">$title</a>"
        }

        // Bold & Italic: ***text***
        res = res.replace(Regex("\\*\\*\\*(.*?)\\*\\*\\*"), "<strong><em>$1</em></strong>")
        // Bold: **text** or __text__
        res = res.replace(Regex("\\*\\*(.*?)\\*\\*"), "<strong>$1</strong>")
        res = res.replace(Regex("__(.*?)__"), "<strong>$1</strong>")
        // Italic: *text* or _text_
        res = res.replace(Regex("\\*(.*?)\\*"), "<em>$1</em>")
        res = res.replace(Regex("_(.*?)_"), "<em>$1</em>")
        // Strikethrough: ~~text~~
        res = res.replace(Regex("~~(.*?)~~"), "<del>$1</del>")
        // Inline Code: `code`
        res = res.replace(Regex("`(.*?)`"), "<code>$1</code>")

        return res
    }

    private fun escapeHtml(text: String): String =
        text.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
}
