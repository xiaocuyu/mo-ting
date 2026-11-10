package com.chennan.moting

import com.chennan.moting.mobi.MobiParser
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * 使用真实的 MOBI 文件做集成校验。
 * 运行前设置环境变量 MOTING_TEST_MOBI 指向文件；未设置则跳过。
 */
class MobiParserTest {

    private fun sampleFile(): File {
        return File(System.getenv("MOTING_TEST_MOBI") ?: "")
    }

    @Test
    fun readsMetadataAndToc() {
        val file = sampleFile()
        assumeTrue("缺少测试用 MOBI 文件", file.exists())
        val info = MobiParser(file.readBytes()).info()
        println("title=${info.title} author=${info.author} chapters=${info.chapterCount}")
        println("toc first=${info.tocTitles.take(5)}")
        check(info.title.contains("绍宋")) { "标题解析错误: ${info.title}" }
        check(info.author == "榴弹怕水") { "作者解析错误: ${info.author}" }
        check(info.chapterCount > 100) { "章节数异常: ${info.chapterCount}" }
        check(info.cover != null && info.cover!!.size > 1000) { "封面解析失败" }
    }

    @Test
    fun decompressesFullText() {
        val file = sampleFile()
        assumeTrue("缺少测试用 MOBI 文件", file.exists())
        val book = MobiParser(file.readBytes()).open()
        println("chapters=${book.chapters.size} first=${book.chapters.first().title}")
        println("second=${book.chapters.getOrNull(1)?.title}")
        check(book.chapters.size > 100)
        val first = book.chapterHtml(1)
        check(first.contains("明道宫")) { "正文开头异常: ${first.take(200)}" }
        check(!first.contains('\uFFFD')) { "正文解码出现替换字符" }
        println("chapter1 length=${first.length} head=${first.take(120)}")
    }
}
