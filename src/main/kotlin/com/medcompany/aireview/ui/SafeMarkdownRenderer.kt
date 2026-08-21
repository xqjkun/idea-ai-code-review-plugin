package com.medcompany.aireview.ui

/**
 * A deliberately small Markdown renderer for AI review text.
 *
 * The source is escaped before formatting is applied, so model-produced HTML
 * cannot create Swing HTML elements. Supporting the common subset used by the
 * review prompt also keeps the plugin independent from an optional IDE Markdown
 * plugin across all supported IDEA versions.
 */
internal object SafeMarkdownRenderer {
    private val fencePattern = Regex("^\\s*```(?:[A-Za-z0-9_+.#-]+)?\\s*$")
    private val headingPattern = Regex("^(#{1,6})\\s+(.+)$")
    private val unorderedItemPattern = Regex("^\\s*[-+*]\\s+(.+)$")
    private val orderedItemPattern = Regex("^\\s*\\d+[.)]\\s+(.+)$")
    private val horizontalRulePattern = Regex("^\\s*(?:-{3,}|\\*{3,}|_{3,})\\s*$")
    private val inlineCodePattern = Regex("`([^`\\n]+)`")
    private val strongAsteriskPattern = Regex("\\*\\*(.+?)\\*\\*")
    private val strongUnderscorePattern = Regex("__(.+?)__")
    private val strikePattern = Regex("~~(.+?)~~")
    private val emphasisAsteriskPattern = Regex("(?<!\\*)\\*([^*\\n]+)\\*(?!\\*)")
    private val emphasisUnderscorePattern = Regex("(?<![A-Za-z0-9_])_([^_\\n]+)_(?![A-Za-z0-9_])")

    fun render(markdown: String): String {
        if (markdown.isBlank()) return "<p></p>"

        val output = StringBuilder()
        val paragraph = mutableListOf<String>()
        val code = mutableListOf<String>()
        var inFence = false
        var list: ListKind? = null

        fun closeParagraph() {
            if (paragraph.isEmpty()) return
            output.append("<p>")
                .append(paragraph.joinToString("<br>") { renderInline(it.trim()) })
                .append("</p>")
            paragraph.clear()
        }

        fun closeList() {
            val current = list ?: return
            output.append(if (current == ListKind.ORDERED) "</ol>" else "</ul>")
            list = null
        }

        fun appendListItem(kind: ListKind, value: String) {
            closeParagraph()
            if (list != kind) {
                closeList()
                output.append(if (kind == ListKind.ORDERED) "<ol>" else "<ul>")
                list = kind
            }
            output.append("<li>").append(renderInline(value.trim())).append("</li>")
        }

        fun closeCodeBlock() {
            output.append("<pre><code>")
                .append(escapeHtml(code.joinToString("\n")))
                .append("</code></pre>")
            code.clear()
        }

        markdown.replace("\r\n", "\n").replace('\r', '\n').lines().forEach { line ->
            if (inFence) {
                if (fencePattern.matches(line)) {
                    closeCodeBlock()
                    inFence = false
                } else {
                    code += line
                }
                return@forEach
            }

            if (fencePattern.matches(line)) {
                closeParagraph()
                closeList()
                inFence = true
                return@forEach
            }

            val trimmed = line.trim()
            if (trimmed.isEmpty()) {
                closeParagraph()
                closeList()
                return@forEach
            }

            headingPattern.matchEntire(trimmed)?.let { match ->
                closeParagraph()
                closeList()
                val level = match.groupValues[1].length
                output.append("<h").append(level).append(">")
                    .append(renderInline(match.groupValues[2]))
                    .append("</h").append(level).append(">")
                return@forEach
            }

            unorderedItemPattern.matchEntire(line)?.let { match ->
                appendListItem(ListKind.UNORDERED, match.groupValues[1])
                return@forEach
            }
            orderedItemPattern.matchEntire(line)?.let { match ->
                appendListItem(ListKind.ORDERED, match.groupValues[1])
                return@forEach
            }

            if (horizontalRulePattern.matches(trimmed)) {
                closeParagraph()
                closeList()
                output.append("<hr>")
                return@forEach
            }

            if (trimmed.startsWith(">")) {
                closeParagraph()
                closeList()
                output.append("<blockquote>")
                    .append(renderInline(trimmed.removePrefix(">").trim()))
                    .append("</blockquote>")
                return@forEach
            }

            closeList()
            paragraph += line
        }

        closeParagraph()
        closeList()
        if (inFence) closeCodeBlock()
        return output.toString()
    }

    private fun renderInline(source: String): String {
        val codeSpans = mutableListOf<String>()
        val tokenized = inlineCodePattern.replace(source) { match ->
            val index = codeSpans.size
            codeSpans += "<code>${escapeHtml(match.groupValues[1])}</code>"
            "\u0001$index\u0002"
        }
        var rendered = escapeHtml(tokenized)
        rendered = strongAsteriskPattern.replace(rendered, "<strong>$1</strong>")
        rendered = strongUnderscorePattern.replace(rendered, "<strong>$1</strong>")
        rendered = strikePattern.replace(rendered, "<strike>$1</strike>")
        rendered = emphasisAsteriskPattern.replace(rendered, "<em>$1</em>")
        rendered = emphasisUnderscorePattern.replace(rendered, "<em>$1</em>")
        codeSpans.forEachIndexed { index, code ->
            rendered = rendered.replace("\u0001$index\u0002", code)
        }
        return rendered
    }

    private fun escapeHtml(value: String): String = buildString(value.length) {
        value.forEach { character ->
            append(
                when (character) {
                    '&' -> "&amp;"
                    '<' -> "&lt;"
                    '>' -> "&gt;"
                    '"' -> "&quot;"
                    '\'' -> "&#39;"
                    else -> character
                },
            )
        }
    }

    private enum class ListKind {
        ORDERED,
        UNORDERED,
    }
}
