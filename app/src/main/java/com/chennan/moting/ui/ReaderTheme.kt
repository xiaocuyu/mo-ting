package com.chennan.moting.ui

/** 阅读器配色方案。 */
enum class ReaderTheme(
    val key: String,
    val background: Int,
    val text: Int,
    val secondary: Int,
    val accent: Int,
    val highlight: Int,
    val lightStatusBar: Boolean,
) {
    PAPER(
        key = "paper",
        background = 0xFFF6F0E4.toInt(),
        text = 0xFF2B2620.toInt(),
        secondary = 0xFF7C7164.toInt(),
        accent = 0xFFA8613C.toInt(),
        highlight = 0x66E8C87A,
        lightStatusBar = true,
    ),
    SEPIA(
        key = "sepia",
        background = 0xFFEFE1C4.toInt(),
        text = 0xFF3A2F22.toInt(),
        secondary = 0xFF7A6A54.toInt(),
        accent = 0xFF95552F.toInt(),
        highlight = 0x66D9B36A,
        lightStatusBar = true,
    ),
    NIGHT(
        key = "night",
        background = 0xFF121110.toInt(),
        text = 0xFFC7BEB0.toInt(),
        secondary = 0xFF7E756A.toInt(),
        accent = 0xFFC98A5E.toInt(),
        highlight = 0x40D08A5A,
        lightStatusBar = false,
    );

    companion object {
        fun fromKey(key: String?): ReaderTheme =
            entries.firstOrNull { it.key == key } ?: PAPER
    }
}
