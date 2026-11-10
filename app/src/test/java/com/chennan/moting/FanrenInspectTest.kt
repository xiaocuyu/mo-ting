package com.chennan.moting

import com.chennan.moting.mobi.MobiParser
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class FanrenInspectTest {
    @Test
    fun inspect() {
        val f = File(System.getenv("MOTING_TEST_MOBI") ?: "")
        assumeTrue(f.exists())
        val info = MobiParser(f.readBytes()).info()
        println("INFO chapters=${info.chapterCount} compression=${info.compression}")
        println("TOC first=${info.tocTitles.take(15)}")
        val book = MobiParser(f.readBytes()).open()
        println("OPEN chapters=${book.chapters.size}")
        println("CHAPTERS first8=${book.chapters.take(8).map { it.title }}")
        println("CHAPTER1 html=${book.chapterHtml(1).take(160)}")
        println("CHAPTER0 html=${book.chapterHtml(0).take(120)}")
    }
}
