package com.qianbi.writer.data

import android.content.Context
import java.security.SecureRandom

/**
 * Web 协作（PC 端用浏览器编辑）的全局偏好。
 *
 * 「哪本书允许协作」是每本书自己的属性，存在该书的 book.info 里；
 * 这里只管服务器本身：总开关 / 端口 / 访问码。
 */
object WebPrefs {

    private const val PREFS = "novel-web"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_PORT = "port"
    private const val KEY_CODE = "access_code"

    /** 默认端口，避开 8080 这类常被 PC 上其他程序占用的端口。 */
    const val DEFAULT_PORT = 8765
    const val MIN_PORT = 1024
    const val MAX_PORT = 65535
    const val CODE_LENGTH = 8

    /** 访问码字母表：去掉 l / 1 / o / 0 这些照着屏幕手抄容易看错的字符。 */
    private const val ALPHABET = "abcdefghijkmnpqrstuvwxyz23456789"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun port(context: Context): Int =
        prefs(context).getInt(KEY_PORT, DEFAULT_PORT).coerceIn(MIN_PORT, MAX_PORT)

    fun setPort(context: Context, port: Int) {
        prefs(context).edit().putInt(KEY_PORT, port.coerceIn(MIN_PORT, MAX_PORT)).apply()
    }

    /** 访问码：第一次读取时自动生成一个并落盘，之后保持不变，直到用户自己改。 */
    fun accessCode(context: Context): String {
        val store = prefs(context)
        val stored = store.getString(KEY_CODE, null)
        if (!stored.isNullOrBlank()) return stored
        val fresh = newAccessCode()
        store.edit().putString(KEY_CODE, fresh).apply()
        return fresh
    }

    /** 自定义访问码：洗掉空白和 URL 里的麻烦字符，返回实际生效的值。 */
    fun setAccessCode(context: Context, raw: String): String {
        val clean = sanitizeCode(raw)
        prefs(context).edit().putString(KEY_CODE, clean).apply()
        return clean
    }

    fun newAccessCode(): String {
        val random = SecureRandom()
        return buildString(CODE_LENGTH) {
            repeat(CODE_LENGTH) { append(ALPHABET[random.nextInt(ALPHABET.length)]) }
        }
    }

    /**
     * 只保留 ASCII 字母 / 数字 / `-_.`，其余丢掉。
     * 收窄字符集是有意的：访问码要出现在手机上显示的 URL 里让人照着敲，
     * 汉字或空格会让这段 URL 又长又难念。全空时回退成随机码。
     */
    fun sanitizeCode(raw: String): String {
        val cleaned = raw.trim().filter {
            (it in 'a'..'z') || (it in 'A'..'Z') || (it in '0'..'9') ||
                it == '-' || it == '_' || it == '.'
        }
        return cleaned.ifBlank { newAccessCode() }
    }
}
