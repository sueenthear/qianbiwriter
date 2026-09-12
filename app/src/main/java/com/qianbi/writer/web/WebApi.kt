package com.qianbi.writer.web

import com.qianbi.writer.data.BookDoc
import com.qianbi.writer.data.BookRef
import com.qianbi.writer.data.BookStore
import com.qianbi.writer.data.NodeKind
import com.qianbi.writer.data.NodeInfo
import org.json.JSONArray
import org.json.JSONObject

/**
 * Web 协作的 REST 接口：手机端能做的事，这里都对应一个入口。
 *
 * 权限边界只有一条 —— **只有开了 `shareEnabled` 的书能被访问**。
 * 未共享的书和不存在的书返回同样的 403，这样谁也办法用「试 id」探出你有哪些书。
 *
 * 书在 URL / JSON 里都用 `meta.id`（32 位哈希）指代，不用文件路径：
 * 后者在 SAF 仓库下是 `content://` URI，又长又要转义。
 */
class WebApi(private val store: BookStore) {

    companion object {
        const val VERSION = 1
        const val APP_NAME = "浅笔记事"
    }

    fun handle(request: HttpRequest): HttpResponse {
        // 手机端随时可能在改同一本书，内存里的索引不能信，每次请求都让位给磁盘
        store.invalidateIndex()
        val response = route(request)
        // 记一条给人看的动态：哪台电脑、干了什么、成没成
        WebLog.record(
            from = request.remote,
            action = actionName(request.path),
            detail = actionDetail(request, response.status),
            status = response.status,
        )
        return response
    }

    /** 把接口路径翻译成人话，手机端面板直接显示这一句。 */
    private fun actionName(path: String): String = when (path) {
        "/api/ping" -> "电脑端连上了"
        "/api/books" -> "查看书架"
        "/api/book" -> "打开目录"
        "/api/node" -> "阅读一节"
        "/api/node/save" -> "保存一节"
        "/api/node/create" -> "新建一节"
        "/api/node/update" -> "改标题 / 简介"
        "/api/node/delete" -> "删除一节"
        "/api/node/move" -> "移动一节"
        else -> "未知请求"
    }

    /** 动态的补充说明：优先说清是哪本书、为什么没成。 */
    private fun actionDetail(request: HttpRequest, status: Int): String {
        when (status) {
            401 -> return "访问码不对"
            403 -> return "这本书没有开共享"
            404 -> return "内容不存在"
            409 -> return "版本冲突，等用户选"
        }
        if (status !in 200..299) return "失败（$status）"
        val id = request.param("id")
            ?: if (request.method == "POST") request.json()?.optString("id") else null
        if (id.isNullOrBlank()) return ""
        return store.findBookById(id)?.meta?.title?.let { "《$it》" } ?: ""
    }

    private fun route(request: HttpRequest): HttpResponse = when (request.path) {
        "/api/ping" -> HttpResponse.ok(
            JSONObject().apply {
                put("ok", true)
                put("app", APP_NAME)
                put("version", VERSION)
            },
        )
        "/api/books" -> books()
        "/api/book" -> book(request)
        "/api/node" -> node(request)
        "/api/node/save" -> save(request)
        "/api/node/create" -> create(request)
        "/api/node/update" -> update(request)
        "/api/node/delete" -> delete(request)
        "/api/node/move" -> move(request)
        else -> HttpResponse.notFound("没有这个接口：${request.path}")
    }

    // ─────────────────────────── 读 ───────────────────────────

    private fun books(): HttpResponse {
        val array = JSONArray()
        for (ref in sharedBooks()) array.put(bookJson(ref))
        return HttpResponse.ok(JSONObject().apply {
            put("ok", true)
            put("books", array)
        })
    }

    /** 目录树 + 书级信息。PC 端靠定时拉这个接口发现手机端的改动。 */
    private fun book(request: HttpRequest): HttpResponse {
        val ref = resolve(request.param("id")) ?: return notShared()
        val doc = store.loadDoc(ref)
        val parents = HashMap<String, String?>()
        indexParents(doc, doc.meta.order, null, parents)

        val tree = JSONArray()
        for (row in doc.flatten()) {
            val node = row.node
            tree.put(
                JSONObject().apply {
                    put("id", node.id)
                    put("title", node.title)
                    put("summary", node.summary)
                    put("kind", node.kind.wire)
                    put("writable", node.writable)
                    put("depth", row.depth)
                    put("parentId", parents[node.id] ?: JSONObject.NULL)
                    put("modifiedAt", node.modifiedAt)
                },
            )
        }
        return HttpResponse.ok(JSONObject().apply {
            put("ok", true)
            put("book", bookJson(ref))
            put("tree", tree)
        })
    }

    private fun node(request: HttpRequest): HttpResponse {
        val ref = resolve(request.param("id")) ?: return notShared()
        val nodeId = request.param("node") ?: return HttpResponse.error(400, "缺少 node 参数")
        val doc = store.loadDoc(ref)
        val info = doc.nodes[nodeId] ?: return HttpResponse.notFound("没有这个节点")
        val content = store.readContent(ref, nodeId)
        return HttpResponse.ok(JSONObject().apply {
            put("ok", true)
            put("node", nodePayload(info, content))
        })
    }

    // ─────────────────────────── 写 ───────────────────────────

    /**
     * 保存正文 / 简介 / 标题。
     *
     * [base] 是客户端读到的 `modifiedAt`；服务器上更新的时间戳比它新，说明别处已经改过，
     * 这里返回 409 并把服务器当前内容一起带回去，让用户自己选「覆盖」还是「丢弃」。
     */
    private fun save(request: HttpRequest): HttpResponse {
        val body = request.json() ?: return HttpResponse.error(400, "请求体不是 JSON")
        val ref = resolve(body.optString("id")) ?: return notShared()
        val nodeId = body.optString("node")
        val doc = store.loadDoc(ref)
        val before = doc.nodes[nodeId] ?: return HttpResponse.notFound("没有这个节点")

        if (isStale(body, before.modifiedAt)) return conflict(ref, nodeId)

        val title = body.optString("title", before.title)
        val summary = body.optString("summary", before.summary)
        if (title != before.title || summary != before.summary) {
            store.updateNode(ref, nodeId, title, summary)
        }

        if (body.has("content")) {
            val content = body.optString("content", "")
            if (!before.writable && content.isNotBlank()) {
                return HttpResponse.error(400, "「卷 / 目录」只能写简介，不能写正文", "not_writable")
            }
            if (before.writable) store.writeContent(ref, nodeId, content)
            // 卷的正文永远为空，上面已经挡掉了非空的情况
        }

        val fresh = store.loadDoc(ref).nodes[nodeId]
        return HttpResponse.ok(JSONObject().apply {
            put("ok", true)
            put("modifiedAt", fresh?.modifiedAt ?: 0L)
            put("chars", store.readContent(ref, nodeId).count { !it.isWhitespace() })
        })
    }

    private fun create(request: HttpRequest): HttpResponse {
        val body = request.json() ?: return HttpResponse.error(400, "请求体不是 JSON")
        val ref = resolve(body.optString("id")) ?: return notShared()
        val parentId = body.optString("parent").ifBlank { null }
        val kind = if (body.optString("kind") == NodeKind.Group.wire) NodeKind.Group else NodeKind.Chapter
        val title = body.optString("title").ifBlank { if (kind == NodeKind.Group) "新卷" else "新章" }
        return try {
            val info = store.addNode(ref, parentId, title, kind)
            HttpResponse.ok(JSONObject().apply {
                put("ok", true)
                put("node", info.id)
            })
        } catch (e: Exception) {
            HttpResponse.error(400, e.message ?: "新建失败")
        }
    }

    private fun update(request: HttpRequest): HttpResponse {
        val body = request.json() ?: return HttpResponse.error(400, "请求体不是 JSON")
        val ref = resolve(body.optString("id")) ?: return notShared()
        val nodeId = body.optString("node")
        val before = store.loadDoc(ref).nodes[nodeId] ?: return HttpResponse.notFound("没有这个节点")
        if (isStale(body, before.modifiedAt)) return conflict(ref, nodeId)
        store.updateNode(
            ref,
            nodeId,
            body.optString("title", before.title),
            body.optString("summary", before.summary),
        )
        // 把新时间戳带回去：客户端要拿它当新的 base，否则它下一次保存会被误判成冲突
        val fresh = store.loadDoc(ref).nodes[nodeId]
        return HttpResponse.ok(JSONObject().apply {
            put("ok", true)
            put("modifiedAt", fresh?.modifiedAt ?: 0L)
        })
    }

    private fun delete(request: HttpRequest): HttpResponse {
        val body = request.json() ?: return HttpResponse.error(400, "请求体不是 JSON")
        val ref = resolve(body.optString("id")) ?: return notShared()
        val nodeId = body.optString("node")
        if (store.loadDoc(ref).nodes[nodeId] == null) return HttpResponse.notFound("没有这个节点")
        store.deleteNode(ref, nodeId)
        return HttpResponse.ok(JSONObject().put("ok", true))
    }

    /** 同级上下移（`delta`）或换到另一个父级下（`parent`，追加到末尾）。 */
    private fun move(request: HttpRequest): HttpResponse {
        val body = request.json() ?: return HttpResponse.error(400, "请求体不是 JSON")
        val ref = resolve(body.optString("id")) ?: return notShared()
        val nodeId = body.optString("node")
        if (store.loadDoc(ref).nodes[nodeId] == null) return HttpResponse.notFound("没有这个节点")
        return if (body.has("parent")) {
            val target = body.optString("parent").ifBlank { null }
            if (store.reparentNode(ref, nodeId, target)) HttpResponse.ok(JSONObject().put("ok", true))
            else HttpResponse.error(400, "不能移动到那里（会剪断子树或位置没变）")
        } else {
            val delta = body.optInt("delta", 0)
            if (delta == 0) return HttpResponse.error(400, "缺少 delta 或 parent")
            store.moveNode(ref, nodeId, delta)
            HttpResponse.ok(JSONObject().put("ok", true))
        }
    }

    // ─────────────────────────── 内部 ───────────────────────────

    private fun sharedBooks(): List<BookRef> = store.listBooks().filter { it.meta.shareEnabled }

    /** 定位请求里的书，并且确认它确实开了共享。 */
    private fun resolve(id: String?): BookRef? {
        if (id.isNullOrBlank()) return null
        val ref = store.findBookById(id) ?: return null
        return if (ref.meta.shareEnabled) ref else null
    }

    private fun notShared() = HttpResponse.error(
        403,
        "这本书没有开启 Web 共享，或书 id 不对",
        "not_shared",
    )

    private fun isStale(body: JSONObject, serverModifiedAt: Long): Boolean {
        val base = body.optLong("base", 0L)
        return base > 0L && serverModifiedAt > base
    }

    /** 别处已经改过 → 把服务器版本原样带回去，客户端的「覆盖 / 载入最新」都靠它。 */
    private fun conflict(ref: BookRef, nodeId: String): HttpResponse {
        val info = store.loadDoc(ref).nodes[nodeId]
        val content = store.readContent(ref, nodeId)
        return HttpResponse.json(
            409,
            JSONObject().apply {
                put("ok", false)
                put("error", "这一节在别处被改过了")
                put("code", "conflict")
                put(
                    "node",
                    if (info != null) nodePayload(info, content)
                    else JSONObject().put("id", nodeId),
                )
            },
        )
    }

    private fun bookJson(ref: BookRef): JSONObject = JSONObject().apply {
        put("id", ref.meta.id)
        put("title", ref.meta.title)
        put("author", ref.meta.author)
        put("summary", ref.meta.summary)
        put("tags", JSONArray(ref.meta.tags))
        put("chars", store.countChars(ref))
        put("modifiedAt", ref.meta.modifiedAt)
    }

    /** 节点信息 + 正文的对外形状。 */
    private fun nodePayload(info: NodeInfo, content: String): JSONObject = info.wireJson().apply {
        put("content", content)
        put("chars", content.count { !it.isWhitespace() })
    }

    private fun NodeInfo.wireJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("title", title)
        put("summary", summary)
        put("kind", kind.wire)
        put("writable", writable)
        put("modifiedAt", modifiedAt)
    }

    private fun indexParents(
        doc: BookDoc,
        ids: List<String>,
        parentId: String?,
        out: MutableMap<String, String?>,
    ) {
        for (id in ids) {
            out[id] = parentId
            val node = doc.nodes[id] ?: continue
            indexParents(doc, node.children, id, out)
        }
    }
}
