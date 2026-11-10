package com.chennan.moting.data

import android.content.Context
import com.chennan.moting.ui.ReaderTheme

/** 阅读偏好设置（字号、行距、主题、语速、定时）。 */
object ReaderPrefs {

    private const val FILE = "reader_prefs"
    private const val KEY_THEME = "theme"
    private const val KEY_FONT_SIZE = "font_size"
    private const val KEY_LINE_SPACING = "line_spacing"
    private const val KEY_TTS_SPEED = "tts_speed"
    private const val KEY_AUTO_PAGE = "auto_page"

    const val MIN_FONT_SIZE = 14
    const val MAX_FONT_SIZE = 28
    const val DEFAULT_FONT_SIZE = 19
    const val MIN_LINE_SPACING = 1.2f
    const val MAX_LINE_SPACING = 2.0f
    const val DEFAULT_LINE_SPACING = 1.5f

    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun theme(context: Context): ReaderTheme =
        ReaderTheme.fromKey(prefs(context).getString(KEY_THEME, null))

    fun setTheme(context: Context, theme: ReaderTheme) {
        prefs(context).edit().putString(KEY_THEME, theme.key).apply()
    }

    fun fontSize(context: Context): Int =
        prefs(context).getInt(KEY_FONT_SIZE, DEFAULT_FONT_SIZE).coerceIn(MIN_FONT_SIZE, MAX_FONT_SIZE)

    fun setFontSize(context: Context, size: Int) {
        prefs(context).edit().putInt(KEY_FONT_SIZE, size.coerceIn(MIN_FONT_SIZE, MAX_FONT_SIZE)).apply()
    }

    fun lineSpacing(context: Context): Float =
        prefs(context).getFloat(KEY_LINE_SPACING, DEFAULT_LINE_SPACING)
            .coerceIn(MIN_LINE_SPACING, MAX_LINE_SPACING)

    fun setLineSpacing(context: Context, value: Float) {
        prefs(context).edit().putFloat(
            KEY_LINE_SPACING,
            value.coerceIn(MIN_LINE_SPACING, MAX_LINE_SPACING),
        ).apply()
    }

    fun ttsSpeed(context: Context): Float =
        prefs(context).getFloat(KEY_TTS_SPEED, 1.0f).coerceIn(0.5f, 2.5f)

    fun setTtsSpeed(context: Context, value: Float) {
        prefs(context).edit().putFloat(KEY_TTS_SPEED, value.coerceIn(0.5f, 2.5f)).apply()
    }

    fun autoPage(context: Context): Boolean = prefs(context).getBoolean(KEY_AUTO_PAGE, true)

    fun setAutoPage(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_PAGE, value).apply()
    }

    val speedOptions = floatArrayOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
}
