package com.qianbi.writer.web

import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** 静态资源（网页）的来源：正式运行时从 assets 读，测试时可以直接喂字节。 */
fun interface StaticSource {
    fun read(path: String): ByteArray?
}

/**
 * 局域网里的 HTTP 服务器。
 *
 * 只监听局域网地址、只服务一个浏览器页面，所以刻意做得简单：
 * 每请求一条短连接、`Content-Length` 定长、固定大小线程池。
 * 网页本身（`/` 和 `/web/` 下的静态文件）不校验访问码 —— 它们不含任何内容；
 * 所有 `/api/` 接口都要带访问码，数据全在那边。
 */
class WebServer(
    private val api: WebApi,
    private val statics: StaticSource,
    private val preferredPort: Int,
    private val accessCode: String,
) {

    companion object {
        /** 首选端口被占用时，往后连着试这么多个（界面提示也要用到）。 */
        const val PORT_ATTEMPTS = 10
        private const val WORKER_THREADS = 6

        private val MIME = mapOf(
            "html" to "text/html; charset=utf-8",
            "css" to "text/css; charset=utf-8",
            "js" to "application/javascript; charset=utf-8",
            "json" to "application/json; charset=utf-8",
            "png" to "image/png",
            "svg" to "image/svg+xml",
            "ico" to "image/x-icon",
        )
    }

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var acceptor: Thread? = null

    @Volatile
    private var pool: ThreadPoolExecutor? = null

    /** 实际绑上的端口（首选端口被占时会自动往后挪）。 */
    @Volatile
    var boundPort: Int = 0
        private set

    val isRunning: Boolean get() = serverSocket?.isClosed == false

    /** 可接入的完整 URL；网卡多（WiFi + 热点）时会给出多条。 */
    fun urls(): List<String> = if (isRunning) NetInfo.urls(boundPort, accessCode) else emptyList()

    @Synchronized
    fun start(): Boolean {
        if (isRunning) return true
        val socket = bind() ?: return false
        serverSocket = socket
        boundPort = socket.localPort
        pool = pool ?: newPool()
        acceptor = Thread({ acceptLoop(socket) }, "qianbi-web-accept").apply {
            isDaemon = true
            start()
        }
        return true
    }

    @Synchronized
    fun stop() {
        try {
            serverSocket?.close()
        } catch (e: IOException) {
            // 关不掉也无所谓，下面照样把状态清干净
        }
        serverSocket = null
        acceptor = null
        boundPort = 0
        // 一次会话结束就清掉：面板上显示的就是「这一次」的动静
        WebLog.clear()
        // 线程池留着复用；线程都是 daemon，空闲 30 秒后自己退掉
    }

    // ─────────────────────────── 内部 ───────────────────────────

    private fun bind(): ServerSocket? {
        for (offset in 0 until PORT_ATTEMPTS) {
            val port = preferredPort + offset
            if (port > 65535) break
            try {
                return ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(port))
                }
            } catch (e: IOException) {
                // 这个端口有人在用，试下一个
            }
        }
        return null
    }

    private fun newPool(): ThreadPoolExecutor = ThreadPoolExecutor(
        0,
        WORKER_THREADS,
        30L,
        TimeUnit.SECONDS,
        LinkedBlockingQueue(),
    ) { runnable ->
        Thread(runnable, "qianbi-web-worker").apply { isDaemon = true }
    }.apply { allowCoreThreadTimeOut(true) }

    private fun acceptLoop(socket: ServerSocket) {
        while (!socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (e: Exception) {
                break // socket 被 stop() 关掉了
            }
            try {
                pool?.execute { serve(client) } ?: client.close()
            } catch (e: RejectedExecutionException) {
                try {
                    client.close()
                } catch (ignored: IOException) {
                    // 已经断开
                }
            }
        }
    }

    private fun serve(client: Socket) {
        val remote = try {
            client.inetAddress?.hostAddress ?: ""
        } catch (e: Exception) {
            ""
        }
        try {
            client.use { socket ->
                socket.soTimeout = 20_000
                socket.tcpNoDelay = true
                val request = HttpCodec.readRequest(socket.getInputStream(), remote) ?: return
                val response = try {
                    dispatch(request)
                } catch (e: Exception) {
                    HttpResponse.error(500, e.message ?: "服务器内部错误")
                }
                HttpCodec.write(socket.getOutputStream(), response)
            }
        } catch (e: Exception) {
            // 浏览器打开页面时会掐掉一些探测连接，断开是常态，不必记
        }
    }

    private fun dispatch(request: HttpRequest): HttpResponse {
        val path = request.path

        if (path == "/" || path == "/index.html") {
            val page = statics.read("index.html") ?: return HttpResponse.notFound("缺少网页资源")
            WebLog.record(request.remote, "打开协作页面")
            return HttpResponse.bytes(200, page, MIME.getValue("html"))
        }
        if (path.startsWith("/web/")) {
            val name = path.removePrefix("/web/")
            if (name.isEmpty() || name.contains("..")) return HttpResponse.notFound()
            val data = statics.read(name) ?: return HttpResponse.notFound("没有这个资源")
            val ext = name.substringAfterLast('.', "").lowercase()
            return HttpResponse.bytes(200, data, MIME[ext] ?: "application/octet-stream")
        }
        if (path.startsWith("/api/")) {
            if (!authorized(request)) {
                WebLog.record(request.remote, "被挡在门外", "访问码不对", 401)
                return HttpResponse.error(401, "访问码不对", "auth")
            }
            // 接口内部的动静由 WebApi 记：它才知道是哪本书
            return api.handle(request)
        }
        WebLog.record(request.remote, "请求了未知地址", path, 404)
        return HttpResponse.notFound("没有这个地址")
    }

    private fun authorized(request: HttpRequest): Boolean {
        if (accessCode.isBlank()) return true
        if (request.param("t") == accessCode) return true
        return request.header("x-access-code") == accessCode
    }
}
