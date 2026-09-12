package com.qianbi.writer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.qianbi.writer.data.NodeKind

/**
 * 新建目录节点：输入题目 + 选类型。
 *
 * - **目录（卷）**：用来分组，只能写简介，不能写正文；
 * - **正文（章）**：只有这一级能写正文；
 * - 「章」可以加在任何一级下面（卷里、卷的卷里，甚至别的章下面），层级不限。
 */
@Composable
fun TextPromptDialog(
    title: String,
    label: String,
    initial: String = "",
    hint: String? = null,
    confirmLabel: String = "创建",
    kindOptions: Boolean = false,
    defaultKind: NodeKind = NodeKind.Chapter,
    onDismiss: () -> Unit,
    onConfirm: (String, NodeKind) -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    var kind by remember { mutableStateOf(defaultKind) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text(label) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (kindOptions) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        KindOption("目录（卷）", kind == NodeKind.Group) { kind = NodeKind.Group }
                        KindOption("正文（章）", kind == NodeKind.Chapter) { kind = NodeKind.Chapter }
                    }
                }
                hint?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MutedText)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = value.isNotBlank(), onClick = { onConfirm(value.trim(), kind) }) {
                Text(confirmLabel)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun KindOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) MaterialTheme.colorScheme.primary else PanelColorAlt)
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 7.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        )
    }
}
