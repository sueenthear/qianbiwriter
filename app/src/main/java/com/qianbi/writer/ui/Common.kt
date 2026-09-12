package com.qianbi.writer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.qianbi.writer.data.BookMeta
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ─────────────────────────── 搜索解析 ───────────────────────────

/**
 * 搜索条件：
 * - `#玄幻`、`#玄幻 #修仙`、`#玄幻#修仙` 都算标签筛选，多个标签之间是「与」关系；
 * - 其余文字按书名 / 作者 / 简介模糊匹配。
 */
data class SearchQuery(val text: String, val tags: List<String>) {
    val isEmpty: Boolean get() = text.isEmpty() && tags.isEmpty()
}

object BookSearch {
    private val TAG_RE = Regex("#([^\\s#]+)")

    fun parse(raw: String): SearchQuery {
        val tags = TAG_RE.findAll(raw).map { it.groupValues[1].trim() }.filter { it.isNotEmpty() }.toList()
        val text = TAG_RE.replace(raw, " ").trim()
        return SearchQuery(text, tags)
    }

    fun matches(meta: BookMeta, query: SearchQuery): Boolean {
        if (query.tags.isNotEmpty()) {
            val owned = meta.tags.map { it.lowercase(Locale.getDefault()) }
            val allHit = query.tags.all { want ->
                val w = want.lowercase(Locale.getDefault())
                owned.any { it == w || it.contains(w) }
            }
            if (!allHit) return false
        }
        if (query.text.isNotEmpty()) {
            val needle = query.text.lowercase(Locale.getDefault())
            val hay = listOf(meta.title, meta.author, meta.summary)
            if (hay.none { it.lowercase(Locale.getDefault()).contains(needle) }) return false
        }
        return true
    }
}

// ─────────────────────────── 时间格式 ───────────────────────────

fun formatDateTime(millis: Long): String =
    if (millis <= 0L) "—" else SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(millis))

fun formatClock(millis: Long): String =
    if (millis <= 0L) "—" else SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(millis))

// ─────────────────────────── 通用小组件 ───────────────────────────

val PanelColor = Color(0xFF1B222B)
val PanelColorAlt = Color(0xFF222B36)
val AccentGold = Color(0xFFE0B36A)
val AccentMint = Color(0xFF9FD8C0)
val MutedText = Color(0xFF8C9AA8)

/** 小标签。 */
@Composable
fun TagChip(
    text: String,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val bg = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = fg,
            maxLines = 1,
        )
    }
}

/** 分区标题（左侧竖条 + 标题）。 */
@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .background(AccentGold, RoundedCornerShape(2.dp))
                .padding(vertical = 8.dp)
                .padding(horizontal = 1.5.dp)
        )
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = AccentGold,
        )
    }
}

/** 空状态占位。 */
@Composable
fun EmptyHint(title: String, detail: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = MutedText)
        Text(
            detail,
            style = MaterialTheme.typography.bodySmall,
            color = MutedText.copy(alpha = 0.7f),
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/** 键值一行，用于信息展示。 */
@Composable
fun KeyValueRow(key: String, value: String) {
    Row(modifier = Modifier.padding(vertical = 3.dp)) {
        Text(
            text = key,
            style = MaterialTheme.typography.bodySmall,
            color = MutedText,
            modifier = Modifier.padding(end = 12.dp),
        )
        Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
    }
}
