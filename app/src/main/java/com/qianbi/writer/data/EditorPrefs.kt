package com.qianbi.writer.data

import android.content.Context

/** 写作界面的偏好，全局生效（目前只有正文字号）。 */
object EditorPrefs {

    private const val PREFS = "novel-editor"
    private const val KEY_FONT_SP = "font_size_sp"

    const val MIN_FONT_SP = 13
    const val MAX_FONT_SP = 30
    const val DEFAULT_FONT_SP = 17

    fun fontSize(context: Context): Int =
        prefs(context).getInt(KEY_FONT_SP, DEFAULT_FONT_SP).coerceIn(MIN_FONT_SP, MAX_FONT_SP)

    fun setFontSize(context: Context, sp: Int) {
        prefs(context).edit().putInt(KEY_FONT_SP, sp.coerceIn(MIN_FONT_SP, MAX_FONT_SP)).apply()
    }

    /** 正文行高：跟着字号走，保持阅读节奏。 */
    fun lineHeightSp(fontSp: Int): Int = (fontSp * 1.75f).toInt()

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
