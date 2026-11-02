package com.warden.blocker.ui.owner

/**
 * Just enough Markdown for docs/DEVICE_OWNER.md shown inside the app: headings, paragraphs,
 * lists, quotes, code blocks, tables and rules. Inline marks (`**bold**`, `*italic*`,
 * `` `code` ``, `<link>`) stay in the text and are styled when drawn. Pure, so it's tested.
 */
object GuideMarkdown {

    sealed interface Block {
        data class Heading(val level: Int, val text: String) : Block
        data class Paragraph(val text: String) : Block
        /** [marker] is "•" or "1." etc.; [indent] 0 for top level, 1 for a nested item. */
        data class ListItem(val marker: String, val text: String, val indent: Int) : Block
        data class Quote(val text: String) : Block
        data class Code(val text: String) : Block
        data class Table(val header: List<String>, val rows: List<List<String>>) : Block
        data object Rule : Block
    }

    private val ordered = Regex("""^(\s*)(\d+)\.\s+(.*)$""")
    private val bullet = Regex("""^(\s*)[-*]\s+(.*)$""")

    fun parse(markdown: String): List<Block> {
        val blocks = mutableListOf<Block>()
        val lines = markdown.replace("\r\n", "\n").lines()
        var i = 0
        val paragraph = StringBuilder()
        fun flush() {
            if (paragraph.isNotBlank()) blocks += Block.Paragraph(paragraph.toString().trim())
            paragraph.clear()
        }
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()
            when {
                trimmed.startsWith("```") -> {
                    flush()
                    val code = mutableListOf<String>()
                    i++
                    while (i < lines.size && !lines[i].trim().startsWith("```")) code += lines[i++]
                    blocks += Block.Code(code.joinToString("\n"))
                }
                trimmed.isEmpty() -> flush()
                trimmed.startsWith("#") -> {
                    flush()
                    val level = trimmed.takeWhile { it == '#' }.length
                    blocks += Block.Heading(level, trimmed.drop(level).trim())
                }
                trimmed == "---" -> { flush(); blocks += Block.Rule }
                trimmed.startsWith(">") -> {
                    flush()
                    val quote = mutableListOf<String>()
                    while (i < lines.size && lines[i].trim().startsWith(">")) quote += lines[i++].trim().removePrefix(">").trim()
                    blocks += Block.Quote(quote.joinToString(" "))
                    continue
                }
                trimmed.startsWith("|") -> {
                    flush()
                    val rows = mutableListOf<List<String>>()
                    while (i < lines.size && lines[i].trim().startsWith("|")) {
                        val cells = lines[i].trim().trim('|').split("|").map { it.trim() }
                        // The |---|---| line under the header.
                        if (cells.any { it.isNotEmpty() } && !cells.all { c -> c.isNotEmpty() && c.all { it == '-' || it == ':' } }) rows += cells
                        i++
                    }
                    if (rows.isNotEmpty()) blocks += Block.Table(rows.first(), rows.drop(1))
                    continue
                }
                ordered.matches(line) || bullet.matches(line) -> {
                    flush()
                    val o = ordered.find(line)
                    val (spaces, marker, text) = if (o != null) {
                        Triple(o.groupValues[1], o.groupValues[2] + ".", o.groupValues[3])
                    } else {
                        val b = bullet.find(line)!!
                        Triple(b.groupValues[1], "•", b.groupValues[2])
                    }
                    // Continuation lines (indented, not a new item) belong to this item.
                    val item = StringBuilder(text)
                    while (i + 1 < lines.size) {
                        val nextLine = lines[i + 1]
                        if (nextLine.isBlank() || !nextLine.startsWith(" ") || ordered.matches(nextLine) || bullet.matches(nextLine)) break
                        item.append(' ').append(nextLine.trim())
                        i++
                    }
                    blocks += Block.ListItem(marker, item.toString(), if (spaces.length >= 2) 1 else 0)
                }
                else -> paragraph.append(if (paragraph.isEmpty()) trimmed else " $trimmed")
            }
            i++
        }
        flush()
        return blocks
    }

    /** A span of inline text and how it's marked. */
    data class Span(val text: String, val bold: Boolean = false, val italic: Boolean = false, val code: Boolean = false)

    /** Splits `**bold**`, `*italic*`, `` `code` `` and `<https://…>` (shown as plain text). */
    fun spans(text: String): List<Span> {
        val out = mutableListOf<Span>()
        val plain = StringBuilder()
        fun plainOut() { if (plain.isNotEmpty()) { out += Span(plain.toString()); plain.clear() } }
        var i = 0
        while (i < text.length) {
            val rest = text.substring(i)
            val mark = when {
                rest.startsWith("**") -> "**"
                rest.startsWith("`") -> "`"
                rest.startsWith("*") -> "*"
                rest.startsWith("<http") -> "<"
                else -> null
            }
            val close = when (mark) { "<" -> ">"; null -> null; else -> mark }
            val end = if (mark != null && close != null) text.indexOf(close, i + mark.length) else -1
            if (mark == null || end < 0) { plain.append(text[i]); i++; continue }
            plainOut()
            val inner = text.substring(i + mark.length, end)
            out += when (mark) {
                "**" -> Span(inner, bold = true)
                "*" -> Span(inner, italic = true)
                "`" -> Span(inner, code = true)
                else -> Span(inner)
            }
            i = end + close!!.length
        }
        plainOut()
        return out
    }
}
