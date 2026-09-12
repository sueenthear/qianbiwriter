package com.qianbi.writer.data

import java.security.MessageDigest
import java.security.SecureRandom

private const val HEX = "0123456789abcdef"

/** 32 位小写十六进制字符串（MD5 的 16 字节 ×2 位）。 */
fun ByteArray.toHex32(): String {
    val sb = StringBuilder(size * 2)
    for (b in this) {
        val v = b.toInt() and 0xFF
        sb.append(HEX[v ushr 4])
        sb.append(HEX[v and 0x0F])
    }
    return sb.toString()
}

/** 稳定哈希：同样的输入永远得到同一个 32 位串。 */
fun md5Hex32(input: String): String =
    MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8)).toHex32()

/**
 * 生成一个全新的 32 位哈希 id，用作章节文件夹名。
 * 章节可能重名，所以名字必须是哈希；seed 里带上标题只是为了调试时人眼能对上。
 */
fun newHashId(seed: String = ""): String {
    val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
    return md5Hex32("$seed|${System.nanoTime()}|${salt.toHex32()}")
}

/** 把任意文本变成可用作文件夹名的短串（保留中文/字母/数字）。 */
fun slugify(raw: String, maxLen: Int = 40): String {
    val sb = StringBuilder()
    for (ch in raw.trim()) {
        when {
            ch.isLetterOrDigit() -> sb.append(ch)
            ch == '-' || ch == '_' || ch == ' ' -> sb.append('_')
            else -> sb.append('_')
        }
    }
    val cleaned = sb.toString().trim('_').replace(Regex("_{2,}"), "_")
    val limited = if (cleaned.length > maxLen) cleaned.substring(0, maxLen) else cleaned
    return limited.ifBlank { "book" }
}
