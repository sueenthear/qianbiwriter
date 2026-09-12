package com.qianbi.writer.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qianbi.writer.data.BookDoc
import com.qianbi.writer.data.BookPackage
import com.qianbi.writer.data.BookRef
import com.qianbi.writer.data.BookStore
import com.qianbi.writer.data.EditorPrefs
import com.qianbi.writer.data.GlossaryItem
import com.qianbi.writer.data.InvalidPackageException
import com.qianbi.writer.data.NodeKind
import com.qianbi.writer.data.NodeInfo
import com.qianbi.writer.data.TreeRow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private enum class SidePanel { None, Tree, Glossary }

/** 离开编辑页时补写正文：不依赖组合生命周期，页面销毁后也能写完。 */
private val flushScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/** 编辑历史：快照式撤销 / 重做，连续输入会合并成一步。 */
private class EditorHistory {
    data class Snapshot(val text: String, val selection: Int)

    private val undoStack = ArrayDeque<Snapshot>()
    private val redoStack = ArrayDeque<Snapshot>()
    private var lastPushAt = 0L

    var undoCount by mutableIntStateOf(0)
        private set
    var redoCount by mutableIntStateOf(0)
        private set

    val canUndo: Boolean get() = undoCount > 0
    val canRedo: Boolean get() = redoCount > 0

    /** 记录一次「改动之前」的快照；600ms 内的连续输入只记一次。 */
    fun onEdit(before: Snapshot) {
        val now = System.currentTimeMillis()
        if (now - lastPushAt >= 600) {
            undoStack.addLast(before)
            while (undoStack.size > 200) undoStack.removeFirst()
            redoStack.clear()
            lastPushAt = now
        }
        sync()
    }

    fun undo(current: Snapshot): Snapshot? {
        val previous = undoStack.removeLastOrNull() ?: return null
        redoStack.addLast(current)
        lastPushAt = 0L
        sync()
        return previous
    }

    fun redo(current: Snapshot): Snapshot? {
        val next = redoStack.removeLastOrNull() ?: return null
        undoStack.addLast(current)
        lastPushAt = 0L
        sync()
        return next
    }

    fun reset() {
        undoStack.clear()
        redoStack.clear()
        lastPushAt = 0L
        sync()
    }

    private fun sync() {
        undoCount = undoStack.size
        redoCount = redoStack.size
    }
}

/**
 * 单本书的编辑页：中间正文，左侧目录抽屉，右侧快捷输入抽屉。
 *
 * - 正文改动停止 700ms 后自动落盘（切章节时会立刻补写一次，不会丢字）；
 * - **撤销 / 重做**放在顶栏，随时可点；
 * - 顶栏实时显示**本章字数与全书字数**；
 * - 字号可用工具条的 `A-` / `A+` 或菜单里的「正文字号…」调节，全局记住。
 */
@Composable
fun EditorScreen(
    store: BookStore,
    initial: BookRef,
    initialNodeId: String?,
    onBack: (BookRef) -> Unit,
    onBookUpdated: (BookRef) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }

    var ref by remember { mutableStateOf(initial) }
    var doc by remember { mutableStateOf<BookDoc?>(null) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var editorValue by remember { mutableStateOf(TextFieldValue("")) }
    var savedText by remember { mutableStateOf("") }
    var savedAt by remember { mutableStateOf(0L) }
    var pending by remember { mutableStateOf(false) }
    var panel by remember { mutableStateOf(SidePanel.None) }
    var busyText by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var infoOpen by remember { mutableStateOf(false) }
    var nodeEditTarget by remember { mutableStateOf<NodeInfo?>(null) }
    var moveOpen by remember { mutableStateOf(false) }
    var exportOpen by remember { mutableStateOf(false) }
    var exportPassword by remember { mutableStateOf<String?>(null) }
    var createPrompt by remember { mutableStateOf<Pair<String?, String>?>(null) }
    var fontOpen by remember { mutableStateOf(false) }
    var fontSp by remember { mutableStateOf(EditorPrefs.fontSize(context)) }
    val history = remember { EditorHistory() }

    // ── 字数：全书字数 + 本章字数（编辑时实时增量更新，不用重扫磁盘）──
    var bookChars by remember { mutableStateOf(0) }
    var chapterSavedChars by remember { mutableStateOf(0) }
    val chapterChars = remember(editorValue.text) { countChars(editorValue.text) }
    val totalChars = (bookChars - chapterSavedChars + chapterChars).coerceAtLeast(0)

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

    // 整本书载入（含 book.info / 各级 this.info / 快捷词表 / 全书字数）
    LaunchedEffect(ref.key) {
        val loaded = withContext(Dispatchers.IO) { store.loadDoc(ref) }
        doc = loaded
        bookChars = withContext(Dispatchers.IO) { store.countChars(ref) }
        val wanted = initialNodeId?.takeIf { loaded.nodes.containsKey(it) }
        selectedId = wanted
            ?: loaded.meta.order.firstOrNull()
            ?: loaded.nodes.keys.firstOrNull()
    }

    // 选中的章节 → 读正文
    LaunchedEffect(selectedId) {
        val id = selectedId
        if (id == null) {
            savedText = ""
            editorValue = TextFieldValue("")
            chapterSavedChars = 0
            history.reset()
            return@LaunchedEffect
        }
        // 目录（卷）不承载正文：不读内容，编辑区显示提示
        if (doc?.nodes?.get(id)?.writable == false) {
            savedText = ""
            editorValue = TextFieldValue("")
            chapterSavedChars = 0
            history.reset()
            return@LaunchedEffect
        }
        val text = withContext(Dispatchers.IO) { store.readContent(ref, id) }
        savedText = text
        editorValue = TextFieldValue(text, TextRange(text.length))
        chapterSavedChars = countChars(text)
        history.reset()
    }

    // 实时保存：停止输入 700ms 落盘
    LaunchedEffect(selectedId, editorValue.text) {
        val id = selectedId ?: return@LaunchedEffect
        val text = editorValue.text
        if (text == savedText) {
            pending = false
            return@LaunchedEffect
        }
        pending = true
        delay(700)
        val info = withContext(Dispatchers.IO) { store.writeContent(ref, id, text) }
        val now = System.currentTimeMillis()
        savedText = text
        savedAt = now
        pending = false
        // 全书字数按本次改动的差值更新，避免重扫整本书
        bookChars = (bookChars - chapterSavedChars + countChars(text)).coerceAtLeast(0)
        chapterSavedChars = countChars(text)
        val current = doc
        if (current != null) {
            doc = current.copy(
                meta = current.meta.copy(modifiedAt = now),
                nodes = if (info != null) current.nodes + (id to info) else current.nodes,
            )
        }
    }

    /** 切换章节前先把当前内容补写一次，避免节流期间丢字。 */
    fun flush(nodeId: String, text: String) {
        scope.launch { withContext(Dispatchers.IO) { store.writeContent(ref, nodeId, text) } }
    }

    fun selectNode(id: String?) {
        if (id == selectedId) return
        val previous = selectedId
        if (previous != null && editorValue.text != savedText) flush(previous, editorValue.text)
        selectedId = id
        panel = SidePanel.None
    }

    fun reload(keepSelection: String? = selectedId) {
        scope.launch {
            val fresh = withContext(Dispatchers.IO) { store.loadDoc(ref) }
            doc = fresh
            bookChars = withContext(Dispatchers.IO) { store.countChars(ref) }
            if (keepSelection != null) selectedId = keepSelection
        }
    }

    fun insertSnippet(snippet: String) {
        val value = editorValue
        val start = value.selection.min.coerceIn(0, value.text.length)
        val end = value.selection.max.coerceIn(start, value.text.length)
        history.onEdit(EditorHistory.Snapshot(value.text, start))
        val newText = value.text.replaceRange(start, end, snippet)
        editorValue = TextFieldValue(newText, TextRange(start + snippet.length))
        focusRequester.requestFocus()
    }

    fun bumpFont(delta: Int) {
        val next = (fontSp + delta).coerceIn(EditorPrefs.MIN_FONT_SP, EditorPrefs.MAX_FONT_SP)
        if (next != fontSp) {
            fontSp = next
            EditorPrefs.setFontSize(context, next)
        }
    }

    /** 离开编辑页：先把没落盘的字补写一次，再回上级页面。 */
    fun leaveEditor() {
        val id = selectedId
        if (id != null && editorValue.text != savedText) {
            val text = editorValue.text
            flushScope.launch { store.writeContent(ref, id, text) }
        }
        onBack(ref)
    }

    // 系统返回键：抽屉开着就先关抽屉，否则返回上一级页面
    BackHandler {
        if (panel != SidePanel.None) panel = SidePanel.None else leaveEditor()
    }

    val currentDoc = doc

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ── 顶栏：返回 / 书名 + 字数 / 撤销 / 重做 / 目录 / 词表 / 更多 ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(start = 2.dp, end = 2.dp, top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { onBack(ref) }) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "返回书本页", tint = Color.White)
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = currentDoc?.nodes?.get(selectedId)?.title
                            ?: currentDoc?.meta?.title
                            ?: ref.meta.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = when {
                            selectedId == null -> "未选中章节"
                            pending -> "编辑中… · 本章 ${formatCount(chapterChars)} 字 · 全书 ${formatCount(totalChars)} 字"
                            savedAt > 0L -> "已保存 ${formatClock(savedAt)} · 本章 ${formatCount(chapterChars)} 字 · 全书 ${formatCount(totalChars)} 字"
                            else -> "本章 ${formatCount(chapterChars)} 字 · 全书 ${formatCount(totalChars)} 字"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (pending) AccentGold else MutedText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // 撤销 / 重做放在顶栏，手指不用去够底部工具条
                CompactAction("撤销", enabled = history.canUndo) {
                    doUndo(history, editorValue) { editorValue = it }
                }
                CompactAction("重做", enabled = history.canRedo) {
                    doRedo(history, editorValue) { editorValue = it }
                }
                IconButton(onClick = { panel = if (panel == SidePanel.Tree) SidePanel.None else SidePanel.Tree }) {
                    Icon(
                        Icons.Default.List,
                        contentDescription = "目录",
                        tint = if (panel == SidePanel.Tree) AccentGold else MaterialTheme.colorScheme.onSurface,
                    )
                }
                IconButton(onClick = { panel = if (panel == SidePanel.Glossary) SidePanel.None else SidePanel.Glossary }) {
                    Icon(
                        Icons.Default.Person,
                        contentDescription = "快捷输入",
                        tint = if (panel == SidePanel.Glossary) AccentGold else MaterialTheme.colorScheme.onSurface,
                    )
                }
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "更多")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("正文字号…（当前 $fontSp）") },
                            onClick = {
                                menuOpen = false
                                fontOpen = true
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("书本信息 / 设置") },
                            leadingIcon = { Icon(Icons.Default.Settings, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                infoOpen = true
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
                            text = { Text("回到书本页") },
                            onClick = {
                                menuOpen = false
                                onBack(ref)
                            },
                        )
                    }
                }
            }

            // ── 面包屑 ──
            val crumb = currentDoc?.breadcrumb(selectedId).orEmpty()
            if (crumb.isNotEmpty()) {
                Text(
                    text = crumb.joinToString("  ›  "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MutedText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 2.dp),
                )
            }

            // ── 工具条（横滑）：章节操作 + 字号 ──
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {











                item { ToolButton("改题/简介", enabled = selectedId != null) { nodeEditTarget = selectedId?.let { currentDoc?.nodes?.get(it) } } }
                item {
                    ToolButton("删除", enabled = selectedId != null, danger = true) {
                        val id = selectedId
                        if (id != null) {
                            scope.launch {
                                withContext(Dispatchers.IO) { store.deleteNode(ref, id) }
                                val fresh = withContext(Dispatchers.IO) { store.loadDoc(ref) }
                                doc = fresh
                                bookChars = withContext(Dispatchers.IO) { store.countChars(ref) }
                                selectedId = null
                                savedText = ""
                            }
                        }
                    }
                }
                item { ToolButton("A-", enabled = fontSp > EditorPrefs.MIN_FONT_SP) { bumpFont(-1) } }
                item { ToolButton("A+", enabled = fontSp < EditorPrefs.MAX_FONT_SP) { bumpFont(1) } }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

            // ── 正文 ──
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .imePadding()
                    .background(MaterialTheme.colorScheme.surface)
            ) {
                val editing = selectedId?.let { currentDoc?.nodes?.get(it) }
                if (selectedId == null) {
                    EmptyHint("还没有选中章节", "点右上角目录图标选一章，或回书本页新建一卷 / 一章")
                } else if (editing != null && !editing.writable) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(28.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = "「${editing.title}」是目录（卷）",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = AccentGold,
                        )
                        Text(
                            text = "目录用来分组，只能写简介；正文请写在这一级下面的「章」里。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MutedText,
                            modifier = Modifier.padding(top = 8.dp, bottom = 12.dp),
                        )
                        TextButton(onClick = { nodeEditTarget = selectedId?.let { currentDoc?.nodes?.get(it) } }) { Text("编辑本卷简介") }
                    }
                } else {
                    BasicTextField(
                        value = editorValue,
                        onValueChange = { new ->
                            history.onEdit(
                                EditorHistory.Snapshot(editorValue.text, editorValue.selection.start)
                            )
                            editorValue = new
                        },
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 18.dp, vertical = 14.dp)
                            .focusRequester(focusRequester),
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            fontFamily = FontFamily.Serif,
                            fontSize = fontSp.sp,
                            lineHeight = EditorPrefs.lineHeightSp(fontSp).sp,
                            color = MaterialTheme.colorScheme.onSurface,
                        ),
                        cursorBrush = SolidColor(AccentGold),
                    )
                }
            }
        }

        // ── 左侧目录抽屉 ──
        if (panel == SidePanel.Tree) {
            DrawerScaffold(onClose = { panel = SidePanel.None }) {
                TreePanel(
                    doc = currentDoc,
                    selectedId = selectedId,
                    onSelect = { id ->
                        val node = currentDoc?.nodes?.get(id)
                        if (node != null && node.kind == NodeKind.Group) {
                            // 卷不承载正文：点它直接弹简介编辑，不进正文编辑
                            nodeEditTarget = node
                            panel = SidePanel.None
                        } else {
                            selectNode(id)
                        }
                    },
                    onAddRoot = { createPrompt = null to "同级" },
                    onAddChild = { id -> createPrompt = id to "子级" },
                    onAddSibling = { id ->
                        val parent = currentDoc?.parentOf(id)
                        createPrompt = parent to "同级"
                    },
                    onEdit = { id -> nodeEditTarget = currentDoc?.nodes?.get(id) },
                    onMove = { id ->
                        selectedId = id
                        moveOpen = true
                    },
                    onDelete = { id ->
                        scope.launch {
                            withContext(Dispatchers.IO) { store.deleteNode(ref, id) }
                            val fresh = withContext(Dispatchers.IO) { store.loadDoc(ref) }
                            doc = fresh
                            bookChars = withContext(Dispatchers.IO) { store.countChars(ref) }
                            if (id == selectedId) {
                                selectedId = null
                                savedText = ""
                            }
                        }
                    },
                    onShift = { id, delta ->
                        scope.launch {
                            withContext(Dispatchers.IO) { store.moveNode(ref, id, delta) }
                            doc = withContext(Dispatchers.IO) { store.loadDoc(ref) }
                        }
                    },
                )
            }
        }

        // ── 右侧快捷输入抽屉（词表按书独立保存） ──
        if (panel == SidePanel.Glossary) {
            DrawerScaffold(alignEnd = true, onClose = { panel = SidePanel.None }) {
                GlossaryPanel(
                    glossary = currentDoc?.glossary,
                    onInsert = { item ->
                        insertSnippet(item.text)
                        panel = SidePanel.None
                    },
                    onAdd = { item ->
                        scope.launch {
                            val base = currentDoc?.glossary ?: com.qianbi.writer.data.Glossary()
                            val updated = base.withAdded(item)
                            withContext(Dispatchers.IO) { store.saveGlossary(ref, updated) }
                            val current = doc
                            if (current != null) doc = current.copy(glossary = updated)
                        }
                    },
                    onRemove = { item ->
                        scope.launch {
                            val base = currentDoc?.glossary ?: com.qianbi.writer.data.Glossary()
                            val updated = com.qianbi.writer.data.Glossary(base.items.filterNot { it == item })
                            withContext(Dispatchers.IO) { store.saveGlossary(ref, updated) }
                            val current = doc
                            if (current != null) doc = current.copy(glossary = updated)
                        }
                    },
                )
            }
        }
    }

    // ─────────────────────── 对话框 ───────────────────────

    if (fontOpen) {
        AlertDialog(
            onDismissRequest = { fontOpen = false },
            title = { Text("正文字号") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("当前 $fontSp sp（${EditorPrefs.MIN_FONT_SP}–${EditorPrefs.MAX_FONT_SP}）", color = MutedText)
                    Slider(
                        value = fontSp.toFloat(),
                        onValueChange = { raw ->
                            val v = raw.roundToInt().coerceIn(EditorPrefs.MIN_FONT_SP, EditorPrefs.MAX_FONT_SP)
                            if (v != fontSp) {
                                fontSp = v
                                EditorPrefs.setFontSize(context, v)
                            }
                        },
                        valueRange = EditorPrefs.MIN_FONT_SP.toFloat()..EditorPrefs.MAX_FONT_SP.toFloat(),
                        steps = EditorPrefs.MAX_FONT_SP - EditorPrefs.MIN_FONT_SP - 1,
                    )
                    Text("预览", style = MaterialTheme.typography.labelSmall, color = AccentGold)
                    Text(
                        text = "夜色像一块浸了水的黑绒布，风一过就沉甸甸地压下来。",
                        fontFamily = FontFamily.Serif,
                        fontSize = fontSp.sp,
                        lineHeight = EditorPrefs.lineHeightSp(fontSp).sp,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            },
            confirmButton = { TextButton(onClick = { fontOpen = false }) { Text("好") } },
            dismissButton = {
                TextButton(onClick = {
                    fontSp = EditorPrefs.DEFAULT_FONT_SP
                    EditorPrefs.setFontSize(context, fontSp)
                }) { Text("恢复默认") }
            },
        )
    }

    if (infoOpen) {
        BookInfoDialog(
            store = store,
            ref = ref,
            onDismiss = { infoOpen = false },
            onSaved = { updated ->
                infoOpen = false
                ref = updated
                val current = doc
                if (current != null) doc = current.copy(ref = updated, meta = updated.meta)
                onBookUpdated(updated)
            },
        )
    }

    nodeEditTarget?.let { editingNode ->
        NodeEditDialog(
            heading = "编辑「${editingNode.title}」",
            title = editingNode.title,
            summary = editingNode.summary,
            createdAt = editingNode.createdAt,
            modifiedAt = editingNode.modifiedAt,
            onDismiss = { nodeEditTarget = null },
            onConfirm = { title, summary ->
                nodeEditTarget = null
                scope.launch {
                    withContext(Dispatchers.IO) { store.updateNode(ref, editingNode.id, title, summary) }
                    reload(keepSelection = selectedId)
                }
            },
        )
    }

    if (moveOpen && currentDoc != null && selectedId != null) {
        MoveNodeDialog(
            doc = currentDoc,
            nodeId = selectedId!!,
            onDismiss = { moveOpen = false },
            onConfirm = { target ->
                moveOpen = false
                val id = selectedId
                scope.launch {
                    if (id != null) {
                        withContext(Dispatchers.IO) { store.reparentNode(ref, id, target) }
                        reload(keepSelection = id)
                    }
                }
            },
        )
    }

    createPrompt?.let { (parentId, kind) ->
        TextPromptDialog(
            title = if (kind == "子级") "新建子级" else "新建同级",
            label = "题目",
            initial = "",
            hint = "「目录（卷）」用来分组、只能写简介；「正文（章）」才能写正文。章可以加在任何一级下面。",
            kindOptions = true,
            defaultKind = NodeKind.Group,
            onDismiss = { createPrompt = null },
            onConfirm = { title, nodeKind ->
                createPrompt = null
                scope.launch {
                    val info = withContext(Dispatchers.IO) { store.addNode(ref, parentId, title, nodeKind) }
                    val fresh = withContext(Dispatchers.IO) { store.loadDoc(ref) }
                    doc = fresh
                    selectNode(info.id)
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

/** 正文字数（不计空白字符）。 */
internal fun countChars(text: String): Int = text.count { !it.isWhitespace() }

/** 千分位，字数多了也好读。 */
internal fun formatCount(value: Int): String {
    val raw = value.toString()
    if (raw.length <= 4) return raw
    val sb = StringBuilder()
    var grouped = 0
    for (i in raw.indices.reversed()) {
        sb.append(raw[i])
        grouped++
        if (grouped % 3 == 0 && i != 0) sb.append(',')
    }
    return sb.reverse().toString()
}

private fun doUndo(history: EditorHistory, value: TextFieldValue, apply: (TextFieldValue) -> Unit) {
    val previous = history.undo(EditorHistory.Snapshot(value.text, value.selection.start)) ?: return
    apply(TextFieldValue(previous.text, TextRange(previous.selection.coerceIn(0, previous.text.length))))
}

private fun doRedo(history: EditorHistory, value: TextFieldValue, apply: (TextFieldValue) -> Unit) {
    val next = history.redo(EditorHistory.Snapshot(value.text, value.selection.start)) ?: return
    apply(TextFieldValue(next.text, TextRange(next.selection.coerceIn(0, next.text.length))))
}

/** 抽屉外壳：半透明遮罩 + 贴边面板，点遮罩关闭。 */
@Composable
private fun DrawerScaffold(
    alignEnd: Boolean = false,
    onClose: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.45f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { onClose() }
        )
        Surface(
            modifier = Modifier
                .align(if (alignEnd) Alignment.CenterEnd else Alignment.CenterStart)
                .width(300.dp)
                .fillMaxHeight()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { },
            color = PanelColor,
        ) {
            content()
        }
    }
}

/** 工具条上的胶囊按钮。 */
@Composable
private fun ToolButton(
    label: String,
    enabled: Boolean,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    val color = when {
        !enabled -> MutedText.copy(alpha = 0.4f)
        danger -> MaterialTheme.colorScheme.error
        else -> AccentMint
    }
    Surface(
        shape = RoundedCornerShape(50),
        color = PanelColorAlt,
        modifier = Modifier.clickable(enabled = enabled, onClick = onClick),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = color,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

/** 顶栏上的紧凑按钮（撤销 / 重做）。 */
@Composable
private fun CompactAction(label: String, enabled: Boolean, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
        modifier = Modifier.widthIn(min = 40.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (enabled) AccentMint else MutedText.copy(alpha = 0.35f),
            maxLines = 1,
        )
    }
}

/** 目录树：多级嵌套、可折叠、每行带操作菜单。编辑页与书本页共用。 */
@Composable
internal fun TreePanel(
    doc: BookDoc?,
    selectedId: String?,
    onSelect: (String) -> Unit,
    onAddRoot: () -> Unit,
    onAddChild: (String) -> Unit,
    onAddSibling: (String) -> Unit,
    onEdit: (String) -> Unit,
    onMove: (String) -> Unit,
    onDelete: (String) -> Unit,
    onShift: (String, Int) -> Unit,
) {
    val collapsed = remember { mutableStateMapOf<String, Boolean>() }

    Column(modifier = Modifier.fillMaxSize()) {
        // 目录头：标题 + 唯一的「新卷/章」入口（新增章节统一在目录里做）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "目录（${doc?.rowCount ?: 0} 个节点）",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = AccentGold,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onAddRoot) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(2.dp))
                Text("新卷/章", style = MaterialTheme.typography.labelMedium)
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
        // 图例：一眼看懂底色含义
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 14.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(9.dp).height(9.dp).background(AccentGold.copy(alpha = 0.75f), RoundedCornerShape(2.dp)))
            Spacer(Modifier.width(5.dp))
            Text("卷 · 只能写简介", style = MaterialTheme.typography.labelSmall, color = MutedText)
            Spacer(Modifier.width(14.dp))
            Box(Modifier.width(9.dp).height(9.dp).background(AccentMint.copy(alpha = 0.75f), RoundedCornerShape(2.dp)))
            Spacer(Modifier.width(5.dp))
            Text("章 · 可写正文", style = MaterialTheme.typography.labelSmall, color = MutedText)
        }

        if (doc != null && doc.nodes.isNotEmpty()) {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                val rows = ArrayList<TreeRow>()
                var suppressBelow = -1
                for (row in doc.flatten()) {
                    if (suppressBelow >= 0 && row.depth > suppressBelow) continue
                    suppressBelow = -1
                    rows.add(row)
                    if (collapsed[row.node.id] == true) suppressBelow = row.depth
                }

                items(items = rows, key = { it.node.id }) { row ->
                    val node = row.node
                    val hasChildren = node.children.isNotEmpty()
                    val isSelected = node.id == selectedId
                    var rowMenu by remember { mutableStateOf(false) }

                    val isGroup = node.kind == NodeKind.Group
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                when {
                                    isSelected -> MaterialTheme.colorScheme.surfaceVariant
                                    isGroup -> AccentGold.copy(alpha = 0.10f)   // 卷：只能写简介
                                    else -> AccentMint.copy(alpha = 0.06f)      // 章：可写正文
                                }
                            )
                            .clickable { onSelect(node.id) }
                            .padding(start = (6 + row.depth * 14).dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // 左侧色条：金色=卷（不可写正文），薄荷=章（可写正文）
                        Box(
                            modifier = Modifier
                                .padding(end = 6.dp)
                                .width(3.dp)
                                .height(26.dp)
                                .background(
                                    if (isGroup) AccentGold.copy(alpha = 0.75f) else AccentMint.copy(alpha = 0.75f),
                                    RoundedCornerShape(2.dp),
                                )
                        )
                        if (hasChildren) {
                            IconButton(
                                onClick = { collapsed[node.id] = collapsed[node.id] != true },
                                modifier = Modifier.width(26.dp),
                            ) {
                                Icon(
                                    if (collapsed[node.id] == true) Icons.Default.KeyboardArrowRight
                                    else Icons.Default.KeyboardArrowDown,
                                    contentDescription = "展开/折叠",
                                    tint = MutedText,
                                )
                            }
                        } else {
                            Spacer(Modifier.width(26.dp))
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = node.title,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (isSelected) AccentGold else MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            val summaryLine = node.summary.replace('\n', ' ').replace('\r', ' ').trim()
                            val kindTag = if (node.kind == NodeKind.Chapter) "章" else "卷"
                            Text(
                                text = if (summaryLine.isNotEmpty()) "${kindTag} · ${summaryLine}" else buildString {
                                    append(kindTag)
                                    append(" · ")
                                    append(node.id.take(8))
                                    if (hasChildren) append(" · ${node.children.size} 个子级")
                                },
                                maxLines = 2,
                                style = MaterialTheme.typography.labelSmall,
                                color = MutedText.copy(alpha = 0.7f),
                            )
                        }
                        Box {
                            IconButton(onClick = { rowMenu = true }, modifier = Modifier.width(32.dp)) {
                                Icon(Icons.Default.MoreVert, contentDescription = "章节操作", tint = MutedText)
                            }
                            DropdownMenu(expanded = rowMenu, onDismissRequest = { rowMenu = false }) {
                                DropdownMenuItem(text = { Text("新建子级") }, onClick = { rowMenu = false; onAddChild(node.id) })
                                DropdownMenuItem(text = { Text("新建同级") }, onClick = { rowMenu = false; onAddSibling(node.id) })
                                DropdownMenuItem(text = { Text("改题 / 简介") }, onClick = { rowMenu = false; onEdit(node.id) })
                                DropdownMenuItem(text = { Text("上移") }, onClick = { rowMenu = false; onShift(node.id, -1) })
                                DropdownMenuItem(text = { Text("下移") }, onClick = { rowMenu = false; onShift(node.id, 1) })
                                DropdownMenuItem(text = { Text("移动到…") }, onClick = { rowMenu = false; onMove(node.id) })
                                DropdownMenuItem(
                                    text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                                    leadingIcon = {
                                        Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                                    },
                                    onClick = { rowMenu = false; onDelete(node.id) },
                                )
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        } else if (doc == null) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = AccentGold)
                Spacer(Modifier.width(10.dp))
                Text("正在读取目录…", style = MaterialTheme.typography.bodySmall, color = MutedText)
            }
        } else {
            Text(
                text = "还没有章节。点上面的「新卷/章」新建第一卷 / 第一章。",
                style = MaterialTheme.typography.bodySmall,
                color = MutedText,
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}

/** 右侧快捷输入：预定义角色名 / 专用名词，点一下插到光标处。词表按书独立。 */
@Composable
private fun GlossaryPanel(
    glossary: com.qianbi.writer.data.Glossary?,
    onInsert: (GlossaryItem) -> Unit,
    onAdd: (GlossaryItem) -> Unit,
    onRemove: (GlossaryItem) -> Unit,
) {
    var addOpen by remember { mutableStateOf(false) }
    val items = glossary?.items.orEmpty()

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "本书词表",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = AccentGold,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { addOpen = true }) {
                Icon(Icons.Default.Add, contentDescription = "新增词条", tint = AccentMint)
            }
        }
        Text(
            text = "共 ${items.size} 条 · 每本书一份，导出 .book 时一起打包",
            style = MaterialTheme.typography.labelSmall,
            color = MutedText,
            modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 8.dp),
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

        if (items.isEmpty()) {
            Text(
                text = "把常出现的角色名、地名、设定词加进来，写作时点一下就插入，不用反复打字。\n" +
                    "词表跟着这本书走，换一本书就是另一份。",
                style = MaterialTheme.typography.bodySmall,
                color = MutedText,
                modifier = Modifier.padding(16.dp),
            )
            return@Column
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            val groups = glossary?.groups().orEmpty()
            groups.forEach { (groupName, groupItems) ->
                item(key = "g-$groupName") {
                    Text(
                        text = groupName,
                        style = MaterialTheme.typography.labelMedium,
                        color = AccentMint,
                        modifier = Modifier.padding(start = 14.dp, top = 12.dp, bottom = 4.dp),
                    )
                }
                items(items = groupItems, key = { "i-${groupName}-${it.name}-${it.text}" }) { entry ->
                    var removeOpen by remember { mutableStateOf(false) }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onInsert(entry) }
                            .padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(entry.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                            if (entry.text != entry.name) {
                                Text(
                                    entry.text,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MutedText,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        Box {
                            IconButton(onClick = { removeOpen = true }, modifier = Modifier.width(32.dp)) {
                                Icon(Icons.Default.Clear, contentDescription = "删除词条", tint = MutedText)
                            }
                            DropdownMenu(expanded = removeOpen, onDismissRequest = { removeOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text("删除这个词条") },
                                    onClick = {
                                        removeOpen = false
                                        onRemove(entry)
                                    },
                                )
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    if (addOpen) {
        GlossaryItemDialog(
            onDismiss = { addOpen = false },
            onConfirm = { item ->
                addOpen = false
                onAdd(item)
            },
        )
    }
}
