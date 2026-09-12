package com.qianbi.writer.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.io.File

/**
 * 书架仓库的位置。
 *
 * - 默认：应用外部私有目录 `Android/data/<包名>/files/NovelStudio/books`
 *   —— 不需要任何权限，也用不着用户操心；
 * - 可选：用户在系统文件选择器里挑的**外部文件夹**（SAF 持久授权），
 *   书稿就实实在在躺在用户自己的目录里，用任何文件管理器都能看到。
 *
 * 仓库位置只存一个 tree uri；选中时申请持久读写权限，之后重启应用仍然有效。
 */
object StoragePrefs {

    private const val PREFS = "novel-storage"
    private const val KEY_REPO_URI = "repo_tree_uri"
    private const val KEY_REPO_LABEL = "repo_label"

    fun externalUri(context: Context): Uri? =
        prefs(context).getString(KEY_REPO_URI, null)?.let { runCatching { Uri.parse(it) }.getOrNull() }

    fun describe(context: Context): String {
        val uri = externalUri(context) ?: return "应用私有目录（默认）"
        val label = prefs(context).getString(KEY_REPO_LABEL, null)
        if (!label.isNullOrBlank()) return label
        return uri.lastPathSegment?.substringAfterLast(':')?.takeIf { it.isNotBlank() } ?: "外部文件夹"
    }

    fun isExternal(context: Context): Boolean = externalUri(context) != null

    /** 记住用户选中的外部目录；传 null 表示恢复默认（私有目录）。 */
    fun setRepository(context: Context, uri: Uri?, label: String?) {
        prefs(context).edit().apply {
            if (uri == null) {
                remove(KEY_REPO_URI)
                remove(KEY_REPO_LABEL)
            } else {
                putString(KEY_REPO_URI, uri.toString())
                putString(KEY_REPO_LABEL, label)
            }
        }.apply()
    }

    /** 申请对所选目录树的持久读写权限（失败则只对本次会话有效）。 */
    fun takePersistablePermission(context: Context, uri: Uri): Boolean = try {
        context.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        true
    } catch (e: Exception) {
        false
    }

    fun defaultBooksDir(context: Context): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        return File(base, "NovelStudio/$DIR_BOOKS")
    }

    /** 打开当前仓库。外部目录不可用（权限被撤销 / 目录被删）时自动回落到私有目录。 */
    fun openStore(context: Context): BookStore {
        val uri = externalUri(context)
        if (uri != null) {
            val entry = DocFsEntry.root(context, uri)
            if (entry != null && entry.exists) return BookStore(entry)
            setRepository(context, null, null)
        }
        return BookStore(FileFsEntry(defaultBooksDir(context)))
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
