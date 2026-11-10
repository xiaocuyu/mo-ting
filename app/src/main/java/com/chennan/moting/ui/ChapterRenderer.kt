package com.chennan.moting.ui

import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.text.style.AlignmentSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import com.chennan.moting.text.ParsedChapter
import com.chennan.moting.text.SpanStyle

/** 把解析后的章节模型转成 Android Spannable。 */
object ChapterRenderer {

    fun render(chapter: ParsedChapter): SpannableString {
        val spannable = SpannableString(chapter.text)

        for (span in chapter.spans) {
            if (span.start < 0 || span.end > spannable.length || span.start >= span.end) continue
            when (span.style) {
                SpanStyle.BOLD -> spannable.setSpan(
                    StyleSpan(Typeface.BOLD),
                    span.start,
                    span.end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )

                SpanStyle.ITALIC -> spannable.setSpan(
                    StyleSpan(Typeface.ITALIC),
                    span.start,
                    span.end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )

                SpanStyle.UNDERLINE -> spannable.setSpan(
                    UnderlineSpan(),
                    span.start,
                    span.end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
        }

        for (block in chapter.blocks) {
            if (!block.heading) continue
            if (block.start < 0 || block.end > spannable.length || block.start >= block.end) continue
            val scale = if (block.level <= 1) 1.3f else 1.16f
            spannable.setSpan(
                RelativeSizeSpan(scale),
                block.start,
                block.end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            spannable.setSpan(
                StyleSpan(Typeface.BOLD),
                block.start,
                block.end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            if (block.centered) {
                spannable.setSpan(
                    AlignmentSpan.Standard(Layout.Alignment.ALIGN_CENTER),
                    block.start,
                    block.end.coerceAtMost(spannable.length),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
        }
        return spannable
    }
}
