package com.qianbi.writer.data

import org.json.JSONObject

/**
 * 书本仓库：**文件系统即真相**。
 *
 * 磁盘结构（[booksDir] 下，一本书一个文件夹）：
 * ```
 *   <书名slug>_<书id前8位>/
 *     book.info          书级元数据
 *     cover.jpg          封面（可选）
 *     glossary.json      快捷输入词表（可选）
 *     book/              正文
 *       <32位哈希>/      —— 任意层级的节点（章节可重名 → 用哈希做文件夹名）
 *         this.info       本级题目/简介/类型/创建/修改/子节点顺序
 *         content.txt     本级正文（**只有「章」类型才写这个文件**）
 *         <32位哈希>/…    继续嵌套
 * ```
 * [booksDir] 是一个 [FsEntry]，所以书架既能在应用私有目录，也能在用户自选的外部文件夹里，
 * 两者的读写逻辑完全一样。
 *
 * **节点类型**（[NodeKind]）：目录（卷）用来分组、只能写简介；正文（章）才能写正文。
 * 「章」可以挂到任何一级下面。
 *
 * **性能**：每次 [loadDoc] 会顺手建立一张「路径表」（nodeId → 目录句柄 + 父级 + 信息），
 * 之后 [findNode] / [readContent] / [writeContent] 直接命中缓存，不必再从根递归扫盘；
 * 全书字数也带缓存，写正文时按增量维护。结构变化（增删移）后缓存自动失效重建。
 *
 * 本类可从任意线程调用（IO 请放 Dispatchers.IO）。
 */
class BookStore(val booksDir: FsEntry) {

    init {
        booksDir.mkdirs()
    }

    /** 一句话的路径表缓存：定位节点用，避免每次都从根扫盘。 */
    private class Index(
        val bookPath: String,
        val nodes: Map<String, NodeInfo>,
        val dirs: Map<String, FsEntry>,
        val parents: Map<String, String?>,
    )

    @Volatile
    private var index: Index? = null

    /** 全书字数缓存：bookPath → 字数。 */
    @Volatile
    private var charsCache: Pair<String, Int>? = null

    /** 让内存缓存失效（导入/外部改动后调用，下次访问会重新扫盘）。 */
    fun invalidate() {
        index = null
        charsCache = null
    }

    /**
     * 只丢掉路径表缓存，保留字数缓存。
     * Web 服务器每处理一个请求都会调它：手机端随时可能改书，内存里的索引必须让位给磁盘；
     * 但整本字数重扫太贵，所以单独留一个轻量入口。
     */
    fun invalidateIndex() {
        index = null
    }

    // ─────────────────────────── 书级操作 ───────────────────────────

    fun listBooks(): List<BookRef> =
        booksDir.list()
            .filter { it.isDirectory && !it.name.startsWith(".") }
            .mapNotNull { readBookRef(it) }
            .sortedByDescending { it.meta.modifiedAt }

    /** 按书 id（32 位哈希）定位一本书：Web 端在 URL 和 JSON 里都用它指代书。 */
    fun findBookById(id: String): BookRef? =
        listBooks().firstOrNull { it.meta.id == id }

    fun createBook(
        title: String,
        author: String = "",
        summary: String = "",
        tags: List<String> = emptyList(),
    ): BookRef {
        val now = System.currentTimeMillis()
        val id = newHashId(title)
        val dir = createBookDir(title, id)
        dir.child(DIR_BODY).mkdirs()
        val meta = BookMeta(
            id = id,
            title = title.ifBlank { "未命名" },
            author = author,
            summary = summary,
            tags = tags,
            createdAt = now,
            modifiedAt = now,
        )
        writeMeta(dir, meta)
        return BookRef(dir, meta)
    }

    /** 在书架下开一个新书目录（同名的自动加序号）。导入时也用它。 */
    fun createBookDir(title: String, id: String): FsEntry {
        booksDir.mkdirs()
        val base = "${slugify(title)}_${id.take(8)}"
        var candidate = booksDir.child(base)
        var n = 2
        while (candidate.exists) {
            candidate = booksDir.child("${base}_$n")
            n++
        }
        candidate.mkdirs()
        return candidate
    }

    fun readBook(dir: FsEntry): BookRef? = readBookRef(dir)

    /** 更新书级元数据（书名/作者/简介/标签）。 */
    fun updateBook(ref: BookRef, meta: BookMeta): BookRef {
        val stamped = meta.copy(modifiedAt = System.currentTimeMillis())
        writeMeta(ref.dir, stamped)
        return BookRef(ref.dir, stamped)
    }

    fun deleteBook(ref: BookRef) {
        ref.dir.deleteRecursively()
        invalidate()
    }

    fun touchBook(ref: BookRef) {
        val meta = readMeta(ref.dir) ?: return
        writeMeta(ref.dir, meta.copy(modifiedAt = System.currentTimeMillis()))
    }

    /** 封面文件句柄（存在时才返回）。 */
    fun coverFile(ref: BookRef): FsEntry? =
        (readMeta(ref.dir)?.cover ?: ref.meta.cover)
            ?.let { ref.dir.child(it) }
            ?.takeIf { it.exists }

    /** 写入封面（已是 JPEG 字节），返回更新后的引用。 */
    fun setCover(ref: BookRef, jpegBytes: ByteArray): BookRef {
        val target = ref.dir.child("cover.jpg")
        target.writeBytes(jpegBytes)
        val old = ref.meta.cover
        if (old != null && old != target.name) ref.dir.child(old).takeIf { it.exists }?.delete()
        val meta = (readMeta(ref.dir) ?: ref.meta).copy(
            cover = target.name,
            modifiedAt = System.currentTimeMillis(),
        )
        writeMeta(ref.dir, meta)
        return BookRef(ref.dir, meta)
    }

    fun clearCover(ref: BookRef): BookRef {
        val meta = readMeta(ref.dir) ?: ref.meta
        meta.cover?.let { ref.dir.child(it).takeIf { f -> f.exists }?.delete() }
        val updated = meta.copy(cover = null, modifiedAt = System.currentTimeMillis())
        writeMeta(ref.dir, updated)
        return BookRef(ref.dir, updated)
    }

    /**
     * 开关某本书的 Web 共享。共享 = 局域网里的电脑可以编辑它；这里只改标记，不碰服务。
     * 有意不动 modifiedAt：这只是授权开关，不是内容修改，不该让书在书架里跳到最前面。
     */
    fun setShare(ref: BookRef, enabled: Boolean): BookRef {
        val meta = readMeta(ref.dir) ?: ref.meta
        if (meta.shareEnabled == enabled) return BookRef(ref.dir, meta)
        val updated = meta.copy(shareEnabled = enabled)
        writeMeta(ref.dir, updated)
        return BookRef(ref.dir, updated)
    }

    // ─────────────────────────── 载入整棵树 ───────────────────────────

    /** 扫一遍整棵树并建立路径表缓存，返回可直接渲染的 [BookDoc]。 */
    fun loadDoc(ref: BookRef): BookDoc {
        val meta = readMeta(ref.dir) ?: ref.meta
        val body = bodyDir(ref)
        val nodes = LinkedHashMap<String, NodeInfo>()
        val dirs = HashMap<String, FsEntry>()
        val parents = HashMap<String, String?>()
        val rootOrder = ArrayList<String>()

        val pending = body.list()
            .filter { it.isDirectory && !it.name.startsWith(".") }
            .map { it.name }
            .toMutableSet()

        // 以磁盘为准：先按 book.info 里记的顺序，再把多出来的文件夹按名字补在后面
        for (id in meta.order) if (pending.remove(id)) rootOrder.add(id)
        for (id in pending.sorted()) rootOrder.add(id)

        val effectiveMeta =
            if (rootOrder != meta.order) meta.copy(order = rootOrder)
            else meta
        if (rootOrder != meta.order) writeMeta(ref.dir, effectiveMeta)

        for (id in rootOrder) readNodeTree(body, id, null, nodes, dirs, parents)

        index = Index(ref.key, nodes, dirs, parents)
        return BookDoc(
            ref = ref,
            meta = effectiveMeta,
            nodes = nodes,
            glossary = loadGlossary(ref),
        )
    }

    private fun readNodeTree(
        parentDir: FsEntry,
        id: String,
        parentId: String?,
        nodes: MutableMap<String, NodeInfo>,
        dirs: MutableMap<String, FsEntry>,
        parents: MutableMap<String, String?>,
    ) {
        val dir = parentDir.child(id)
        if (!dir.isDirectory) return
        dirs[id] = dir
        parents[id] = parentId
        val infoFile = dir.child(FILE_NODE_INFO)
        val stored = readNodeInfo(dir, id)
        val pending = dir.list()
            .filter { it.isDirectory && !it.name.startsWith(".") }
            .map { it.name }
            .toMutableSet()
        val order = ArrayList<String>(stored.children.size + pending.size)
        for (c in stored.children) if (pending.remove(c)) order.add(c)
        for (c in pending.sorted()) order.add(c)

        val node = if (order != stored.children) stored.copy(children = order) else stored
        nodes[id] = node
        // this.info 缺失（手工丢进来的文件夹）或子级顺序变了 → 落盘一次
        if (!infoFile.exists || order != stored.children) writeNodeInfo(dir, node)
        for (c in order) readNodeTree(dir, c, id, nodes, dirs, parents)
    }

    // ─────────────────────────── 节点操作 ───────────────────────────

    /**
     * 在 [parentId]（null = 正文根）下追加一个新节点。
     * [kind] = [NodeKind.Group] 是目录（只能写简介），[NodeKind.Chapter] 是可写正文的章。
     */
    fun addNode(
        ref: BookRef,
        parentId: String?,
        title: String,
        kind: NodeKind = NodeKind.Chapter,
    ): NodeInfo {
        val now = System.currentTimeMillis()
        val parentDir = if (parentId == null) {
            bodyDir(ref)
        } else {
            findNode(ref, parentId)?.dir ?: error("父级节点不存在：$parentId")
        }
        parentDir.mkdirs()
        var id = newHashId(title)
        while (parentDir.child(id).exists) id = newHashId(title)
        parentDir.child(id).mkdirs()
        val info = NodeInfo(
            id = id,
            title = title.ifBlank { if (kind == NodeKind.Group) "新卷" else "新章" },
            createdAt = now,
            modifiedAt = now,
            kind = kind,
        )
        writeNodeInfo(parentDir.child(id), info)
        mutateOrder(ref, parentId) { it + id }
        touchBook(ref)
        index = null // 结构变了，路径表下次访问重建
        return info
    }

    fun updateNode(ref: BookRef, nodeId: String, title: String, summary: String) {
        val dir = findNode(ref, nodeId)?.dir ?: return
        val info = readNodeInfo(dir, nodeId)
        writeNodeInfo(
            dir,
            info.copy(
                title = title.ifBlank { info.title },
                summary = summary,
                modifiedAt = System.currentTimeMillis(),
            ),
        )
        touchBook(ref)
    }

    fun deleteNode(ref: BookRef, nodeId: String) {
        val found = findNode(ref, nodeId) ?: return
        mutateOrder(ref, found.parentId) { it - nodeId }
        found.dir.deleteRecursively()
        touchBook(ref)
        invalidate()
    }

    /** 同级上下移动（[delta] = -1 上移 / +1 下移）。 */
    fun moveNode(ref: BookRef, nodeId: String, delta: Int) {
        val located = findNode(ref, nodeId) ?: return
        mutateOrder(ref, located.parentId) { ids ->
            val idx = ids.indexOf(nodeId)
            if (idx < 0) return@mutateOrder ids
            val to = (idx + delta).coerceIn(0, ids.size - 1)
            if (to == idx) return@mutateOrder ids
            ids.toMutableList().apply { removeAt(idx); add(to, nodeId) }
        }
        touchBook(ref)
        index = null
    }

    /** 把节点挂到另一个父级下（[newParentId] = null 表示移到正文根）。 */
    fun reparentNode(ref: BookRef, nodeId: String, newParentId: String?): Boolean {
        if (nodeId == newParentId) return false
        val found = findNode(ref, nodeId) ?: return false
        val oldParent = found.parentId
        if (oldParent == newParentId) return false
        // 不允许移动到自己的子孙下面（否则会把子树剪断）
        if (newParentId != null && isDescendant(ref, nodeId, newParentId)) return false

        val toDir = if (newParentId == null) {
            bodyDir(ref)
        } else {
            findNode(ref, newParentId)?.dir ?: return false
        }
        val src = found.dir
        val dst = toDir.child(nodeId)
        if (!src.isDirectory || dst.exists) return false
        toDir.mkdirs()
        // 抽象层不保证能"跨父目录改名"，统一用复制 + 删除来实现移动
        copyEntry(src, dst)
        src.deleteRecursively()
        mutateOrder(ref, oldParent) { it - nodeId }
        mutateOrder(ref, newParentId) { it + nodeId }
        touchBook(ref)
        invalidate()
        return true
    }

    /** [ancestorId] 的子树里是否包含 [nodeId]。 */
    fun isDescendant(ref: BookRef, ancestorId: String, nodeId: String): Boolean {
        val dir = findNode(ref, ancestorId)?.dir ?: return false
        fun walk(d: FsEntry): Boolean {
            for (child in d.list()) {
                if (!child.isDirectory) continue
                if (child.name == nodeId) return true
                if (walk(child)) return true
            }
            return false
        }
        return walk(dir)
    }

    // ─────────────────────────── 正文读写 ───────────────────────────

    fun readContent(ref: BookRef, nodeId: String): String {
        val dir = findNode(ref, nodeId)?.dir ?: return ""
        return dir.child(FILE_CONTENT).readText()
    }

    /**
     * 保存某一级的正文。**只有 [NodeKind.Chapter] 类型的节点能写**，
     * 目录（卷）返回 null（界面据此给出提示）。空文本会删掉 `content.txt`。
     * 返回刷新过 modifiedAt 的节点信息，供界面直接替换内存副本。
     */
    fun writeContent(ref: BookRef, nodeId: String, text: String): NodeInfo? {
        val located = findNode(ref, nodeId) ?: return null
        val dir = located.dir
        val info = readNodeInfo(dir, nodeId)
        if (!info.writable) return null

        dir.mkdirs()
        val file = dir.child(FILE_CONTENT)
        val before = if (file.exists) countCharsOf(file.readText()) else 0
        if (text.isEmpty()) {
            if (file.exists) file.delete()
        } else {
            file.writeText(text)
        }
        adjustChars(ref, countCharsOf(text) - before)

        val now = System.currentTimeMillis()
        val updated = info.copy(modifiedAt = now)
        writeNodeInfo(dir, updated)
        touchBook(ref)
        return updated
    }

    /** 整本书的字数统计（按字符计，忽略空白）。带缓存，只在需要时才重扫。 */
    fun countChars(ref: BookRef): Int {
        val cached = charsCache
        if (cached != null && cached.first == ref.key) return cached.second
        val total = scanChars(ref)
        charsCache = ref.key to total
        return total
    }

    private fun scanChars(ref: BookRef): Int {
        val body = ref.dir.child(DIR_BODY)
        if (!body.isDirectory) return 0
        var total = 0
        fun walk(entry: FsEntry) {
            for (child in entry.list()) {
                if (child.isDirectory) {
                    walk(child)
                } else if (child.name == FILE_CONTENT) {
                    total += countCharsOf(child.readText())
                }
            }
        }
        walk(body)
        return total
    }

    private fun adjustChars(ref: BookRef, delta: Int) {
        if (delta == 0) return
        val cached = charsCache ?: return
        if (cached.first != ref.key) return
        charsCache = ref.key to (cached.second + delta).coerceAtLeast(0)
    }

    // ─────────────────────────── 快捷输入词表 ───────────────────────────

    fun loadGlossary(ref: BookRef): Glossary =
        Glossary.fromJson(readJson(ref.dir.child(FILE_GLOSSARY)))

    fun saveGlossary(ref: BookRef, glossary: Glossary) {
        ref.dir.child(FILE_GLOSSARY).writeText(glossary.toJson().toString(2))
    }

    // ─────────────────────────── 内部实现 ───────────────────────────

    /** 在磁盘上定位一个节点：拿到它所在的目录，以及它在哪一层（[parentId] 为 null 表示正文根）。 */
    class Located(val parentId: String?, val dir: FsEntry)

    /**
     * 优先走路径表缓存；未命中（刚被外部改动、或还没 loadDoc）才老实扫一遍，
     * 扫描同时会把缓存重建起来。
     */
    fun findNode(ref: BookRef, nodeId: String): Located? {
        val cached = index
        if (cached != null && cached.bookPath == ref.key) {
            val dir = cached.dirs[nodeId]
            if (dir != null) return Located(cached.parents[nodeId], dir)
        }
        loadDoc(ref) // 扫描的同时会把 index 重建好
        val fresh = index ?: return null
        val dir = fresh.dirs[nodeId] ?: return null
        return Located(fresh.parents[nodeId], dir)
    }

    fun bodyDir(ref: BookRef): FsEntry = ref.dir.child(DIR_BODY).also { it.mkdirs() }

    private inline fun mutateOrder(ref: BookRef, parentId: String?, change: (List<String>) -> List<String>) {
        if (parentId == null) {
            val meta = readMeta(ref.dir) ?: return
            writeMeta(ref.dir, meta.copy(order = change(meta.order).distinct()))
        } else {
            val dir = findNode(ref, parentId)?.dir ?: return
            val info = readNodeInfo(dir, parentId)
            writeNodeInfo(dir, info.copy(children = change(info.children).distinct()))
        }
    }

    private fun copyEntry(src: FsEntry, dst: FsEntry) {
        if (src.isDirectory) {
            dst.mkdirs()
            src.list().forEach { copyEntry(it, dst.child(it.name)) }
        } else {
            dst.writeBytes(src.readBytes())
        }
    }

    private fun readBookRef(dir: FsEntry): BookRef? {
        if (!dir.isDirectory) return null
        val meta = readMeta(dir) ?: run {
            // 没有 book.info：可能是手工放进来的文件夹，补一份元数据
            if (!dir.child(DIR_BODY).isDirectory) return null
            val now = System.currentTimeMillis()
            val generated = BookMeta(id = md5Hex32(dir.name), title = dir.name, createdAt = now, modifiedAt = now)
            writeMeta(dir, generated)
            generated
        }
        return BookRef(dir, meta)
    }

    private fun readMeta(dir: FsEntry): BookMeta? {
        val json = readJson(dir.child(FILE_BOOK_INFO)) ?: return null
        return try {
            BookMeta.fromJson(json, md5Hex32(dir.name))
        } catch (e: Exception) {
            null
        }
    }

    private fun writeMeta(dir: FsEntry, meta: BookMeta) {
        dir.mkdirs()
        dir.child(FILE_BOOK_INFO).writeText(meta.toJson().toString(2))
    }

    private fun readNodeInfo(dir: FsEntry, id: String): NodeInfo {
        val json = readJson(dir.child(FILE_NODE_INFO))
        return NodeInfo.fromJson(json, id, id)
    }

    private fun writeNodeInfo(dir: FsEntry, info: NodeInfo) {
        dir.mkdirs()
        dir.child(FILE_NODE_INFO).writeText(info.toJson().toString(2))
    }

    private fun readJson(file: FsEntry): JSONObject? = try {
        if (file.exists && !file.isDirectory) JSONObject(file.readText()) else null
    } catch (e: Exception) {
        null
    }

    private fun countCharsOf(text: String): Int = text.count { !it.isWhitespace() }
}
