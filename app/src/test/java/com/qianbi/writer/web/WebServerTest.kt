package com.qianbi.writer.web

import com.qianbi.writer.data.BookRef
import com.qianbi.writer.data.BookStore
import com.qianbi.writer.data.FileFsEntry
import com.qianbi.writer.data.NodeInfo
import com.qianbi.writer.data.NodeKind
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.net.Socket

/**
 * Web 服务器的端到端测试：真起一个服务器，再用真套接字打 HTTP 请求过去。
 *
 * 特意不走任何 Android API —— [WebServer] / [WebApi] 都只依赖 [BookStore] 和 `java.net`，
 * 所以能在普通 JVM 单测里跑，不必等真机。
 */
class WebServerTest {

    private lateinit var root: File
    private lateinit var store: BookStore
    private lateinit var server: WebServer
    private lateinit var bookId: String
    private lateinit var privateId: String
    private var port = 0

    private val code = "testcode"

    @Before
    fun setUp() {
        root = File(System.getProperty("java.io.tmpdir"), "qianbi-web-${System.nanoTime()}")
        root.mkdirs()
        store = BookStore(FileFsEntry(root))

        val open = store.createBook("共享的书", author = "作者甲", summary = "一本测试书")
        store.setShare(open, true)
        bookId = open.meta.id
        privateId = store.createBook("私密的书").meta.id

        val volume = store.addNode(open, null, "第一卷", NodeKind.Group)
        val chapter = store.addNode(open, volume.id, "第一章", NodeKind.Chapter)
        store.writeContent(open, chapter.id, "原本的正文")

        WebLog.clear() // 全局单例，别让上一个用例的动态串进来
        server = newServer(0)
        assertTrue("服务器应该在随机端口上起来", server.start())
        port = server.boundPort
    }

    @After
    fun tearDown() {
        server.stop()
        root.deleteRecursively()
    }

    // ─────────────────────────── 权限 ───────────────────────────

    @Test
    fun `未共享的书不出现在列表里也访问不到`() {
        val list = get("/api/books?t=$code").json()
        val titles = (0 until list.getJSONArray("books").length())
            .map { list.getJSONArray("books").getJSONObject(it).getString("title") }

        assertEquals(listOf("共享的书"), titles)
        assertEquals(403, get("/api/book?id=$privateId&t=$code").status)
        assertEquals(403, get("/api/book?id=不存在的id&t=$code").status)
    }

    @Test
    fun `访问码不对一律 401`() {
        assertEquals(401, get("/api/books?t=wrong").status)
        assertEquals(401, get("/api/books").status)
        assertEquals(401, get("/api/book?id=$bookId&t=wrong").status)
    }

    @Test
    fun `网页本身不需要访问码`() {
        val home = get("/")
        assertEquals(200, home.status)
        assertEquals("<html>stub</html>", home.body)

        assertEquals(200, get("/web/style.css").status)
        assertEquals(404, get("/web/../secret").status)
    }

    // ─────────────────────────── 读 ───────────────────────────

    @Test
    fun `目录树带着层级和类型`() {
        val json = get("/api/book?id=$bookId&t=$code").json()
        val tree = json.getJSONArray("tree")
        assertEquals(2, tree.length())

        val volume = tree.getJSONObject(0)
        assertEquals("第一卷", volume.getString("title"))
        assertEquals("group", volume.getString("kind"))
        assertEquals(0, volume.getInt("depth"))
        assertFalse(volume.getBoolean("writable"))

        val chapter = tree.getJSONObject(1)
        assertEquals("第一章", chapter.getString("title"))
        assertEquals("chapter", chapter.getString("kind"))
        assertEquals(1, chapter.getInt("depth"))
        assertEquals(volume.getString("id"), chapter.getString("parentId"))
    }

    @Test
    fun `读单节带上正文和字数`() {
        val node = get("/api/node?id=$bookId&node=${nodeByTitle("第一章").id}&t=$code")
            .json().getJSONObject("node")
        assertEquals("原本的正文", node.getString("content"))
        assertEquals(5, node.getInt("chars"))
        assertTrue(node.getBoolean("writable"))
    }

    /** 手机端和服务器是两个 [BookStore] 实例，缓存不能把对方写的东西挡掉。 */
    @Test
    fun `别的实例改了书，这边立刻读得到`() {
        val phone = BookStore(FileFsEntry(root))
        val phoneRef = phone.findBookById(bookId)!!
        phone.writeContent(phoneRef, nodeByTitle("第一章").id, "手机改的正文")

        val node = get("/api/node?id=$bookId&node=${nodeByTitle("第一章").id}&t=$code")
            .json().getJSONObject("node")
        assertEquals("手机改的正文", node.getString("content"))
    }

    // ─────────────────────────── 写 ───────────────────────────

    @Test
    fun `保存正文和简介`() {
        val chapter = nodeByTitle("第一章")
        val saved = post(
            "/api/node/save?t=$code",
            JSONObject()
                .put("id", bookId)
                .put("node", chapter.id)
                .put("content", "换了一版正文")
                .put("summary", "这一章的简介")
                .put("base", chapter.modifiedAt),
        ).json()

        assertTrue(saved.getBoolean("ok"))
        val ref = sharedRef()
        assertEquals("换了一版正文", store.readContent(ref, chapter.id))
        assertEquals("这一章的简介", store.loadDoc(ref).nodes.getValue(chapter.id).summary)
    }

    @Test
    fun `拿旧时间戳保存会撞冲突，并把服务器内容带回来`() {
        val chapter = nodeByTitle("第一章")
        val stale = chapter.modifiedAt

        // 先让别人改一次
        post(
            "/api/node/save?t=$code",
            JSONObject().put("id", bookId).put("node", chapter.id)
                .put("content", "别人写的").put("base", stale),
        )

        val conflict = post(
            "/api/node/save?t=$code",
            JSONObject().put("id", bookId).put("node", chapter.id)
                .put("content", "我写的").put("base", stale),
        )
        assertEquals(409, conflict.status)
        val json = conflict.json()
        assertEquals("conflict", json.getString("code"))
        assertEquals("别人写的", json.getJSONObject("node").getString("content"))

        // base 归零就是「强制覆盖」，服务器不再拦
        val forced = post(
            "/api/node/save?t=$code",
            JSONObject().put("id", bookId).put("node", chapter.id)
                .put("content", "我写的").put("base", 0),
        )
        assertEquals(200, forced.status)
        assertEquals("我写的", store.readContent(sharedRef(), chapter.id))
    }

    @Test
    fun `卷不能写正文`() {
        val volume = nodeByTitle("第一卷")
        val res = post(
            "/api/node/save?t=$code",
            JSONObject().put("id", bookId).put("node", volume.id)
                .put("content", "不该写进去").put("base", 0),
        )
        assertEquals(400, res.status)
        assertEquals("not_writable", res.json().getString("code"))
    }

    @Test
    fun `新建、移动、删除`() {
        val volume = nodeByTitle("第一卷")
        val created = post(
            "/api/node/create?t=$code",
            JSONObject().put("id", bookId).put("parent", volume.id)
                .put("kind", "chapter").put("title", "第二章"),
        ).json()
        val newId = created.getString("node")
        assertEquals("第二章", store.loadDoc(sharedRef()).nodes.getValue(newId).title)

        assertEquals(
            200,
            post("/api/node/move?t=$code", JSONObject().put("id", bookId).put("node", newId).put("delta", -1)).status,
        )
        assertEquals(
            listOf("第二章", "第一章"),
            store.loadDoc(sharedRef()).nodes.getValue(volume.id).children.map {
                store.loadDoc(sharedRef()).nodes.getValue(it).title
            },
        )

        // 挪到最外层：不再挂在卷下面
        assertEquals(
            200,
            post("/api/node/move?t=$code", JSONObject().put("id", bookId).put("node", newId).put("parent", "")).status,
        )
        val moved = store.loadDoc(sharedRef())
        assertFalse(moved.nodes.getValue(volume.id).children.contains(newId))
        assertTrue(moved.meta.order.contains(newId))

        assertEquals(
            200,
            post("/api/node/delete?t=$code", JSONObject().put("id", bookId).put("node", newId)).status,
        )
        assertFalse(store.loadDoc(sharedRef()).nodes.containsKey(newId))
    }

    @Test
    fun `改标题`() {
        val chapter = nodeByTitle("第一章")
        val res = post(
            "/api/node/update?t=$code",
            JSONObject().put("id", bookId).put("node", chapter.id).put("title", "改名了"),
        )
        assertEquals(200, res.status)
        assertEquals("改名了", store.loadDoc(sharedRef()).nodes.getValue(chapter.id).title)
        // 改名的同时会推进节点时间戳，必须把新值回给客户端，否则它下一次保存会误判成冲突
        assertTrue(res.json().getLong("modifiedAt") >= chapter.modifiedAt)
    }

    @Test
    fun `找不到的路径给 404`() {
        assertEquals(404, get("/api/nope?t=$code").status)
        assertEquals(404, get("/nothing").status)
    }

    // ─────────────────────────── 手机端动态 ───────────────────────────

    @Test
    fun `电脑端的每次操作都会记进动态里`() {
        get("/api/books?t=$code")
        get("/api/book?id=$bookId&t=$code")
        post(
            "/api/node/save?t=$code",
            JSONObject().put("id", bookId).put("node", nodeByTitle("第一章").id)
                .put("content", "改一下").put("base", 0),
        )
        get("/api/books?t=wrong")
        get("/api/book?id=$privateId&t=$code")
        get("/")

        val log = WebLog.snapshot()
        // 最新的排在最前面
        assertEquals("打开协作页面", log.first().action)
        assertTrue(log.any { it.action == "查看书架" && it.status == 200 })
        assertTrue("打开目录那条要带上书名", log.any { it.action == "打开目录" && it.detail == "《共享的书》" })
        assertTrue(log.any { it.action == "保存一节" && it.status == 200 })
        assertTrue(log.any { it.status == 401 && it.detail == "访问码不对" })
        assertTrue(log.any { it.status == 403 && it.detail == "这本书没有开共享" })
        assertTrue("要能看出是哪台电脑", log.all { it.from == "127.0.0.1" })
    }

    @Test
    fun `动态只留内存里的最近若干条`() {
        WebLog.clear()
        repeat(200) { WebLog.record("127.0.0.1", "第 $it 条") }
        val log = WebLog.snapshot()
        assertEquals(150, log.size)
        assertEquals("第 199 条", log.first().action) // 最新的在最前
        assertEquals("第 50 条", log.last().action)  // 更早的已经被挤掉
        WebLog.clear()
        assertTrue(WebLog.snapshot().isEmpty())
    }

    // ─────────────────────────── 端口 ───────────────────────────

    /**
     * 端口被占用时换一个继续服务 —— 这在 Linux（也就是 Android）上一定会挪位：
     * SO_REUSEADDR 不会去抢别人正在监听的端口。Windows 的 SO_REUSEADDR 语义不同，
     * 第二个 socket 会直接抢到同一个端口，所以这里只断言「照样能起来」，
     * 不用端口号变化去卡平台差异。
     */
    @Test
    fun `首选端口被占也不会起不来`() {
        // 先占住一个空闲端口（注意不能占 setUp 里那台服务器自己的端口）
        val blocker = ServerSocket(0)
        val taken = blocker.localPort
        try {
            val second = newServer(taken)
            assertTrue(second.start())
            second.stop()
        } finally {
            blocker.close()
        }
    }

    // ─────────────────────────── 脚手架 ───────────────────────────

    private fun newServer(preferred: Int) = WebServer(
        api = WebApi(store),
        statics = { path ->
            when (path) {
                "index.html" -> "<html>stub</html>".toByteArray(Charsets.UTF_8)
                "style.css" -> "body{}".toByteArray(Charsets.UTF_8)
                else -> null
            }
        },
        preferredPort = preferred,
        accessCode = code,
    )

    private fun sharedRef(): BookRef = store.findBookById(bookId)!!

    private fun nodeByTitle(title: String): NodeInfo =
        store.loadDoc(sharedRef()).nodes.values.first { it.title == title }

    private class Reply(val status: Int, val body: String) {
        fun json(): JSONObject = JSONObject(body)
    }

    private fun get(path: String): Reply = call("GET", path, null)

    private fun post(path: String, body: JSONObject): Reply = call("POST", path, body.toString())

    private fun call(method: String, path: String, body: String?): Reply {
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 8000
            val payload = body?.toByteArray(Charsets.UTF_8)
            val head = buildString {
                append("$method $path HTTP/1.1\r\n")
                append("Host: 127.0.0.1\r\n")
                if (payload != null) {
                    append("Content-Type: application/json\r\n")
                    append("Content-Length: ${payload.size}\r\n")
                }
                append("Connection: close\r\n\r\n")
            }
            socket.getOutputStream().apply {
                write(head.toByteArray(Charsets.UTF_8))
                if (payload != null) write(payload)
                flush()
            }
            val raw = socket.getInputStream().readBytes().toString(Charsets.UTF_8)
            val split = raw.indexOf("\r\n\r\n")
            val status = raw.substringBefore("\r\n").split(' ')[1].toInt()
            return Reply(status, if (split >= 0) raw.substring(split + 4) else "")
        }
    }
}
