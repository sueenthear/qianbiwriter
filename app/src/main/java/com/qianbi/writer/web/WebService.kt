package com.qianbi.writer.web

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.qianbi.writer.MainActivity
import com.qianbi.writer.data.StoragePrefs
import com.qianbi.writer.data.WebPrefs

/**
 * 把 Web 服务器挂成前台服务：熄屏、切到别的应用之后，电脑那头的网页依然连得上。
 *
 * 三层保活，逐层兜底：
 * 1. **前台服务 + 常驻通知** —— 系统不会随手回收，也让你随时看得见它开着；
 * 2. **WifiLock** —— 阻止熄屏后 WiFi 进省电模式把连接掐掉；
 * 3. **PARTIAL_WAKE_LOCK** —— 屏幕关掉后 CPU 不睡，TCP 连接不会半死不活。
 *
 * 第 3 条是实打实的耗电项，所以只在用户主动开启服务器期间持有，界面里也会写明。
 */
class WebService : Service() {

    companion object {
        const val CHANNEL_ID = "qianbi-web"
        const val NOTIFICATION_ID = 4201

        private const val ACTION_STOP = "com.qianbi.writer.action.WEB_STOP"
        private const val WIFI_LOCK_TAG = "qianbi:web-wifi"
        private const val CPU_LOCK_TAG = "qianbi:web-cpu"

        @Volatile
        private var instance: WebService? = null

        /** 服务器是否真的在监听 —— 注意这不是「开关打开」的意思。 */
        fun isRunning(): Boolean = instance?.server?.isRunning == true

        /** 当前的接入地址；没跑起来时是空列表。 */
        fun urls(): List<String> = instance?.server?.urls().orEmpty()

        fun boundPort(): Int = instance?.server?.boundPort ?: 0

        /** 启动失败的原因（端口占满之类），给界面提示用。 */
        @Volatile
        var lastError: String? = null

        fun start(context: Context) {
            lastError = null
            context.startForegroundService(Intent(context, WebService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WebService::class.java))
        }
    }

    @Volatile
    private var server: WebServer? = null

    private var wifiLock: WifiManager.WifiLock? = null
    private var cpuLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        // 必须在 onStartCommand 里尽快转前台，否则系统会直接抛异常把服务掐掉
        startForeground(NOTIFICATION_ID, buildNotification())
        acquireLocks()
        if (server?.isRunning != true) launch()
        return START_STICKY
    }

    override fun onDestroy() {
        server?.stop()
        server = null
        releaseLocks()
        if (instance === this) instance = null
        super.onDestroy()
    }

    // ─────────────────────────── 服务器 ───────────────────────────

    private fun launch() {
        val port = WebPrefs.port(this)
        val created = WebServer(
            api = WebApi(StoragePrefs.openStore(this)),
            statics = AssetSource(this),
            preferredPort = port,
            accessCode = WebPrefs.accessCode(this),
        )
        if (!created.start()) {
            val last = port + WebServer.PORT_ATTEMPTS - 1
            lastError = "端口 $port ~ $last 都被占用了，换一个端口再试"
            stopSelf()
            return
        }
        server = created
        lastError = null
        // 端口可能不是首选的那个（自动往后挪过），通知里要显示真实地址
        (getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)
            ?.notify(NOTIFICATION_ID, buildNotification())
    }

    /** 网页资源从 assets/web/ 里取。 */
    private class AssetSource(private val context: Context) : StaticSource {
        override fun read(path: String): ByteArray? = try {
            context.assets.open("web/$path").use { it.readBytes() }
        } catch (e: Exception) {
            null
        }
    }

    // ─────────────────────────── 通知 ───────────────────────────

    private fun createChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Web 协作", NotificationManager.IMPORTANCE_LOW).apply {
                description = "在电脑上编辑手机里的书时，保持服务器运行"
                setShowBadge(false)
            },
        )
    }

    private fun buildNotification(): Notification {
        val urls = server?.urls().orEmpty()
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, WebService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val detail = buildString {
            if (urls.isEmpty()) {
                append("正在启动…")
            } else {
                append("电脑浏览器打开：\n")
                urls.forEach { append(it).append('\n') }
                append("\n只有开了「Web 共享」的书能被编辑。")
            }
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Web 协作已开启")
            .setContentText(urls.firstOrNull() ?: "正在启动…")
            .setStyle(Notification.BigTextStyle().bigText(detail))
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .setShowWhen(false)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "停止共享", stop).build())
            .build()
    }

    // ─────────────────────────── 保活 ───────────────────────────

    @SuppressLint("WakelockTimeout")
    private fun acquireLocks() {
        if (wifiLock == null) {
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                @Suppress("DEPRECATION")
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            wifiLock = try {
                wifi?.createWifiLock(mode, WIFI_LOCK_TAG)?.apply {
                    setReferenceCounted(false)
                    acquire()
                }
            } catch (e: Exception) {
                null
            }
        }
        if (cpuLock == null) {
            val power = applicationContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
            cpuLock = try {
                power?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, CPU_LOCK_TAG)?.apply {
                    setReferenceCounted(false)
                    acquire()
                }
            } catch (e: Exception) {
                null
            }
        }
    }

    private fun releaseLocks() {
        try {
            wifiLock?.takeIf { it.isHeld }?.release()
        } catch (e: Exception) {
            // 已经释放
        }
        try {
            cpuLock?.takeIf { it.isHeld }?.release()
        } catch (e: Exception) {
            // 已经释放
        }
        wifiLock = null
        cpuLock = null
    }
}
