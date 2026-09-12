package com.qianbi.writer.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile

/**
 * SAF 实现：用户在系统文件选择器里挑的**外部文件夹**（如 `Documents/我的小说`、SD 卡目录）。
 *
 * 说明：
 * - Android 不允许直接拿外部目录的 `java.io.File` 路径（Scoped Storage），
 *   所以这里走 `DocumentFile` / `ContentResolver`。
 * - 目录名与文件名完全由我们决定（32 位哈希目录、`book.info`…），
 *   所以放到外部文件夹后结构一模一样。
 * - 写入直接覆盖（外部 provider 对「改名换文件」的支持参差不齐，不做原子替换）。
 */
class DocFsEntry private constructor(
    private val context: Context,
    private val parent: DocFsEntry?,
    private var doc: DocumentFile?,
    private var entryName: String,
) : FsEntry {

    private var resolved: Boolean = doc != null

    private fun resolve(): DocumentFile? {
        if (!resolved) {
            doc = doc ?: runCatching { parent?.resolve()?.findFile(entryName) }.getOrNull()
            resolved = true
        }
        return doc
    }

    override val name: String get() = entryName

    override val path: String
        get() = resolve()?.uri?.toString() ?: "${parent?.path ?: ""}/$entryName"

    override val exists: Boolean get() = resolve() != null

    override val isDirectory: Boolean get() = resolve()?.isDirectory == true

    override val isFile: Boolean get() = resolve()?.isFile == true

    override fun list(): List<FsEntry> {
        val self = resolve() ?: return emptyList()
        if (!self.isDirectory) return emptyList()
        val children = runCatching { self.listFiles() }.getOrNull() ?: return emptyList()
        return children.map { DocFsEntry(context, this, it, it.name ?: "?") }
    }

    override fun child(name: String): FsEntry = DocFsEntry(context, this, null, name)

    override fun readText(): String = try {
        String(readBytes(), Charsets.UTF_8)
    } catch (e: Exception) {
        ""
    }

    override fun writeText(text: String) = writeBytes(text.toByteArray(Charsets.UTF_8))

    override fun readBytes(): ByteArray = try {
        val uri = resolve()?.uri ?: return ByteArray(0)
        context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0)
    } catch (e: Exception) {
        ByteArray(0)
    }

    override fun writeBytes(bytes: ByteArray) {
        val target = materializeFile() ?: return
        try {
            context.contentResolver.openOutputStream(target.uri, "wt")?.use { it.write(bytes) }
        } catch (e: Exception) {
            // 外部 provider 写失败（只读挂载、权限被撤销等）：静默失败，调用方用 exists/readBytes 校验
        }
    }

    override fun mkdirs(): Boolean {
        val existing = resolve()
        if (existing != null) return existing.isDirectory
        val parentDoc = parent?.resolve() ?: return false
        val created = runCatching { parentDoc.createDirectory(entryName) }.getOrNull() ?: return false
        doc = created
        resolved = true
        return created.isDirectory
    }

    override fun delete(): Boolean = runCatching { resolve()?.delete() ?: false }.getOrDefault(false)

    override fun deleteRecursively(): Boolean {
        val self = resolve() ?: return false
        if (self.isDirectory) list().forEach { it.deleteRecursively() }
        return runCatching { self.delete() }.getOrDefault(false)
    }

    override fun renameTo(newName: String): Boolean {
        val self = resolve() ?: return false
        val ok = runCatching { self.renameTo(newName) }.getOrDefault(false)
        if (!ok) return false
        val parentDir = parent
        if (parentDir != null) doc = runCatching { parentDir.resolve()?.findFile(newName) }.getOrNull()
        entryName = newName
        resolved = true
        return true
    }

    private fun materializeFile(): DocumentFile? {
        resolve()?.let { return it }
        val parentDoc = parent?.resolve() ?: return null
        val created = runCatching { parentDoc.createFile(mimeOf(entryName), entryName) }.getOrNull() ?: return null
        doc = created
        resolved = true
        return created
    }

    override fun equals(other: Any?): Boolean = other is DocFsEntry && other.path == path

    override fun hashCode(): Int = path.hashCode()

    override fun toString(): String = path

    companion object {
        /** 打开用户之前选中的目录树；不可用（权限被撤销等）时返回 null。 */
        fun root(context: Context, uri: Uri): DocFsEntry? {
            val doc = runCatching { DocumentFile.fromTreeUri(context, uri) }.getOrNull() ?: return null
            if (!doc.isDirectory) return null
            return DocFsEntry(context, null, doc, doc.name ?: "书架")
        }

        fun of(context: Context, doc: DocumentFile): DocFsEntry =
            DocFsEntry(context, null, doc, doc.name ?: "书架")

        private fun mimeOf(name: String): String = when {
            name.endsWith(".txt") -> "text/plain"
            name.endsWith(".json") -> "application/json"
            name.endsWith(".jpg") || name.endsWith(".jpeg") -> "image/jpeg"
            name.endsWith(".png") -> "image/png"
            else -> "application/octet-stream"
        }
    }
}
