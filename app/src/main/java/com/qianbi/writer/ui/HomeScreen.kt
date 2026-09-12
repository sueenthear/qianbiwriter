package com.qianbi.writer.ui

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.qianbi.writer.data.BookPackage
import com.qianbi.writer.data.BookRef
import com.qianbi.writer.data.BookStore
import com.qianbi.writer.data.DisguisePrefs
import com.qianbi.writer.data.ImageUtil
import com.qianbi.writer.data.InvalidPackageException
import com.qianbi.writer.data.NeedPasswordException
import com.qianbi.writer.data.StoragePrefs
import com.qianbi.writer.data.WrongPasswordException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


/** 书架主界面：网格展示书本（封面 + 书名），顶部支持书名 / `#标签` 多标签筛选。 */
@Composable
fun HomeScreen(
    store: BookStore,
    books: List<BookRef>,
    loaded: Boolean,
    onRefreshRequest: () -> Unit,
    onOpenBook: (BookRef) -> Unit,
    onRepositoryChanged: () -> Unit,
    onLockNow: () -> Unit,
    onOpenConsole: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var query by remember { mutableStateOf("") }
    val parsed = remember(query) { BookSearch.parse(query) }
    val filtered = remember(books, parsed) { books.filter { BookSearch.matches(it.meta, parsed) } }
    val allTags = remember(books) { books.flatMap { it.meta.tags }.distinct().sorted() }

    var busyText by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var createOpen by remember { mutableStateOf(false) }
    var infoTarget by remember { mutableStateOf<BookRef?>(null) }
    var exportTarget by remember { mutableStateOf<BookRef?>(null) }
    var exportPassword by remember { mutableStateOf<String?>(null) }
    var deleteTarget by remember { mutableStateOf<BookRef?>(null) }
    var repoOpen by remember { mutableStateOf(false) }
    var pendingUri by remember { mutableStateOf<Uri?>(null) }
    var passwordOpen by remember { mutableStateOf(false) }
    var passwordDraft by remember { mutableStateOf("") }
    // 导出：先问口令，再走 SAF 选保存位置
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(BookPackage.MIME)
    ) { uri ->
        val target = exportTarget
        val password = exportPassword
        exportTarget = null
        exportPassword = null
        if (uri == null || target == null) return@rememberLauncherForActivityResult
        scope.launch {
            busyText = "正在导出…"
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        BookPackage.export(target.dir, out, password)
                    } ?: throw InvalidPackageException("无法写入目标文件")
                }
            }
            busyText = null
            message = result.fold(
                onSuccess = {
                    "已导出《${target.meta.title}》" + if (password.isNullOrEmpty()) "（未加密）" else "（已加密）"
                },
                onFailure = { "导出失败：${it.message ?: it::class.java.simpleName}" },
            )
        }
    }

    // 导入：先读明文头部判断是否需要口令
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busyText = "正在读取包…"
            val header = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { BookPackage.readHeader(it) }
                }.getOrNull()
            }
            busyText = null
            if (header == null) {
                message = "无法读取该文件，或它不是有效的 .book 包"
                return@launch
            }
            if (header.encrypted) {
                pendingUri = uri
                passwordDraft = ""
                passwordOpen = true
            } else {
                message = doImport(context, store, uri, null) { busyText = it }
                onRefreshRequest()
            }
        }
    }

    // 选一个外部文件夹当书架仓库（申请持久读写权限）
    val treeLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val persistable = StoragePrefs.takePersistablePermission(context, uri)
        val label = uri.lastPathSegment?.substringAfterLast(':')?.takeIf { it.isNotBlank() } ?: "外部文件夹"
        StoragePrefs.setRepository(context, uri, label)
        repoOpen = false
        message = buildString {
            append("书架仓库已切换到「$label」")
            if (!persistable) append("（系统未授予持久权限，重启后可能失效）")
        }
        onRepositoryChanged()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF11151A), Color(0xFF141C24), Color(0xFF11151A))
                )
            )
    ) {
        // ── 顶栏 ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(start = 16.dp, end = 8.dp, top = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "书架",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    text = if (parsed.isEmpty) {
                        "${books.size} 本书 · ${StoragePrefs.describe(context)}"
                    } else {
                        "筛选出 ${filtered.size} / ${books.size} 本"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MutedText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onOpenConsole) {
                Icon(Icons.Default.Share, contentDescription = "Web 协作", tint = MutedText)
            }
            IconButton(onClick = { repoOpen = true }) {
                Icon(Icons.Default.Settings, contentDescription = "设置", tint = MutedText)
            }
            TextButton(onClick = { importLauncher.launch(arrayOf("*/*")) }) {
                Text("导入", color = AccentMint)
            }
            Button(onClick = { createOpen = true }) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.width(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("新建")
            }
        }

        // ── 搜索 ──
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            placeholder = { Text("搜书名 / 作者，或 #标签 筛选，如 #玄幻#修仙", color = MutedText) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = MutedText) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = "" }) {
                        Icon(Icons.Default.Clear, contentDescription = "清空", tint = MutedText)
                    }
                }
            },
            singleLine = true,
        )

        if (allTags.isNotEmpty()) {
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 4.dp),
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(count = allTags.size, key = { allTags[it] }) { index ->
                    val tag = allTags[index]
                    val active = parsed.tags.any { it.equals(tag, ignoreCase = true) }
                    TagChip(text = "#$tag", selected = active) {
                        val current = BookSearch.parse(query)
                        query = if (active) {
                            (current.text + " " + current.tags
                                .filterNot { it.equals(tag, ignoreCase = true) }
                                .joinToString(" ") { "#$it" }).trim()
                        } else {
                            (query.trim() + " #$tag").trim()
                        }
                    }
                }
            }
        }

        // ── 网格 ──
        when {
            !loaded -> EmptyHint("正在读取书架…", "稍等片刻", Modifier.fillMaxSize())
            books.isEmpty() -> EmptyHint(
                "书架还是空的",
                "点右上角「新建」创建第一本书，或用「导入」打开一个 .book 包",
                Modifier.fillMaxSize(),
            )
            filtered.isEmpty() -> EmptyHint(
                "没有匹配的书",
                "换个关键词，或清掉 #标签 再试",
                Modifier.fillMaxSize(),
            )
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 146.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 28.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                items(items = filtered, key = { it.key }) { ref ->
                    BookCard(
                        store = store,
                        ref = ref,
                        onOpen = { onOpenBook(ref) },
                        onShowInfo = { infoTarget = ref },
                        onExport = { exportTarget = ref },
                        onDelete = { deleteTarget = ref },
                    )
                }
            }
        }
    }

    // ─────────────────────── 对话框 ───────────────────────

    if (createOpen) {
        CreateBookDialog(
            onDismiss = { createOpen = false },
            onCreate = { title, author, tags ->
                createOpen = false
                scope.launch {
                    val ref = withContext(Dispatchers.IO) { store.createBook(title, author, tags = tags) }
                    onRefreshRequest()
                    onOpenBook(ref)
                }
            },
        )
    }

    infoTarget?.let { target ->
        BookInfoDialog(
            store = store,
            ref = target,
            onDismiss = { infoTarget = null },
            onSaved = { updated ->
                infoTarget = null
                onRefreshRequest()
                message = "已保存《${updated.meta.title}》的书本信息"
            },
        )
    }

    exportTarget?.let { target ->
        ExportDialog(
            title = target.meta.title,
            onDismiss = { exportTarget = null },
            onConfirm = { password ->
                exportPassword = password
                exportLauncher.launch("${target.meta.title}.${BookPackage.EXTENSION}")
            },
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除《${target.meta.title}》？") },
            text = { Text("该书所在的文件夹会被整个删除，无法撤销。建议先导出 .book 备份。") },
            confirmButton = {
                TextButton(onClick = {
                    deleteTarget = null
                    scope.launch {
                        busyText = "正在删除…"
                        withContext(Dispatchers.IO) { store.deleteBook(target) }
                        busyText = null
                        message = "已删除《${target.meta.title}》"
                        onRefreshRequest()
                    }
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } },
        )
    }

    if (passwordOpen) {
        PasswordDialog(
            title = "导入需要密码",
            hint = "这个 .book 包已加密，请输入导出时设置的口令",
            password = passwordDraft,
            onPasswordChange = { passwordDraft = it },
            onDismiss = {
                passwordOpen = false
                pendingUri = null
            },
            onConfirm = {
                val uri = pendingUri
                passwordOpen = false
                pendingUri = null
                if (uri != null) {
                    scope.launch {
                        message = doImport(context, store, uri, passwordDraft) { busyText = it }
                        onRefreshRequest()
                    }
                }
            },
        )
    }

    if (repoOpen) {
        SettingsDialog(
            store = store,
            onPickFolder = { treeLauncher.launch(null) },
            onResetRepo = {
                StoragePrefs.setRepository(context, null, null)
                repoOpen = false
                message = "书架仓库已恢复为应用私有目录"
                onRepositoryChanged()
            },
            onLockNow = {
                repoOpen = false
                onLockNow()
            },
            onDismiss = { repoOpen = false },
        )
    }

    message?.let { text ->
        AlertDialog(
            onDismissRequest = { message = null },
            title = { Text("提示") },
            text = { Text(text) },
            confirmButton = { TextButton(onClick = { message = null }) { Text("好") } },
        )
    }

    busyText?.let { text ->
        AlertDialog(
            onDismissRequest = {},
            title = null,
            text = { Text(text) },
            confirmButton = {},
        )
    }
}

/** 网格里的一张书卡：主界面只显示封面 + 书名，其余信息点进去看。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookCard(
    store: BookStore,
    ref: BookRef,
    onOpen: () -> Unit,
    onShowInfo: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val cover by produceState<ImageBitmap?>(initialValue = null, ref.key, ref.meta.cover) {
        value = withContext(Dispatchers.IO) {
            val bytes = store.coverFile(ref)?.readBytes() ?: ByteArray(0)
            ImageUtil.load(bytes, 480)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onOpen, onLongClick = { menuOpen = true }),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.72f)
                .clip(RoundedCornerShape(12.dp))
                .background(PanelColorAlt),
        ) {
            val bitmap = cover
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Brush.linearGradient(listOf(Color(0xFF243040), Color(0xFF151D27)))),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = ref.meta.title.take(1),
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Black,
                        color = AccentGold.copy(alpha = 0.8f),
                    )
                }
            }
            Box(modifier = Modifier.align(Alignment.TopEnd)) {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = "更多",
                        tint = Color.White.copy(alpha = 0.85f),
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("书本信息 / 设置") },
                        onClick = {
                            menuOpen = false
                            onShowInfo()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("导出 .book") },
                        onClick = {
                            menuOpen = false
                            onExport()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                        leadingIcon = {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                            )
                        },
                        onClick = {
                            menuOpen = false
                            onDelete()
                        },
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = ref.meta.title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 设置：书架仓库 + 伪装模式。 */
@Composable
private fun SettingsDialog(
    store: BookStore,
    onPickFolder: () -> Unit,
    onResetRepo: () -> Unit,
    onLockNow: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var disguise by remember { mutableStateOf(DisguisePrefs.isEnabled(context)) }
    var password by remember { mutableStateOf(DisguisePrefs.password(context)) }
    var showPassword by remember { mutableStateOf(false) }
    var hint by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("设置") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 440.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SectionHeader("书架仓库")
                KeyValueRow("当前", StoragePrefs.describe(context))
                Text(
                    store.booksDir.path,
                    style = MaterialTheme.typography.labelSmall,
                    color = AccentMint,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onPickFolder) { Text("选择外部文件夹") }
                    if (StoragePrefs.isExternal(context)) {
                        TextButton(onClick = onResetRepo) {
                            Text("恢复默认", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

                SectionHeader("伪装模式")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("开启伪装模式", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "启动时先显示一个 AI 聊天界面，输入口令才进入书架；输错会显示 API 报错。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MutedText,
                        )
                    }
                    Switch(
                        checked = disguise,
                        onCheckedChange = { on ->
                            disguise = on
                            DisguisePrefs.setEnabled(context, on)
                            hint = if (on) "已开启：下次启动会先显示聊天界面" else "已关闭伪装模式"
                        },
                    )
                }
                if (disguise) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            label = { Text("解锁口令") },
                            singleLine = true,
                            visualTransformation = if (showPassword) {
                                VisualTransformation.None
                            } else {
                                PasswordVisualTransformation()
                            },
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { showPassword = !showPassword }) {
                            Text(
                                if (showPassword) "隐藏" else "显示",
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(
                            enabled = password.isNotBlank(),
                            onClick = {
                                DisguisePrefs.setPassword(context, password)
                                hint = "口令已保存"
                            },
                        ) { Text("保存口令") }
                        TextButton(onClick = onLockNow) {
                            Text("立即进入伪装界面", color = AccentGold)
                        }
                    }
                }
                hint?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = AccentMint)
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

                SectionHeader("存储位置")
                Text(
                    "每本书就是一个文件夹：" + store.booksDir.path,
                    style = MaterialTheme.typography.labelSmall,
                    color = MutedText,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

/** 执行导入，返回一句给用户看的结论。 */
private suspend fun doImport(
    context: Context,
    store: BookStore,
    uri: Uri,
    password: String?,
    onBusy: (String?) -> Unit,
): String {
    onBusy("正在导入…")
    val result = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { BookPackage.import(store, it, password) }
                ?: throw InvalidPackageException("无法打开文件")
        }
    }
    onBusy(null)
    return result.fold(
        onSuccess = { "已导入《${it.meta.title}》" },
        onFailure = { error ->
            when (error) {
                is WrongPasswordException -> "密码错误，导入已取消"
                is NeedPasswordException -> "这个包需要密码才能导入"
                is InvalidPackageException -> "导入失败：${error.message}"
                else -> "导入失败：${error.message ?: error::class.java.simpleName}"
            }
        },
    )
}
