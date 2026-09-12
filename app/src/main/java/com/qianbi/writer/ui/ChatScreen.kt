package com.qianbi.writer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class Speaker { User, Assistant }

private data class ChatMessage(val speaker: Speaker, val text: String, val isError: Boolean = false)

private const val GREETING = "你好，我是智能助手。可以问我问题，也可以让我帮你写点东西。"

/** 假装的 API 报错：看起来就是这个聊天应用自己的密钥/配额出了问题。 */
private val API_ERRORS = listOf(
    "请求失败 · 401 Unauthorized\n" +
        "{\"error\":{\"message\":\"Incorrect API key provided. You can find your API key at…\"," +
        "\"type\":\"invalid_request_error\",\"code\":\"invalid_api_key\"}}",
    "请求失败 · 429 Too Many Requests\n" +
        "{\"error\":{\"message\":\"Rate limit reached for gpt-4o-mini on requests per min\"," +
        "\"type\":\"requests\",\"code\":\"rate_limit_exceeded\"}}",
    "请求失败 · 400 Bad Request\n" +
        "{\"error\":{\"message\":\"The model does not exist or you do not have access to it\"," +
        "\"type\":\"invalid_request_error\",\"code\":\"model_not_found\"}}",
)

/** 闲聊时给一句笼统但像样的回复，让这个界面看起来真的能用。 */
private val SMALL_TALK = listOf(
    "这个问题可以拆成三步看：先确认目标，再列出约束，最后逐项验证。你想先从哪一步展开？",
    "我理解你的意思。简单说，关键是把大问题切小，然后一步步确认。需要我给你举个具体例子吗？",
    "好的，我记下了。再补充一点背景（场景或限制），我能给出更贴合的答复。",
    "可以。先给一个能跑起来的最小版本，再按需要加东西 —— 这样出问题时也好定位。",
)

private const val HEX = "0123456789abcdef"

/**
 * 伪装模式的入口界面：**看起来就是一个普通的 AI 聊天助手**。
 *
 * - 输入口令 → [onUnlock]，进入真正的书架；
 * - 输入"像密钥"的一串字符（不含中文、不含空格）但不对 → 显示 **API 报错**，
 *   外人会以为这个聊天应用自己的密钥失效了，而不是"有个上锁的应用"；
 * - 其它带中文或空格的普通聊天 → 给一句笼统回复，界面保持可用。
 */
@Composable
fun ChatScreen(password: String, onUnlock: () -> Unit) {
    val scope = rememberCoroutineScope()
    var messages by remember { mutableStateOf(listOf(ChatMessage(Speaker.Assistant, GREETING))) }
    var input by remember { mutableStateOf("") }
    var typing by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    fun send(raw: String) {
        val text = raw.trim()
        if (text.isEmpty() || typing) return
        messages = messages + ChatMessage(Speaker.User, text)
        input = ""
        scope.launch {
            typing = true
            delay(650)
            typing = false
            when {
                text == password -> onUnlock()
                looksLikeKey(text) -> messages =
                    messages + ChatMessage(Speaker.Assistant, apiError(), isError = true)
                else -> messages = messages + ChatMessage(Speaker.Assistant, SMALL_TALK.random())
            }
        }
    }

    LaunchedEffect(messages.size, typing) {
        val last = messages.size - 1 + if (typing) 1 else 0
        if (last >= 0) listState.animateScrollToItem(last)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF0E1116), Color(0xFF131A24), Color(0xFF0E1116))
                )
            )
    ) {
        // ── 顶栏 ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(start = 16.dp, end = 6.dp, top = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF2E9E7B)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "AI",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
            }
            Column(
                modifier = Modifier
                    .padding(start = 10.dp)
                    .weight(1f)
            ) {
                Text(
                    "智能助手",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
                Text(
                    "在线 · gpt-4o-mini",
                    style = MaterialTheme.typography.labelSmall,
                    color = AccentMint,
                )
            }
            IconButton(onClick = {
                messages = listOf(ChatMessage(Speaker.Assistant, GREETING))
            }) {
                Icon(Icons.Default.Refresh, contentDescription = "清空对话", tint = MutedText)
            }
        }

        // ── 消息 ──
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            itemsIndexed(messages) { _, msg -> Bubble(msg) }
            if (typing) item { TypingBubble() }
            if (messages.size == 1 && !typing) {
                item { Suggestions(onPick = { send(it) }) }
            }
        }

        // ── 输入栏 ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // IME 与导航栏取并集：两个 inset 相加会把输入栏顶得太高
                .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))
                .padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("发消息…", color = MutedText) },
                maxLines = 4,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send(input) }),
            )
            IconButton(onClick = { send(input) }, enabled = input.isNotBlank()) {
                Icon(
                    Icons.Default.Send,
                    contentDescription = "发送",
                    tint = if (input.isNotBlank()) AccentGold else MutedText,
                )
            }
        }
    }
}

@Composable
private fun Bubble(msg: ChatMessage) {
    val fromUser = msg.speaker == Speaker.User
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (fromUser) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = when {
                msg.isError -> Color(0xFF2A1416)
                fromUser -> MaterialTheme.colorScheme.primary
                else -> PanelColorAlt
            },
            modifier = Modifier.widthIn(max = 300.dp),
        ) {
            Text(
                text = msg.text,
                style = if (msg.isError) {
                    MaterialTheme.typography.labelSmall
                } else {
                    MaterialTheme.typography.bodyMedium
                },
                fontFamily = if (msg.isError) FontFamily.Monospace else FontFamily.Default,
                color = when {
                    msg.isError -> Color(0xFFFFB4AB)
                    fromUser -> MaterialTheme.colorScheme.onPrimary
                    else -> MaterialTheme.colorScheme.onSurface
                },
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun TypingBubble() {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Surface(shape = RoundedCornerShape(14.dp), color = PanelColorAlt) {
            Text(
                "正在输入…",
                style = MaterialTheme.typography.labelMedium,
                color = MutedText,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun Suggestions(onPick: (String) -> Unit) {
    val ideas = listOf("帮我写一段自我介绍", "解释一下什么是相对论", "推荐三本科幻小说")
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ideas.forEach { idea ->
            Surface(
                shape = RoundedCornerShape(50),
                color = PanelColorAlt,
                modifier = Modifier.clickable { onPick(idea) },
            ) {
                Text(
                    idea,
                    style = MaterialTheme.typography.labelMedium,
                    color = AccentMint,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                )
            }
        }
    }
}

/** 不含中文、不含空格的一串字符 → 当成"在输密钥/口令"。 */
private fun looksLikeKey(text: String): Boolean =
    text.isNotEmpty() && text.none { it.isWhitespace() || it.code >= 0x2E80 }

private fun apiError(): String =
    API_ERRORS.random() + "\nrequest_id: req_" + (1..20).map { HEX.random() }.joinToString("")
