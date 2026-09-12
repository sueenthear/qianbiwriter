package com.qianbi.writer.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.qianbi.writer.data.BookRef
import com.qianbi.writer.data.BookStore
import com.qianbi.writer.data.DisguisePrefs
import com.qianbi.writer.data.StoragePrefs
import com.qianbi.writer.data.WebPrefs
import com.qianbi.writer.web.WebService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 导航：**书架页 → 书本简介页 + 目录页 → 编辑页**。
 * 只有三个目的地，用一个状态变量就够了，不必引入 navigation 库。
 *
 * 书架仓库（[BookStore] 的 booksDir）可以是应用私有目录，也可以是用户自选的外部文件夹；
 * 换仓库会重建 store 并重新扫描书架。
 */
private sealed interface Route {
    data object Shelf : Route
    data class Detail(val ref: BookRef) : Route
    data class Editor(val ref: BookRef, val nodeId: String?) : Route
}

@Composable
fun NovelApp(context: Context) {
    val scope = rememberCoroutineScope()
    var store by remember { mutableStateOf(StoragePrefs.openStore(context)) }
    var books by remember { mutableStateOf<List<BookRef>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var route: Route by remember { mutableStateOf(Route.Shelf) }

    // 伪装模式：开启且本次还没解锁时，先显示"AI 聊天"界面
    var unlocked by remember { mutableStateOf(!DisguisePrefs.isEnabled(context)) }
    if (!unlocked) {
        ChatScreen(
            password = DisguisePrefs.password(context),
            onUnlock = { unlocked = true },
        )
        return
    }

    // Web 协作控制台是一个独立页面，不是随手能关掉的对话框：
    // 开着它 = 编辑权在电脑那边，手机不写书；只有点「关闭 Web 协作」才回得来。
    // 初始值取服务器的真实状态：上次没关就退出应用的话，一进来还是控制台。
    var consoleOpen by remember { mutableStateOf(WebService.isRunning()) }
    if (consoleOpen) {
        WebConsoleScreen(onClosed = { consoleOpen = false })
        return
    }

    LaunchedEffect(store) {
        loaded = false
        books = withContext(Dispatchers.IO) { store.listBooks() }
        loaded = true
    }

    fun refresh() {
        scope.launch { books = withContext(Dispatchers.IO) { store.listBooks() } }
    }

    when (val current = route) {
        is Route.Shelf -> HomeScreen(
            store = store,
            books = books,
            loaded = loaded,
            onRefreshRequest = { refresh() },
            onOpenBook = { route = Route.Detail(it) },
            onRepositoryChanged = {
                // 换仓库：回到书架并重建 store（LaunchedEffect(store) 会重新扫描）
                route = Route.Shelf
                store = StoragePrefs.openStore(context)
            },
            onLockNow = { unlocked = false },
            onOpenConsole = {
                WebPrefs.setEnabled(context, true)
                WebService.start(context)
                consoleOpen = true
            },
        )

        is Route.Detail -> BookDetailScreen(
            store = store,
            initial = current.ref,
            onBack = {
                route = Route.Shelf
                refresh()
            },
            onOpenChapter = { ref, nodeId -> route = Route.Editor(ref, nodeId) },
        )

        is Route.Editor -> EditorScreen(
            store = store,
            initial = current.ref,
            initialNodeId = current.nodeId,
            onBack = { latest -> route = Route.Detail(latest) },
            onBookUpdated = { updated -> route = Route.Editor(updated, current.nodeId) },
        )
    }
}
