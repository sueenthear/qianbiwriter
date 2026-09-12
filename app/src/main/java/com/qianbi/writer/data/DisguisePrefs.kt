package com.qianbi.writer.data

import android.content.Context

/**
 * 伪装模式设置。
 *
 * 开启后，应用启动先显示一个 AI 聊天界面（[com.qianbi.writer.ui.ChatScreen]），
 * 只有在里面输入正确口令才会进入书架；输入错误会显示「API 报错」，看起来就像这个
 * 聊天应用本身坏了/密钥失效，而不是一个被锁住的应用。
 */
object DisguisePrefs {

    private const val PREFS = "novel-disguise"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_PASSWORD = "password"

    const val DEFAULT_PASSWORD = "qianbi2026"

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun password(context: Context): String =
        prefs(context).getString(KEY_PASSWORD, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_PASSWORD

    fun setPassword(context: Context, value: String) {
        prefs(context).edit().putString(KEY_PASSWORD, value.trim()).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
