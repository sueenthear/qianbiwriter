package com.qianbi.writer.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.qianbi.writer.data.WebPrefs
import com.qianbi.writer.web.KeepAlive
import com.qianbi.writer.web.WebLog
import com.qianbi.writer.web.WebServer
import com.qianbi.writer.web.WebService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Web 协作控制台 —— 一个**独立页面**，不是一个可以随手关掉的对话框。
 *
 * 语义上这里等于「把编辑权交给电脑」：
 * - 开着控制台 = 服务器在跑，手机这边不写书，两头不会同时改同一节；
 * - 只有点底部的「关闭 Web 协作」才会退出（返回键不退，只给一句提醒）；
 * - 服务器若从别处停了（通知栏的「停止共享」、被系统回收），控制台也会跟着退出去，
 *   免得停在一个已经没有意义的页面上。
 */
@Composable
fun WebConsoleScreen(onClosed: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var running by remember { mutableStateOf(WebService.isRunning()) }
    var urls by remember { mutableStateOf(WebService.urls()) }
    var logs by remember { mutableStateOf<List<WebLog.Entry>>(emptyList()) }
    var port by remember { mutableStateOf(WebPrefs.port(context).toString()) }
    var code by remember { mutableStateOf(WebPrefs.accessCode(context)) }
    var ignoring by remember { mutableStateOf(KeepAlive.ignoringBatteryOptimizations(context)) }
    var hint by remember { mutableStateOf<String?>(null) }
    // 服务器"曾经起来过"才谈得上"停了要退出"：刚进来那一两秒还在启动，不能误判
    var everRan by remember { mutableStateOf(WebService.isRunning()) }

    var notificationGranted by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> notificationGranted = granted }

    LaunchedEffect(Unit) {
        while (true) {
            running = WebService.isRunning()
            if (running) everRan = true
            urls = WebService.urls()
            logs = WebLog.snapshot()
            ignoring = KeepAlive.ignoringBatteryOptimizations(context)
            WebService.lastError?.let { hint = it }
            delay(1000)
        }
    }

    LaunchedEffect(running, everRan) {
        if (everRan && !running) {
            delay(400)
            onClosed()
        }
    }

    // 返回键不退出控制台：控制台在，服务器就在
    BackHandler { hint = "先点下面的「关闭 Web 协作」，才能回书架写书" }

    fun shutDown() {
        WebPrefs.setEnabled(context, false)
        WebService.stop(context)
        onClosed()
    }

    /** 改端口 / 访问码要重启才生效：先停下，喘口气再起。 */
    fun restartServer() {
        if (!WebService.isRunning()) return
        WebService.stop(context)
        scope.launch {
            delay(400)
            WebService.start(context)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        // ── 顶栏 ──
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 10.dp),
        ) {
            Text(
                text = "Web 协作控制台",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Black,
                color = AccentGold,
            )
            Text(
                text = if (running) "服务器运行中 · 手机这边暂不写书" else "服务器已停止",
                style = MaterialTheme.typography.bodySmall,
                color = if (running) AccentMint else MutedText,
            )
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!notificationGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        "没有通知权限：前台服务的常驻通知不会显示，也就没有通知栏那个「停止共享」入口。",
                        style = MaterialTheme.typography.labelSmall,
                        color = AccentGold,
                    )
                    TextButton(onClick = {
                        notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }) { Text("授予通知权限", color = AccentGold) }
                }
            }

            // ── 接入地址 ──
            SectionHeader("接入地址")
            if (urls.isEmpty()) {
                Text(
                    if (running) {
                        "还没拿到局域网地址。确认手机连着 WiFi 或者开着热点。"
                    } else {
                        "服务器已停止，没有可接入的地址。"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = AccentGold,
                )
            } else {
                urls.forEach { url ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = url,
                            style = MaterialTheme.typography.bodySmall,
                            color = AccentMint,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = {
                            copyToClipboard(context, url)
                            hint = "地址已复制，可以发到电脑上打开"
                        }) { Text("复制") }
                    }
                }
                Text(
                    "在电脑浏览器里打开任意一条，链接里的 ?t= 就是访问码。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MutedText,
                )
                Text(
                    "电脑上能看到哪些书，由每本书简介页 ⋮ 里的「Web 共享」决定（想改就先关掉这个控制台）。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MutedText,
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

            // ── 电脑端动态 ──
            SectionHeader("电脑端动态")
            if (logs.isEmpty()) {
                Text(
                    "还没有电脑连上来。在电脑浏览器打开上面的地址，这里会实时显示它做了什么。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MutedText,
                )
            } else {
                logs.take(14).forEach { entry -> LogRow(entry) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "共 ${logs.size} 条 · 只留在内存里，关闭服务器就清空",
                        style = MaterialTheme.typography.labelSmall,
                        color = MutedText,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = {
                        WebLog.clear()
                        logs = emptyList()
                    }) { Text("清空") }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

            // ── 访问码 ──
            SectionHeader("访问码")
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = code,
                    onValueChange = { raw -> code = sanitizeCodeInput(raw) },
                    label = { Text("自定义访问码") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { code = WebPrefs.newAccessCode() }) { Text("换一个") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    enabled = code.isNotBlank(),
                    onClick = {
                        val applied = WebPrefs.setAccessCode(context, code)
                        code = applied
                        restartServer()
                        hint = "访问码已保存：$applied"
                    },
                ) { Text("保存访问码") }
                Text(
                    "用字母数字，别用中文",
                    style = MaterialTheme.typography.labelSmall,
                    color = MutedText,
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

            // ── 端口 ──
            SectionHeader("端口")
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = port,
                    onValueChange = { raw -> port = raw.filter { it.isDigit() }.take(5) },
                    label = { Text("${WebPrefs.MIN_PORT} - ${WebPrefs.MAX_PORT}") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    enabled = port.toIntOrNull()?.let { it in WebPrefs.MIN_PORT..WebPrefs.MAX_PORT } == true,
                    onClick = {
                        val value = port.toIntOrNull() ?: return@TextButton
                        WebPrefs.setPort(context, value)
                        restartServer()
                        hint = "端口已改成 $value"
                    },
                ) { Text("保存端口") }
            }
            Text(
                "端口被别的程序占用时会自动往后找（最多试 ${WebServer.PORT_ATTEMPTS} 个），" +
                    "所以实际地址以「接入地址」里显示的为准。",
                style = MaterialTheme.typography.labelSmall,
                color = MutedText,
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

            // ── 保活 ──
            SectionHeader("保活")
            KeyValueRow("电池优化", if (ignoring) "已关闭 · 能稳定后台运行" else "未关闭 · 可能被系统杀掉")
            if (!ignoring) {
                TextButton(onClick = { KeepAlive.requestIgnoreBatteryOptimizations(context) }) {
                    Text("去关闭电池优化", color = AccentGold)
                }
            }
            Text(
                "服务器跑在前台服务里，带常驻通知，熄屏和切到别的应用都不会断；" +
                    "另外持有 WiFi 锁和 CPU 唤醒锁，网络不会因为锁屏掉线。代价是稍微费电 —— " +
                    "写完了就点下面的按钮关掉。",
                style = MaterialTheme.typography.labelSmall,
                color = MutedText,
            )

            hint?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = AccentMint)
            }

            Text(
                "控制台开着的时候，手机这边不写书：同一本书两头同时改会打架，" +
                    "所以编辑权一次只交给一边。要自己在手机上写，就关掉 Web 协作。",
                style = MaterialTheme.typography.labelSmall,
                color = MutedText,
            )
        }

        // ── 底部：唯一的出口 ──
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 12.dp),
        ) {
            Button(
                onClick = { shutDown() },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
            ) {
                Text("关闭 Web 协作", fontWeight = FontWeight.Bold)
            }
            Text(
                "关闭后电脑立刻断开，手机恢复可编辑。",
                style = MaterialTheme.typography.labelSmall,
                color = MutedText,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

/** 一条动态：时间 + 动作 + （书名 · 来源电脑）。 */
@Composable
private fun LogRow(entry: WebLog.Entry) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                formatClock(entry.at),
                style = MaterialTheme.typography.labelSmall,
                color = MutedText,
            )
            Text(
                entry.action,
                style = MaterialTheme.typography.labelSmall,
                color = if (entry.ok) AccentMint else AccentGold,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        Text(
            listOf(entry.detail, entry.from).filter { it.isNotBlank() }.joinToString(" · "),
            style = MaterialTheme.typography.labelSmall,
            color = MutedText,
        )
    }
}

/** 访问码输入框里就先把非法字符挡住，省得用户敲完一半才发现被改了。 */
private fun sanitizeCodeInput(raw: String): String = raw.filter {
    (it in 'a'..'z') || (it in 'A'..'Z') || (it in '0'..'9') || it == '-' || it == '_' || it == '.'
}

private fun copyToClipboard(context: Context, text: String) {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    manager.setPrimaryClip(ClipData.newPlainText("浅笔记事 Web 协作", text))
}
