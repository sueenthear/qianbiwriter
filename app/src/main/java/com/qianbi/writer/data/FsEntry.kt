package com.qianbi.writer.data

import java.io.File

/**
 * 文件系统条目抽象。
 *
 * 书本仓库只依赖下面这些操作，于是同一套 [BookStore] 逻辑既能跑在
 * 「应用私有目录（[FileFsEntry]）」上，也能跑在
 * 「用户自选的外部文件夹（[DocFsEntry]，Storage Access Framework）」上。
 *
 * 约定：`child(name)` 允许返回"尚不存在"的条目，写操作负责按需创建。
 */
interface FsEntry {
    val name: String

    /** 稳定标识：File 后端是绝对路径，SAF 后端是 document uri。用于 UI key 与去重。 */
    val path: String

    val exists: Boolean
    val isDirectory: Boolean
    val isFile: Boolean

    /** 目录内容；不存在或不是目录时返回空列表。 */
    fun list(): List<FsEntry>

    fun child(name: String): FsEntry

    fun readText(): String
    fun writeText(text: String)

    fun readBytes(): ByteArray
    fun writeBytes(bytes: ByteArray)

    /** 创建本条目为目录（父目录必须已存在）。 */
    fun mkdirs(): Boolean

    /** 删除文件或空目录。 */
    fun delete(): Boolean

    /** 递归删除。 */
    fun deleteRecursively(): Boolean

    /** 同父级改名。 */
    fun renameTo(newName: String): Boolean
}

/** java.io.File 实现：应用私有目录，写入用「临时文件 + 改名」保证原子性。 */
class FileFsEntry(val file: File) : FsEntry {

    override val name: String get() = file.name

    override val path: String get() = file.absolutePath

    override val exists: Boolean get() = file.exists()

    override val isDirectory: Boolean get() = file.isDirectory

    override val isFile: Boolean get() = file.isFile

    override fun list(): List<FsEntry> =
        (file.listFiles() ?: emptyArray()).map { FileFsEntry(it) }

    override fun child(name: String): FsEntry = FileFsEntry(File(file, name))

    override fun readText(): String = try {
        if (file.isFile) file.readText() else ""
    } catch (e: Exception) {
        ""
    }

    override fun writeText(text: String) = writeBytes(text.toByteArray(Charsets.UTF_8))

    override fun readBytes(): ByteArray = try {
        if (file.isFile) file.readBytes() else ByteArray(0)
    } catch (e: Exception) {
        ByteArray(0)
    }

    override fun writeBytes(bytes: ByteArray) {
        val parent = file.parentFile
        if (parent == null) {
            file.writeBytes(bytes)
            return
        }
        parent.mkdirs()
        val tmp = File(parent, "${file.name}.tmp")
        try {
            tmp.writeBytes(bytes)
            if (file.exists()) file.delete()
            if (!tmp.renameTo(file)) {
                file.writeBytes(bytes)
                tmp.delete()
            }
        } catch (e: Exception) {
            tmp.delete()
            file.writeBytes(bytes)
        }
    }

    override fun mkdirs(): Boolean = file.isDirectory || file.mkdirs()

    override fun delete(): Boolean = file.delete()

    override fun deleteRecursively(): Boolean = file.deleteRecursively()

    override fun renameTo(newName: String): Boolean {
        val target = File(file.parentFile, newName)
        return file.renameTo(target)
    }

    override fun equals(other: Any?): Boolean = other is FileFsEntry && other.file == file

    override fun hashCode(): Int = file.hashCode()

    override fun toString(): String = path
}
