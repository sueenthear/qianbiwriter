package com.qianbi.writer.data

import org.json.JSONArray
import org.json.JSONObject

// ───────────────────────────── 文件名约定 ─────────────────────────────
// 一本小说 = 一个文件夹：
//   <书名slug>_<8位哈希>/
//     ├─ book.info         书级元数据（书名/作者/简介/标签/创建/修改/封面/正文顺序）
//     ├─ cover.jpg         封面图（可选）
//     ├─ glossary.json     快捷输入词表（可选）
//     └─ book/             正文部分
//         ├─ <32位哈希>/   —— 目录节点（章节可重名，故用哈希做文件夹名）
//         │   ├─ this.info  本级题目/简介/创建/修改/子节点顺序
//         │   ├─ content.txt 本级正文（非叶子级同样可以放文本）
//         │   └─ <32位哈希>/   —— 任意深度嵌套（书-卷-章-总卷子章-…）
//         └─ ...
const val FILE_BOOK_INFO = "book.info"
const val FILE_NODE_INFO = "this.info"
const val FILE_CONTENT = "content.txt"
const val FILE_GLOSSARY = "glossary.json"
const val DIR_BODY = "book"
const val DIR_BOOKS = "books"

const val FORMAT_VERSION = 1

// ───────────────────────────── 数据模型 ─────────────────────────────

/** 书级元数据，序列化为书本文件夹根部的 [FILE_BOOK_INFO]。 */
data class BookMeta(
    val id: String,
    val title: String,
    val author: String = "",
    val summary: String = "",
    val tags: List<String> = emptyList(),
    val createdAt: Long = 0L,
    val modifiedAt: Long = 0L,
    /** 封面文件名（相对书本文件夹），无封面为 null。 */
    val cover: String? = null,
    /** 正文根（book/）下直接子节点的顺序。 */
    val order: List<String> = emptyList(),
    /**
     * 是否允许通过局域网 Web 服务器在电脑上编辑这本书。
     * 这是被人看到隐私的开关，默认关闭；旧数据没有这个字段时也按关闭处理。
     */
    val shareEnabled: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("format", FORMAT_VERSION)
        put("id", id)
        put("title", title)
        put("author", author)
        put("summary", summary)
        put("tags", JSONArray(tags))
        put("createdAt", createdAt)
        put("modifiedAt", modifiedAt)
        put("cover", cover ?: JSONObject.NULL)
        put("order", JSONArray(order))
        put("share", shareEnabled)
    }

    companion object {
        fun fromJson(o: JSONObject, fallbackId: String): BookMeta = BookMeta(
            id = o.optString("id", fallbackId).ifBlank { fallbackId },
            title = o.optString("title", "").ifBlank { "未命名" },
            author = o.optString("author", ""),
            summary = o.optString("summary", ""),
            tags = o.optJSONArray("tags").toStringList(),
            createdAt = o.optLong("createdAt", 0L),
            modifiedAt = o.optLong("modifiedAt", 0L),
            cover = if (o.isNull("cover")) null else o.optString("cover", "").ifBlank { null },
            order = o.optJSONArray("order").toStringList(),
            shareEnabled = o.optBoolean("share", false),
        )
    }
}

/** 节点类型：目录（卷）只能写简介；正文（章）才能写正文。 */
enum class NodeKind(val wire: String) {
    Group("group"),
    Chapter("chapter");

    companion object {
        /** 旧数据没有 kind 字段：有子级的当目录，没有子级的当正文。 */
        fun fromWire(value: String, hasChildren: Boolean): NodeKind = when (value) {
            Group.wire -> Group
            Chapter.wire -> Chapter
            else -> if (hasChildren) Group else Chapter
        }
    }
}

/** 目录树中的一个节点，序列化为该节点文件夹内的 [FILE_NODE_INFO]。 */
data class NodeInfo(
    val id: String,
    val title: String,
    val summary: String = "",
    val createdAt: Long = 0L,
    val modifiedAt: Long = 0L,
    /** 本级直接子节点顺序。 */
    val children: List<String> = emptyList(),
    /** 这一级是目录（卷）还是正文（章）。 */
    val kind: NodeKind = NodeKind.Chapter,
) {
    /** 能不能在这一级写正文（只有「章」可以）。 */
    val writable: Boolean get() = kind == NodeKind.Chapter

    fun toJson(): JSONObject = JSONObject().apply {
        put("format", FORMAT_VERSION)
        put("id", id)
        put("title", title)
        put("summary", summary)
        put("createdAt", createdAt)
        put("modifiedAt", modifiedAt)
        put("children", JSONArray(children))
        put("kind", kind.wire)
    }

    companion object {
        fun fromJson(o: JSONObject?, fallbackId: String, fallbackTitle: String): NodeInfo {
            if (o == null) return NodeInfo(id = fallbackId, title = fallbackTitle, kind = NodeKind.Chapter)
            val children = o.optJSONArray("children").toStringList()
            return NodeInfo(
                id = o.optString("id", fallbackId).ifBlank { fallbackId },
                title = o.optString("title", "").ifBlank { fallbackTitle },
                summary = o.optString("summary", ""),
                createdAt = o.optLong("createdAt", 0L),
                modifiedAt = o.optLong("modifiedAt", 0L),
                children = children,
                kind = NodeKind.fromWire(o.optString("kind", ""), children.isNotEmpty()),
            )
        }
    }
}

/** 主界面网格里的一本书：目录句柄 + 元数据。 */
data class BookRef(val dir: FsEntry, val meta: BookMeta) {
    /** 稳定标识：File 后端是绝对路径，SAF 后端是 document uri。 */
    val key: String get() = dir.path
    val id: String get() = meta.id
}

/** 目录树渲染用的一行（节点 + 缩进深度）。 */
data class TreeRow(val node: NodeInfo, val depth: Int)

/** 整本书在内存中的快照（不含正文文本，文本按需从磁盘读取）。 */
data class BookDoc(
    val ref: BookRef,
    val meta: BookMeta,
    val nodes: Map<String, NodeInfo>,
    val glossary: Glossary,
) {
    /** 某个父级（null = 正文根）下的子节点顺序。 */
    fun orderOf(parentId: String?): List<String> =
        if (parentId == null) meta.order else nodes[parentId]?.children ?: emptyList()

    val rowCount: Int get() = nodes.size

    /** 深度优先展开成可渲染的行列表。 */
    fun flatten(): List<TreeRow> {
        val rows = ArrayList<TreeRow>(nodes.size)
        val seen = HashSet<String>()
        fun walk(ids: List<String>, depth: Int) {
            for (id in ids) {
                if (!seen.add(id)) continue
                val node = nodes[id] ?: continue
                rows.add(TreeRow(node, depth))
                walk(node.children, depth + 1)
            }
        }
        walk(meta.order, 0)
        // 容错：文件夹存在但没被任何顺序列表引用时，补在末尾
        for ((id, node) in nodes) if (id !in seen) rows.add(TreeRow(node, 0))
        return rows
    }

    /** 从根到该节点的祖先链（不含自身）。 */
    fun ancestorsOf(nodeId: String): List<NodeInfo> {
        val path = ArrayList<NodeInfo>()
        fun walk(ids: List<String>, trail: List<NodeInfo>): Boolean {
            for (id in ids) {
                val node = nodes[id] ?: continue
                if (id == nodeId) {
                    path.addAll(trail)
                    return true
                }
                if (walk(node.children, trail + node)) return true
            }
            return false
        }
        walk(meta.order, emptyList())
        return path
    }

    /** 该节点所在层级（null = 正文根）的父节点 id。 */
    fun parentOf(nodeId: String): String? = ancestorsOf(nodeId).lastOrNull()?.id

    fun breadcrumb(nodeId: String?): List<String> {
        if (nodeId == null) return listOf(meta.title)
        val chain = ancestorsOf(nodeId).map { it.title } + listOfNotNull(nodes[nodeId]?.title)
        return listOf(meta.title) + chain
    }

    companion object {
        fun empty(ref: BookRef): BookDoc =
            BookDoc(ref, ref.meta, emptyMap(), Glossary())
    }
}

// ───────────────────────────── 快捷输入词表 ─────────────────────────────

/** 快捷输入词条：点一下就把 [text] 插入到光标处。 */
data class GlossaryItem(
    val name: String,
    val text: String = name,
    val group: String = "默认",
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name)
        put("text", text)
        put("group", group)
    }

    companion object {
        fun fromJson(o: JSONObject): GlossaryItem = GlossaryItem(
            name = o.optString("name", ""),
            text = o.optString("text", ""),
            group = o.optString("group", "默认").ifBlank { "默认" },
        )
    }
}

/** 书级快捷输入词表，序列化为 [FILE_GLOSSARY]。 */
data class Glossary(val items: List<GlossaryItem> = emptyList()) {
    fun groups(): List<Pair<String, List<GlossaryItem>>> =
        items.groupBy { it.group }.map { (k, v) -> k to v }

    fun withAdded(item: GlossaryItem): Glossary = copy(items = items + item)

    fun withRemoved(index: Int): Glossary =
        if (index in items.indices) copy(items = items.filterIndexed { i, _ -> i != index }) else this

    fun toJson(): JSONObject = JSONObject().apply {
        put("format", FORMAT_VERSION)
        put("items", JSONArray().also { arr -> items.forEach { arr.put(it.toJson()) } })
    }

    companion object {
        fun fromJson(o: JSONObject?): Glossary {
            val arr = o?.optJSONArray("items") ?: return Glossary()
            val out = ArrayList<GlossaryItem>(arr.length())
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val parsed = GlossaryItem.fromJson(item)
                if (parsed.name.isNotBlank()) out.add(parsed)
            }
            return Glossary(out)
        }
    }
}

// ───────────────────────────── 小工具 ─────────────────────────────

internal fun JSONArray?.toStringList(): List<String> {
    if (this == null) return emptyList()
    val out = ArrayList<String>(length())
    for (i in 0 until length()) {
        val s = optString(i, "")
        if (s.isNotBlank()) out.add(s)
    }
    return out
}
