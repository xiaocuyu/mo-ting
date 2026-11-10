package com.chennan.moting

import com.chennan.moting.mobi.MobiParser
import com.chennan.moting.text.HtmlToText
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class HtmlToTextTest {

    @Test
    fun convertsBasicHtml() {
        val html = """
            <html><head><title>标题</title><style>p{color:red}</style></head>
            <body>
            <p>第一段内容。</p>
            <p align="center"><font size="4"><b>第二章 测试</b></font></p>
            <p>第二段 &amp; 内容，<i>斜体</i>。</p>
            <p>　　</p>
            </body></html>
        """.trimIndent()

        val chapter = HtmlToText.convert(html)
        println("TEXT>>>\n${chapter.text}\n<<<TEXT")
        println("BLOCKS>>> ${chapter.blocks}")
        println("SPANS>>> ${chapter.spans}")

        check(chapter.text.contains("第一段内容")) { "缺少正文" }
        check(!chapter.text.contains("color:red")) { "样式内容未移除" }
        check(chapter.text.contains("& 内容")) { "实体未解码" }
        check(chapter.blocks.any { it.heading && it.centered }) { "未识别居中标题" }
        check(chapter.text.contains("　　第一段内容")) { "中文段落未缩进" }
        check(chapter.spans.any { it.style.name == "ITALIC" }) { "斜体样式丢失" }
    }

    @Test
    fun convertsRealChapter() {
        val path = System.getenv("MOTING_TEST_MOBI") ?: ""
        val file = File(path)
        assumeTrue("缺少测试用 MOBI 文件", file.exists())
        val book = MobiParser(file.readBytes()).open()
        val chapter = HtmlToText.convert(book.chapterHtml(1))
        println("chars=${chapter.text.length} blocks=${chapter.blocks.size} spans=${chapter.spans.size}")
        println("HEAD>>>\n${chapter.text.take(300)}\n<<<")
        check(chapter.text.length > 1000)
        check(chapter.text.contains("第一章 明道宫"))
        check(!chapter.text.contains("<p")) { "HTML 标签残留" }
        check(chapter.blocks.size > 10)
        check(chapter.blocks.first().heading) { "章节标题未被识别" }
    }
}
