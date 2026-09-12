package com.qianbi.writer.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.qianbi.writer.data.BookDoc
import com.qianbi.writer.data.BookPackage
import com.qianbi.writer.data.BookRef
import com.qianbi.writer.data.BookStore
import com.qianbi.writer.data.ImageUtil
import com.qianbi.writer.data.InvalidPackageException
import com.qianbi.writer.data.NodeKind
import com.qianbi.writer.data.NodeInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 书本简介页 + 目录页。
 *
 * 导航：书架 →（点一本书）→ **本页** →（点一章 / 继续写作）→ 编辑页。
 * 这里能看书名 / 作者 / 标签 / 简介 / 创建与最后改动时间 / 全书字数，也能直接在目录里
 * 新建、改名、排序、移动、删除章节。
 */
@Composable
fun BookDetailScreen(
    store: BookStore,
    initial: BookRef,
    onBack: () -> Unit,
    onOpenChapter: (BookRef, String?) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var ref by remember { mutableStateOf(initial) }
    var doc by remember { mutableStateOf<BookDoc?>(null) }
    var chars by remember { mutableStateOf(0) }
    var busyText by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var infoOpen by remember { mutableStateOf(false) }
    var exportOpen by remember { mutableStateOf(false) }
    var exportPassword by remember { mutableStateOf<String?>(null) }
    var deleteOpen by remember { mutableStateOf(false) }
    var nodeEditTarget by remember { mutableStateOf<NodeInfo?>(null) }
    var moveTarget by remember { mutableStateOf<String?>(null) }
    var createPrompt by remember { mutableStateOf<Pair<String?, String>?>(null) }

    fun reload() {
        scope.launch {
            val loaded = withContext(Dispatchers.IO) { store.loadDoc(ref) }
            doc = loaded
            ref = BookRef(ref.dir, loaded.meta)
            chars = withContext(Dispatchers.IO) { store.countChars(ref) }
        }
    }

    LaunchedEffect(ref.key) { reload() }

    // 系统返回键 → 回书架
    BackHandler { onBack() }

    val cover by produceState<ImageBitmap?>(initialValue = null, ref.key, ref.meta.cover) {
        value = withContext(Dispatchers.IO) {
            ImageUtil.load(store.coverFile(ref)?.readBytes() ?: ByteArray(0), 540)
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(BookPackage.MIME)
    ) { uri ->
        val password = exportPassword
        exportPassword = null
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busyText = "正在导出…"
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        BookPackage.export(ref.dir, out, password)
                    } ?: throw InvalidPackageException("无法写入目标文件")
                }
            }
            busyText = null
            message = result.fold(
                onSuccess = { "已导出《${ref.meta.title}》" + if (password.isNullOrEmpty()) "（未加密）" else "（已加密）" },
                onFailure = { "导出失败：${it.message ?: it::class.java.simpleName}" },
            )
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // ── 顶栏 ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(start = 4.dp, end = 4.dp, top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "返回书架", tint = Color.White)
            }
            Text(
                text = ref.meta.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = {
                val target = doc?.nodes?.values?.maxByOrNull { it.modifiedAt }?.id
                    ?: doc?.meta?.order?.firstOrNull()
                onOpenChapter(ref, target)
            }) {
                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(2.dp))
                Text("写作", color = AccentGold)
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "更多")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("书本信息 / 设置") },
                        onClick = {
                            menuOpen = false
                            infoOpen = true
                        },
                    )
                    DropdownMenuItem(
                        text = {
                            Text(if (ref.meta.shareEnabled) "关闭 Web 共享" else "开启 Web 共享")
                        },
                        leadingIcon = {
                            Icon(
                                Icons.Default.Share,
                                contentDescription = null,
                                tint = if (ref.meta.shareEnabled) AccentMint else MutedText,
                            )
                        },
                        onClick = {
                            menuOpen = false
                            val enabled = !ref.meta.shareEnabled
                            scope.launch {
                                ref = withContext(Dispatchers.IO) { store.setShare(ref, enabled) }
                                message = if (enabled) {
                                    "已开启 Web 共享：手机开着服务器时，电脑浏览器里就能编辑这本书"
                                } else {
                                    "已关闭 Web 共享"
                                }
                            }
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("导出 .book") },
                        onClick = {
                            menuOpen = false
                            exportOpen = true
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("删除这本书", color = MaterialTheme.colorScheme.error) },
                        leadingIcon = {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                            )
                        },
                        onClick = {
                            menuOpen = false
                            deleteOpen = true
                        },
                    )
                }
            }
        }

        // ── 书本简介 ──
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 290.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row {
                Box(
                    modifier = Modifier
                        .size(width = 88.dp, height = 122.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(PanelColorAlt),
                    contentAlignment = Alignment.Center,
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
                        Text("无封面", style = MaterialTheme.typography.labelSmall, color = MutedText)
                    }
                }
                Column(
                    modifier = Modifier
                        .padding(start = 14.dp)
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = ref.meta.title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Text(
                        text = "作者：${ref.meta.author.ifBlank { "未署名" }}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MutedText,
                    )
                    Text(
                        text = if (doc == null) "正在读取…" else "全书 $chars 字 · ${doc?.rowCount ?: 0} 个目录节点",
                        style = MaterialTheme.typography.bodyMedium,
                        color = AccentMint,
                    )
                    KeyValueRow("创建时间", formatDateTime(ref.meta.createdAt))
                    KeyValueRow("最后改动", formatDateTime(ref.meta.modifiedAt))
                    KeyValueRow(
                        "Web 共享",
                        if (ref.meta.shareEnabled) "已开启 · 可在电脑上编辑" else "未开启",
                    )
                }
            }

            if (ref.meta.tags.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ref.meta.tags.take(8).forEach { tag -> TagChip("#$tag") }
                }
            }

            SectionHeader("简介")
            Text(
                text = ref.meta.summary.ifBlank { "还没有写简介。点右上角 ⋮ →「书本信息 / 设置」补上。" },
                style = MaterialTheme.typography.bodyMedium,
                color = if (ref.meta.summary.isBlank()) MutedText else MaterialTheme.colorScheme.onBackground,
            )
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

        // ── 目录（标题与「新卷/章」入口由 TreePanel 自带）──

        Box(modifier = Modifier.weight(1f)) {
            TreePanel(
                doc = doc,
                selectedId = null,
                onSelect = { id ->
                    val node = doc?.nodes?.get(id)
                    if (node != null && node.kind == NodeKind.Group) {
                        // 卷不承载正文：点它直接弹简介编辑，不进编辑页
                        nodeEditTarget = node
                    } else {
                        onOpenChapter(ref, id)
                    }
                },
                onAddRoot = { createPrompt = null to "同级" },
                onAddChild = { id -> createPrompt = id to "子级" },
                onAddSibling = { id -> createPrompt = (doc?.parentOf(id)) to "同级" },
                onEdit = { id -> nodeEditTarget = doc?.nodes?.get(id) },
                onMove = { id -> moveTarget = id },
                onDelete = { id ->
                    scope.launch {
                        withContext(Dispatchers.IO) { store.deleteNode(ref, id) }
                        reload()
                    }
                },
                onShift = { id, delta ->
                    scope.launch {
                        withContext(Dispatchers.IO) { store.moveNode(ref, id, delta) }
                        reload()
                    }
                },
            )
        }
    }

    // ─────────────────────── 对话框 ───────────────────────

    if (infoOpen) {
        BookInfoDialog(
            store = store,
            ref = ref,
            onDismiss = { infoOpen = false },
            onSaved = { updated ->
                infoOpen = false
                ref = updated
                message = "已保存《${updated.meta.title}》的书本信息"
            },
        )
    }

    nodeEditTarget?.let { node ->
        NodeEditDialog(
            heading = "编辑「${node.title}」",
            title = node.title,
            summary = node.summary,
            createdAt = node.createdAt,
            modifiedAt = node.modifiedAt,
            onDismiss = { nodeEditTarget = null },
            onConfirm = { title, summary ->
                nodeEditTarget = null
                scope.launch {
                    withContext(Dispatchers.IO) { store.updateNode(ref, node.id, title, summary) }
                    reload()
                }
            },
        )
    }

    val currentDoc = doc
    val moveId = moveTarget
    if (currentDoc != null && moveId != null) {
        MoveNodeDialog(
            doc = currentDoc,
            nodeId = moveId,
            onDismiss = { moveTarget = null },
            onConfirm = { target ->
                moveTarget = null
                scope.launch {
                    withContext(Dispatchers.IO) { store.reparentNode(ref, moveId, target) }
                    reload()
                }
            },
        )
    }

    createPrompt?.let { (parentId, kind) ->
        TextPromptDialog(
            title = if (kind == "子级") "新建子级" else "新建卷 / 章",
            label = "题目",
            initial = "",
            hint = "「目录（卷）」用来分组、只能写简介；「正文（章）」才能写正文。章可以加在任何一级下面。",
            kindOptions = true,
            defaultKind = NodeKind.Group,
            onDismiss = { createPrompt = null },
            onConfirm = { title, nodeKind ->
                createPrompt = null
                scope.launch {
                    withContext(Dispatchers.IO) { store.addNode(ref, parentId, title, nodeKind) }
                    reload()
                }
            },
        )
    }

    if (exportOpen) {
        ExportDialog(
            title = ref.meta.title,
            onDismiss = { exportOpen = false },
            onConfirm = { password ->
                exportOpen = false
                exportPassword = password
                exportLauncher.launch("${ref.meta.title}.${BookPackage.EXTENSION}")
            },
        )
    }

    if (deleteOpen) {
        AlertDialog(
            onDismissRequest = { deleteOpen = false },
            title = { Text("删除《${ref.meta.title}》？") },
            text = { Text("该书所在的文件夹会被整个删除，无法撤销。建议先导出 .book 备份。") },
            confirmButton = {
                TextButton(onClick = {
                    deleteOpen = false
                    scope.launch {
                        busyText = "正在删除…"
                        withContext(Dispatchers.IO) { store.deleteBook(ref) }
                        busyText = null
                        onBack()
                    }
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteOpen = false }) { Text("取消") } },
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
