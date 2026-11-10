package com.chennan.moting.text

/** 行内样式类型。 */
enum class SpanStyle { BOLD, ITALIC, UNDERLINE }

/** 文本片段上的行内样式区间（相对于整章文本）。 */
data class TextSpan(val start: Int, val end: Int, val style: SpanStyle)

/**
 * 章节中的一个段落（相对于整章文本的区间）。
 * heading 为 true 表示标题；level 对应 h1..h6（无标签时为 1）。
 */
data class TextBlock(
    val start: Int,
    val end: Int,
    val heading: Boolean = false,
    val centered: Boolean = false,
    val level: Int = 1,
)

class ParsedChapter(
    val text: String,
    val blocks: List<TextBlock>,
    val spans: List<TextSpan>,
)

/**
 * 把 MOBI 内的 HTML 片段转换为适合阅读与 TTS 的纯文本模型。
 *
 * 设计目标：
 *  - 不依赖 Android API，可在 JVM 单元测试中直接验证；
 *  - 去掉样式标签但保留粗体/斜体等语义；
 *  - 段落之间用换行分隔，中文段落自动缩进两格；
 *  - 输出段落区间，供 TTS 逐段朗读与高亮定位。
 */
object HtmlToText {

    private val blockTags = setOf(
        "p", "div", "br", "hr", "h1", "h2", "h3", "h4", "h5", "h6",
        "li", "ul", "ol", "blockquote", "pre", "section", "article",
        "header", "footer", "table", "tr", "td", "th", "dt", "dd", "dl",
        "figcaption", "center", "address", "body", "html",
    )

    private val inlineStyles = mapOf(
        "b" to SpanStyle.BOLD,
        "strong" to SpanStyle.BOLD,
        "i" to SpanStyle.ITALIC,
        "em" to SpanStyle.ITALIC,
        "u" to SpanStyle.UNDERLINE,
    )

    private val entities = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'",
        "nbsp" to "\u00A0", "mdash" to "—", "ndash" to "–", "hellip" to "…",
        "ldquo" to "“", "rdquo" to "”", "lsquo" to "‘", "rsquo" to "’",
        "middot" to "·", "bull" to "•", "copy" to "©", "reg" to "®",
        "emsp" to "\u3000", "ensp" to " ", "thinsp" to " ", "shy" to "",
        "laquo" to "«", "raquo" to "»", "deg" to "°", "times" to "×",
    )

    private class StyleFrame(val style: SpanStyle, val start: Int)

    private class PendingSpan(val start: Int, val end: Int, val style: SpanStyle)

    fun convert(html: String): ParsedChapter {
        val cleaned = stripNonContent(html)
        val cjk = looksChinese(cleaned)
        val out = StringBuilder(cleaned.length / 2)
        val blocks = ArrayList<TextBlock>()
        val spans = ArrayList<TextSpan>()
        val pendingSpans = ArrayList<PendingSpan>()
        val styleStack = ArrayList<StyleFrame>()
        val paragraph = StringBuilder()
        val paragraphSpans = ArrayList<PendingSpan>()

        var paragraphCentered = false
        var paragraphHeadingLevel = 0
        var indent = 0

        fun resetParagraph() {
            paragraph.setLength(0)
            paragraphSpans.clear()
            styleStack.clear()
            paragraphCentered = false
            paragraphHeadingLevel = 0
            indent = 0
        }

        fun flushParagraph() {
            var text = paragraph.toString()
            var leading = 0
            var trailing = text.length
            while (leading < trailing && (text[leading] == ' ' || text[leading] == '\u3000')) leading++
            while (trailing > leading && (text[trailing - 1] == ' ' || text[trailing - 1] == '\u3000')) trailing--
            text = text.substring(leading, trailing)
            if (text.isEmpty()) {
                resetParagraph()
                return
            }

            val alreadyIndented = text.startsWith("\u3000")
            val useIndent = cjk && paragraphHeadingLevel == 0 && !alreadyIndented
            val indentText = if (useIndent) "\u3000\u3000" else ""
            val blockStart = out.length
            if (indentText.isNotEmpty()) out.append(indentText)
            out.append(text)
            val blockEnd = out.length

            // 标题：显式 h 标签，或居中且整段加粗的短句
            var heading = paragraphHeadingLevel > 0
            if (!heading && paragraphCentered && blockEnd - blockStart <= 60) {
                val allBold = paragraphSpans.isNotEmpty() &&
                    paragraphSpans.any { it.style == SpanStyle.BOLD && it.end - it.start >= text.length - 2 }
                if (allBold) heading = true
            }

            blocks.add(
                TextBlock(
                    start = blockStart,
                    end = blockEnd,
                    heading = heading,
                    centered = paragraphCentered,
                    level = if (paragraphHeadingLevel > 0) paragraphHeadingLevel else 1,
                )
            )

            for (p in paragraphSpans) {
                val s = blockStart + indentText.length + (p.start - leading)
                val e = blockStart + indentText.length + (p.end - leading)
                if (s >= 0 && s in out.indices && e <= out.length && s < e) {
                    spans.add(TextSpan(s, e, p.style))
                }
            }

            out.append('\n')
            if (heading) out.append('\n')

            resetParagraph()
        }

        var i = 0
        val n = cleaned.length
        while (i < n) {
            val c = cleaned[i]
            if (c == '<') {
                val end = findTagEnd(cleaned, i)
                if (end < 0) {
                    // 不是合法标签，按文本处理
                    appendTextChar(paragraph, '<')
                    i++
                    continue
                }
                val rawTag = cleaned.substring(i + 1, end)
                i = end + 1
                handleTag(
                    rawTag,
                    paragraph,
                    styleStack,
                    paragraphSpans,
                    onBlockBoundary = { flushParagraph() },
                    onHeading = { level -> paragraphHeadingLevel = maxOf(paragraphHeadingLevel, level) },
                    onCentered = { paragraphCentered = true },
                    onAppendText = { text -> appendText(paragraph, text) },
                )
            } else if (c == '&') {
                val semi = cleaned.indexOf(';', i + 1)
                if (semi in (i + 1)..(i + 12)) {
                    val name = cleaned.substring(i + 1, semi)
                    val decoded = decodeEntity(name)
                    if (decoded != null) {
                        if (paragraph.isEmpty() && decoded.isBlank()) {
                            // 行首空白丢弃
                        } else {
                            appendText(paragraph, decoded)
                        }
                        i = semi + 1
                        continue
                    }
                }
                appendTextChar(paragraph, '&')
                i++
            } else if (c == '\r' || c == '\n' || c == '\t') {
                if (!cjk) appendTextChar(paragraph, ' ')
                i++
            } else {
                appendTextChar(paragraph, c)
                i++
            }
        }
        flushParagraph()

        // 去掉末尾多余的换行
        var length = out.length
        while (length > 0 && out[length - 1] == '\n') length--
        val text = out.substring(0, length)

        return ParsedChapter(
            text = text,
            blocks = blocks.filter { it.start < text.length },
            spans = spans.filter { it.end <= text.length },
        )
    }

    private fun handleTag(
        rawTag: String,
        paragraph: StringBuilder,
        styleStack: MutableList<StyleFrame>,
        paragraphSpans: MutableList<PendingSpan>,
        onBlockBoundary: () -> Unit,
        onHeading: (Int) -> Unit,
        onCentered: () -> Unit,
        onAppendText: (String) -> Unit,
    ) {
        val trimmed = rawTag.trim()
        if (trimmed.isEmpty()) return
        if (trimmed.startsWith("!--") || trimmed.startsWith("!")) return
        val closing = trimmed.startsWith("/")
        val body = if (closing) trimmed.substring(1).trim() else trimmed
        var nameEnd = 0
        while (nameEnd < body.length &&
            (body[nameEnd].isLetterOrDigit() || body[nameEnd] == ':' || body[nameEnd] == '-')
        ) {
            nameEnd++
        }
        if (nameEnd == 0) return
        val name = body.substring(0, nameEnd).lowercase()
        val attrs = body.substring(nameEnd)

        if (!closing) {
            when (name) {
                "head", "style", "script" -> {
                    // 这些标签的内容由调用方预处理移除；这里兜底跳过标签本身
                    return
                }
                "img" -> {
                    val alt = Regex("alt\\s*=\\s*[\"']([^\"']*)[\"']").find(attrs)?.groupValues?.get(1)
                    if (!alt.isNullOrBlank()) onAppendText(alt)
                    return
                }
                "br", "hr" -> {
                    onBlockBoundary()
                    return
                }
            }
        }

        if (name in blockTags) {
            if (closing) {
                onBlockBoundary()
            } else {
                onBlockBoundary()
                if (name.length == 2 && name[0] == 'h' && name[1] in '1'..'6') {
                    onHeading(name[1] - '0')
                }
                if (name == "li") onAppendText("· ")
                if (attrs.contains("center", ignoreCase = true) &&
                    (attrs.contains("align", ignoreCase = true) || attrs.contains("text-align", ignoreCase = true))
                ) {
                    onCentered()
                }
            }
            return
        }

        val style = inlineStyles[name] ?: return
        if (!closing) {
            styleStack.add(StyleFrame(style, paragraph.length))
        } else {
            var index = styleStack.size - 1
            while (index >= 0 && styleStack[index].style != style) index--
            if (index >= 0) {
                val frame = styleStack.removeAt(index)
                if (paragraph.length > frame.start) {
                    paragraphSpans.add(PendingSpan(frame.start, paragraph.length, style))
                }
            }
        }
    }

    private fun stripNonContent(html: String): String {
        var s = html
        s = s.replace(Regex("(?is)<!--.*?-->"), "")
        s = s.replace(Regex("(?is)<(head|style|script)\\b[^>]*>.*?</\\1\\s*>"), "")
        s = s.replace(Regex("(?is)<head\\b[^>]*>.*?(?=<body\\b|</html>|$)"), "")
        s = s.replace(Regex("(?is)<style\\b[^>]*>.*?(?=</html>|$)"), "")
        s = s.replace(Regex("(?is)<script\\b[^>]*>.*?(?=</html>|$)"), "")
        return s
    }

    private fun findTagEnd(html: String, start: Int): Int {
        var i = start + 1
        var quote: Char? = null
        while (i < html.length) {
            val c = html[i]
            if (quote != null) {
                if (c == quote) quote = null
            } else {
                when (c) {
                    '"', '\'' -> quote = c
                    '>' -> return i
                    '<' -> return -1
                }
            }
            i++
        }
        return -1
    }

    private fun decodeEntity(name: String): String? {
        val key = name.lowercase()
        if (key.startsWith("#")) {
            return try {
                val code = if (key.startsWith("#x")) {
                    key.substring(2).toInt(16)
                } else {
                    key.substring(1).toInt()
                }
                if (code in 1..0x10FFFF) String(Character.toChars(code)) else null
            } catch (e: NumberFormatException) {
                null
            }
        }
        return entities[key]
    }

    private fun appendText(sb: StringBuilder, text: String) {
        for (c in text) {
            when (c) {
                '\r', '\n', '\t' -> appendTextChar(sb, ' ')
                else -> appendTextChar(sb, c)
            }
        }
    }

    private fun appendTextChar(sb: StringBuilder, c: Char) {
        if (c == ' ' && (sb.isEmpty() || sb[sb.length - 1] == ' ')) return
        sb.append(c)
    }

    /** 粗略判断是否为中文内容，用于决定是否自动缩进。 */
    fun looksChinese(html: String): Boolean {
        var cjk = 0
        var latin = 0
        var inTag = false
        var i = 0
        while (i < html.length) {
            val c = html[i]
            if (c == '<') {
                inTag = true
            } else if (c == '>') {
                inTag = false
            } else if (!inTag) {
                when {
                    c.code in 0x4E00..0x9FFF || c.code in 0x3400..0x4DBF -> cjk++
                    c.isLetter() && c.code < 0x250 -> latin++
                }
                if (cjk > 120) return true
                if (latin > 400 && cjk == 0) return false
            }
            i++
        }
        return cjk >= 6 && cjk * 2 >= latin
    }
}
