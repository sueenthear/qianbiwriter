package com.qianbi.writer.data

import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher

class NeedPasswordException : IOException("需要密码")
class WrongPasswordException : IOException("密码错误")
class InvalidPackageException(message: String) : IOException(message)

/** .book 包的明文头部。 */
data class PackageHeader(
    val version: Int,
    val encrypted: Boolean,
    val iterations: Int,
    val salt: ByteArray,
    val verifier: ByteArray,
) {
    // data class 里有 ByteArray，equals/hashCode 需手写才正确
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}

/**
 * .book 包的读写（**双向解析**）。
 *
 * 包布局：
 * ```
 *   magic       8B  "NBOOKPKG"
 *   version     1B
 *   flags       1B  bit0 = 是否口令加密
 *   iterations  4B  PBKDF2 迭代次数（明文包为 0）
 *   salt       16B  随机盐（明文包为全 0）
 *   verifier   32B  HMAC(macKey,"NBOOK-VERIFY-V1")，导入时先验密码（明文包为全 0）
 *   ── payload ──
 *   明文包：zip 原始字节
 *   加密包：AES-256-CTR(zip 字节) + 32B HMAC(macKey, 密文)
 * ```
 * payload 就是一个标准 zip，内部结构与书本文件夹一字不差：
 * `book.info` / `cover.jpg` / `glossary.json` / `book/<32位哈希>/this.info` …
 *
 * 导入/导出都只依赖 [FsEntry]，所以书架放在应用私有目录或用户自选的外部文件夹都能用。
 */
object BookPackage {

    const val EXTENSION = "book"
    const val MIME = "application/octet-stream"

    private val MAGIC = "NBOOKPKG".toByteArray(Charsets.US_ASCII)
    private const val VERSION = 1
    private const val FLAG_ENCRYPTED = 0x01
    private const val MAX_UNPACKED_BYTES = 512L * 1024 * 1024

    // ─────────────────────────── 导出 ───────────────────────────

    /** 把一本书的文件夹打成 .book 包写到 [out]。password 为空表示不加密。 */
    fun export(bookDir: FsEntry, out: OutputStream, password: String?) {
        val zipBytes = zipDirectory(bookDir)
        val encrypted = !password.isNullOrEmpty()
        val header = ByteArrayOutputStream()
        header.write(MAGIC)
        header.write(VERSION)
        header.write(if (encrypted) FLAG_ENCRYPTED else 0)
        if (encrypted) {
            val salt = CryptoUtil.newSalt()
            val keys = CryptoUtil.deriveKeys(password!!.toCharArray(), salt, CryptoUtil.DEFAULT_ITERATIONS)
            writeInt(header, CryptoUtil.DEFAULT_ITERATIONS)
            header.write(salt)
            header.write(CryptoUtil.verifier(keys))
            out.write(header.toByteArray())

            val body = CryptoUtil.ctr(Cipher.ENCRYPT_MODE, keys.enc).doFinal(zipBytes)
            out.write(body)
            // 包尾 HMAC：导入时可校验完整性与是否被截断
            out.write(CryptoUtil.hmac(keys.mac, body))
        } else {
            writeInt(header, 0)
            header.write(ByteArray(CryptoUtil.SALT_SIZE))
            header.write(ByteArray(CryptoUtil.VERIFIER_SIZE))
            out.write(header.toByteArray())
            out.write(zipBytes)
        }
        out.flush()
    }

    // ─────────────────────────── 头部 / 密码 ───────────────────────────

    /** 只读头部，用来判断这个包是否需要密码（读完后流指针停在 payload 起点）。 */
    fun readHeader(input: InputStream): PackageHeader {
        val magic = readExactly(input, MAGIC.size)
        if (!magic.contentEquals(MAGIC)) throw InvalidPackageException("不是 .book 包（魔数不匹配）")
        val version = input.read()
        val flags = input.read()
        if (version < 0 || flags < 0) throw InvalidPackageException("包头部不完整")
        if (version != VERSION) throw InvalidPackageException("不支持的包版本：$version")
        val iterations = readInt(input)
        val salt = readExactly(input, CryptoUtil.SALT_SIZE)
        val verifier = readExactly(input, CryptoUtil.VERIFIER_SIZE)
        return PackageHeader(version, flags and FLAG_ENCRYPTED != 0, iterations, salt, verifier)
    }

    /** 用头部里的 verifier 快速校验口令，正确返回 true。 */
    fun checkPassword(header: PackageHeader, password: String): Boolean {
        val keys = CryptoUtil.deriveKeys(password.toCharArray(), header.salt, header.iterations)
        return CryptoUtil.matches(keys, header.verifier)
    }

    // ─────────────────────────── 导入 ───────────────────────────

    /**
     * 把 .book 包导入成一本新书，返回新书的引用。
     * - 包需要密码而没有给 → [NeedPasswordException]
     * - 密码不对 → [WrongPasswordException]（读完头部就能判定，不用等整包解完）
     * - 包损坏/被篡改/不是合法书本 → [InvalidPackageException]
     * - 已存在同 id 的书 → 作为副本导入（重新分配 id，绝不覆盖已有书）
     */
    fun import(store: BookStore, input: InputStream, password: String?): BookRef {
        val header = readHeader(input)
        val payload: ByteArray = if (header.encrypted) {
            if (password.isNullOrEmpty()) throw NeedPasswordException()
            val keys = CryptoUtil.deriveKeys(password.toCharArray(), header.salt, header.iterations)
            if (!CryptoUtil.matches(keys, header.verifier)) throw WrongPasswordException()
            val raw = input.readBytes()
            if (raw.size <= CryptoUtil.MAC_SIZE) throw InvalidPackageException("包内容不完整")
            val body = raw.copyOfRange(0, raw.size - CryptoUtil.MAC_SIZE)
            val tag = raw.copyOfRange(raw.size - CryptoUtil.MAC_SIZE, raw.size)
            if (!MessageDigest.isEqual(CryptoUtil.hmac(keys.mac, body), tag)) {
                throw InvalidPackageException("包校验失败：内容损坏或被篡改")
            }
            CryptoUtil.ctr(Cipher.DECRYPT_MODE, keys.enc).doFinal(body)
        } else {
            input.readBytes()
        }

        val entries = unzipToMemory(ByteArrayInputStream(payload))
        // 允许包里多一层目录（例如手工打成 zip 时带上了书名文件夹）
        val infoPath = entries.keys.firstOrNull { it == FILE_BOOK_INFO }
            ?: entries.keys.firstOrNull { it.endsWith("/$FILE_BOOK_INFO") }
            ?: throw InvalidPackageException("包里没有 book.info，不是有效的书本")
        val prefix = infoPath.removeSuffix(FILE_BOOK_INFO)

        val parsed = try {
            BookMeta.fromJson(
                JSONObject(String(entries.getValue(infoPath), Charsets.UTF_8)),
                md5Hex32(prefix.ifBlank { "imported" }),
            )
        } catch (e: Exception) {
            throw InvalidPackageException("包里的 book.info 无法解析")
        }

        val now = System.currentTimeMillis()
        val idTaken = store.listBooks().any { it.meta.id == parsed.id }
        val finalId = if (idTaken) newHashId(parsed.title) else parsed.id
        val finalMeta = parsed.copy(id = finalId, modifiedAt = now)

        val target = store.createBookDir(finalMeta.title, finalId)
        for ((path, bytes) in entries) {
            if (path == infoPath || !path.startsWith(prefix)) continue
            val rel = path.removePrefix(prefix)
            if (rel.isBlank()) continue
            val parts = rel.split('/').filter { it.isNotBlank() && it != "." }
            if (parts.isEmpty()) continue
            var dir = target
            for (i in 0 until parts.size - 1) {
                val next = dir.child(parts[i])
                next.mkdirs()
                dir = next
            }
            val file = dir.child(parts.last())
            if (!file.exists || !file.isDirectory) file.writeBytes(bytes)
        }
        target.child(FILE_BOOK_INFO).writeText(finalMeta.toJson().toString(2))
        return BookRef(target, finalMeta)
    }

    // ─────────────────────────── 内部工具 ───────────────────────────

    private fun zipDirectory(dir: FsEntry): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            fun walk(entry: FsEntry, prefix: String) {
                entry.list().sortedBy { it.name }.forEach { child ->
                    val rel = if (prefix.isEmpty()) child.name else "$prefix/${child.name}"
                    when {
                        child.isDirectory -> walk(child, rel)
                        child.name.endsWith(".tmp") -> Unit
                        else -> {
                            zos.putNextEntry(ZipEntry(rel))
                            zos.write(child.readBytes())
                            zos.closeEntry()
                        }
                    }
                }
            }
            walk(dir, "")
        }
        return out.toByteArray()
    }

    private fun unzipToMemory(input: InputStream): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        var total = 0L
        ZipInputStream(input).use { zis ->
            var entry: ZipEntry? = zis.nextEntry
            while (entry != null) {
                val name = entry.name.replace('\\', '/')
                if (name.contains("..") || name.startsWith("/")) {
                    throw InvalidPackageException("包内路径非法：$name")
                }
                if (!entry.isDirectory) {
                    val bytes = zis.readBytes()
                    total += bytes.size
                    if (total > MAX_UNPACKED_BYTES) throw InvalidPackageException("包解压后过大")
                    out[name] = bytes
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        return out
    }

    private fun writeInt(out: OutputStream, value: Int) {
        out.write((value ushr 24) and 0xFF)
        out.write((value ushr 16) and 0xFF)
        out.write((value ushr 8) and 0xFF)
        out.write(value and 0xFF)
    }

    private fun readInt(input: InputStream): Int {
        val b = readExactly(input, 4)
        return ((b[0].toInt() and 0xFF) shl 24) or
            ((b[1].toInt() and 0xFF) shl 16) or
            ((b[2].toInt() and 0xFF) shl 8) or
            (b[3].toInt() and 0xFF)
    }

    private fun readExactly(input: InputStream, count: Int): ByteArray {
        val buffer = ByteArray(count)
        var read = 0
        while (read < count) {
            val n = input.read(buffer, read, count - read)
            if (n < 0) throw InvalidPackageException("包数据不完整")
            read += n
        }
        return buffer
    }
}
