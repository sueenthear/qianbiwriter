package com.qianbi.writer.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.qianbi.writer.data.BookDoc
import com.qianbi.writer.data.BookRef
import com.qianbi.writer.data.BookStore
import com.qianbi.writer.data.GlossaryItem
import com.qianbi.writer.data.ImageUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 新建书本：书名 + 作者 + 标签（简介、封面进去后再补）。 */
@Composable
fun CreateBookDialog(
    onDismiss: () -> Unit,
    onCreate: (title: String, author: String, tags: List<String>) -> Unit,
) {
    var title by remember { mutableStateOf("") }
    var author by remember { mutableStateOf("") }
    var tagDraft by remember { mutableStateOf("") }
    var tags by remember { mutableStateOf(emptyList<String>()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建小说") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("书名（必填）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = author,
                    onValueChange = { author = it },
                    label = { Text("作者") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                TagEditor(
                    tags = tags,
                    draft = tagDraft,
                    onDraftChange = { tagDraft = it },
                    onTagsChange = { tags = it },
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = title.isNotBlank(),
                onClick = { onCreate(title.trim(), author.trim(), tags) },
            ) { Text("创建并开始写") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 书本信息 / 设置：封面、书名、作者、简介、标签组，以及创建与最后改动时间。 */
@Composable
fun BookInfoDialog(
    store: BookStore,
    ref: BookRef,
    onDismiss: () -> Unit,
    onSaved: (BookRef) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var current by remember { mutableStateOf(ref) }
    var title by remember { mutableStateOf(ref.meta.title) }
    var author by remember { mutableStateOf(ref.meta.author) }
    var summary by remember { mutableStateOf(ref.meta.summary) }
    var tags by remember { mutableStateOf(ref.meta.tags) }
    var tagDraft by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var coverVersion by remember { mutableStateOf(0) }

    val cover by produceState<ImageBitmap?>(null, current.key, current.meta.cover, coverVersion) {
        value = withContext(Dispatchers.IO) {
            store.coverFile(current)?.readBytes()?.let { ImageUtil.load(it, 540) }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            val updated = withContext(Dispatchers.IO) {
                val raw = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                val jpeg = raw?.let { ImageUtil.decode(it, 1080) }
                if (jpeg == null) null else store.setCover(current, jpeg)
            }
            busy = false
            if (updated != null) {
                current = updated
                coverVersion++
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("书本信息") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(width = 84.dp, height = 116.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(PanelColorAlt),
                        contentAlignment = Alignment.Center,
                    ) {
                        val bitmap = cover
                        if (bitmap != null) {
                            Image(
                                bitmap = bitmap,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else {
                            Text("无封面", style = MaterialTheme.typography.labelSmall, color = MutedText)
                        }
                    }
                    Column(
                        modifier = Modifier
                            .padding(start = 12.dp)
                            .weight(1f),
                    ) {
                        TextButton(onClick = { picker.launch("image/*") }, enabled = !busy) {
                            Text(if (cover == null) "选择封面图片" else "更换封面")
                        }
                        if (cover != null) {
                            TextButton(
                                enabled = !busy,
                                onClick = {
                                    scope.launch {
                                        busy = true
                                        current = withContext(Dispatchers.IO) { store.clearCover(current) }
                                        busy = false
                                        coverVersion++
                                    }
                                },
                            ) { Text("移除封面", color = MaterialTheme.colorScheme.error) }
                        }
                    }
                }

                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("书名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = author,
                    onValueChange = { author = it },
                    label = { Text("作者") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = summary,
                    onValueChange = { summary = it },
                    label = { Text("简介") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
                TagEditor(
                    tags = tags,
                    draft = tagDraft,
                    onDraftChange = { tagDraft = it },
                    onTagsChange = { tags = it },
                )
                Column(modifier = Modifier.padding(top = 4.dp)) {
                    KeyValueRow("创建时间", formatDateTime(current.meta.createdAt))
                    KeyValueRow("最后改动", formatDateTime(current.meta.modifiedAt))
                    KeyValueRow("文件夹", current.dir.name)
                }
                if (busy) {
                    Text("处理中…", style = MaterialTheme.typography.labelSmall, color = AccentMint)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = title.isNotBlank() && !busy,
                onClick = {
                    scope.launch {
                        busy = true
                        val meta = current.meta.copy(
                            title = title.trim(),
                            author = author.trim(),
                            summary = summary.trim(),
                            tags = tags,
                        )
                        val updated = withContext(Dispatchers.IO) { store.updateBook(current, meta) }
                        busy = false
                        onSaved(updated)
                    }
                },
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 标签编辑：输入框 + 「添加」+ 可删除的标签片。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TagEditor(
    tags: List<String>,
    draft: String,
    onDraftChange: (String) -> Unit,
    onTagsChange: (List<String>) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = draft,
                onValueChange = onDraftChange,
                label = { Text("标签组") },
                placeholder = { Text("空格 / 逗号分隔，如 玄幻 修仙") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = {
                val fresh = splitTags(draft).filterNot { t -> tags.any { it.equals(t, true) } }
                if (fresh.isNotEmpty()) onTagsChange(tags + fresh)
                onDraftChange("")
            }) { Text("添加") }
        }
        if (tags.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                tags.forEach { tag ->
                    TagChip(text = "#$tag ×") {
                        onTagsChange(tags.filterNot { it == tag })
                    }
                }
            }
        }
    }
}

private fun splitTags(raw: String): List<String> =
    raw.split(' ', ',', '，', '、', ';', '；', '#')
        .map { it.trim() }
        .filter { it.isNotEmpty() }

/** 导出 .book：可留空口令（明文包），也可设口令（AES-256-CTR + PBKDF2）。 */
@Composable
fun ExportDialog(
    title: String,
    onDismiss: () -> Unit,
    onConfirm: (password: String?) -> Unit,
) {
    var password by remember { mutableStateOf("") }
    var confirmText by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("导出《$title》") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "导出的 .book 包就是这本书的文件夹结构（zip）。留空口令即不加密；填了口令则整包加密，" +
                        "之后导入时必须输入同一口令。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MutedText,
                )
                PasswordField("口令（可留空）", password, { password = it })
                if (password.isNotEmpty()) {
                    PasswordField("再输一次", confirmText, { confirmText = it })
                }
                error?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (password.isNotEmpty() && password != confirmText) {
                    error = "两次输入的口令不一致"
                } else {
                    onConfirm(password.ifEmpty { null })
                }
            }) { Text("选择保存位置") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 导入加密包时输入口令。 */
@Composable
fun PasswordDialog(
    title: String,
    hint: String,
    password: String,
    onPasswordChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(hint, style = MaterialTheme.typography.bodySmall, color = MutedText)
                PasswordField("口令", password, onPasswordChange)
            }
        },
        confirmButton = {
            TextButton(enabled = password.isNotEmpty(), onClick = onConfirm) { Text("解密并导入") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun PasswordField(label: String, value: String, onChange: (String) -> Unit) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (visible) androidx.compose.ui.text.input.VisualTransformation.None
        else PasswordVisualTransformation(),
        trailingIcon = {
            TextButton(onClick = { visible = !visible }) {
                Text(if (visible) "隐藏" else "显示", style = MaterialTheme.typography.labelSmall)
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

/** 节点的题目 / 简介 / 时间信息。 */
@Composable
fun NodeEditDialog(
    heading: String,
    title: String,
    summary: String,
    createdAt: Long,
    modifiedAt: Long,
    onDismiss: () -> Unit,
    onConfirm: (title: String, summary: String) -> Unit,
) {
    var draftTitle by remember { mutableStateOf(title) }
    var draftSummary by remember { mutableStateOf(summary) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(heading) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = draftTitle,
                    onValueChange = { draftTitle = it },
                    label = { Text("本级题目") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = draftSummary,
                    onValueChange = { draftSummary = it },
                    label = { Text("本级简介") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
                KeyValueRow("创建时间", formatDateTime(createdAt))
                KeyValueRow("最后改动", formatDateTime(modifiedAt))
            }
        },
        confirmButton = {
            TextButton(
                enabled = draftTitle.isNotBlank(),
                onClick = { onConfirm(draftTitle.trim(), draftSummary.trim()) },
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 把节点移动到别的目录下（正文根也算一个目标）。 */
@Composable
fun MoveNodeDialog(
    doc: BookDoc,
    nodeId: String,
    onDismiss: () -> Unit,
    onConfirm: (newParentId: String?) -> Unit,
) {
    val blocked = remember(doc, nodeId) { descendantsOf(doc, nodeId) + nodeId }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("移动到…") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                MoveOption("《${doc.meta.title}》正文根", 0) { onConfirm(null) }
                doc.flatten().forEach { row ->
                    if (row.node.id in blocked) return@forEach
                    MoveOption(row.node.title, row.depth + 1) { onConfirm(row.node.id) }
                }
                if (doc.nodes.isEmpty()) {
                    Text("还没有其他目录", style = MaterialTheme.typography.bodySmall, color = MutedText)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun MoveOption(label: String, depth: Int, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Spacer(Modifier.width((depth * 14).dp))
        Text(label, maxLines = 1, modifier = Modifier.weight(1f))
    }
}

private fun descendantsOf(doc: BookDoc, rootId: String): Set<String> {
    val out = HashSet<String>()
    fun walk(id: String) {
        doc.nodes[id]?.children?.forEach { child ->
            if (out.add(child)) walk(child)
        }
    }
    walk(rootId)
    return out
}

/** 新增 / 编辑一条快捷输入词条。 */
@Composable
fun GlossaryItemDialog(
    existing: GlossaryItem? = null,
    onDismiss: () -> Unit,
    onConfirm: (GlossaryItem) -> Unit,
) {
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var text by remember { mutableStateOf(existing?.text ?: "") }
    var group by remember { mutableStateOf(existing?.group ?: "角色") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "新增快捷词条" else "编辑快捷词条") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名称（工具栏上显示）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("插入文本（留空则插入名称）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = group,
                    onValueChange = { group = it },
                    label = { Text("分组（如 角色 / 地名 / 术语）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = {
                    onConfirm(
                        GlossaryItem(
                            name = name.trim(),
                            text = text.trim().ifEmpty { name.trim() },
                            group = group.trim().ifEmpty { "默认" },
                        )
                    )
                },
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
