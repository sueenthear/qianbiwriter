package com.qianbi.writer.data

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import javax.crypto.Cipher

/**
 * 核心逻辑的纯 JVM 测试（不需要设备/模拟器）：
 * 书本文件夹结构、32 位哈希命名、多级嵌套、非叶子放正文、
 * .book 打包的导出/导入往返，以及口令加密的校验与防篡改。
 */
class NovelCoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun newStore() = BookStore(FileFsEntry(File(tmp.root, "books")))

    // ─────────────────────────── 目录结构 ───────────────────────────

    @Test
    fun `书本文件夹结构与任意深度嵌套`() {
        val store = newStore()
        val ref = store.createBook("测试小说", "作者甲", tags = listOf("玄幻", "修仙"))
        assertTrue(ref.dir.child(FILE_BOOK_INFO).isFile)
        assertTrue(ref.dir.child(DIR_BODY).isDirectory)

        // 书 - 卷 - 章 - 节 - 更深处（5 级）
        val vol = store.addNode(ref, null, "第一卷")
        val chap = store.addNode(ref, vol.id, "第一章")
        val sect = store.addNode(ref, chap.id, "第一节")
        val deep = store.addNode(ref, sect.id, "更深处")

        // 子文件夹用 32 位哈希串命名，章节重名也不冲突
        assertEquals(32, vol.id.length)
        assertTrue(vol.id.matches(Regex("[0-9a-f]{32}")))
        assertTrue(ref.dir.child("$DIR_BODY/${vol.id}/$FILE_NODE_INFO").isFile)
        assertTrue(ref.dir.child("$DIR_BODY/${vol.id}/${chap.id}/$FILE_NODE_INFO").isFile)
        assertTrue(ref.dir.child("$DIR_BODY/${vol.id}/${chap.id}/${sect.id}/${deep.id}").isDirectory)

        // 非叶子级同样可以放文本
        store.writeContent(ref, vol.id, "卷首语")
        assertEquals("卷首语", ref.dir.child("$DIR_BODY/${vol.id}/$FILE_CONTENT").readText())

        val doc = store.loadDoc(ref)
        assertEquals(4, doc.nodes.size)
        assertEquals(listOf(vol.id), doc.meta.order)
        assertEquals(listOf(chap.id), doc.nodes[vol.id]!!.children)
        assertEquals(listOf(sect.id), doc.nodes[chap.id]!!.children)
        assertEquals(listOf(deep.id), doc.nodes[sect.id]!!.children)
        assertEquals(4, doc.flatten().size)
        // 书 - 卷 - 章 - 节 - 更深处 → 最深那层 depth = 3
        assertEquals(3, doc.flatten().last().depth)
        assertEquals(listOf("测试小说", "第一卷", "第一章"), doc.breadcrumb(chap.id))
        assertEquals(listOf("玄幻", "修仙"), doc.meta.tags)
        assertEquals("作者甲", doc.meta.author)
        assertTrue(doc.meta.createdAt > 0L)
        assertTrue(doc.meta.modifiedAt >= doc.meta.createdAt)
    }

    @Test
    fun `重名章节落在不同的哈希文件夹`() {
        val store = newStore()
        val ref = store.createBook("重名测试")
        val first = store.addNode(ref, null, "第一章")
        val second = store.addNode(ref, null, "第一章")

        assertNotEquals(first.id, second.id)
        assertTrue(ref.dir.child("$DIR_BODY/${first.id}").isDirectory)
        assertTrue(ref.dir.child("$DIR_BODY/${second.id}").isDirectory)
        assertEquals(listOf(first.id, second.id), store.loadDoc(ref).meta.order)
    }

    @Test
    fun `删除排序与跨级移动`() {
        val store = newStore()
        val ref = store.createBook("操作测试")
        val a = store.addNode(ref, null, "A")
        val b = store.addNode(ref, null, "B")
        val c = store.addNode(ref, null, "C")

        store.moveNode(ref, c.id, -1)
        assertEquals(listOf(a.id, c.id, b.id), store.loadDoc(ref).meta.order)

        store.deleteNode(ref, c.id)
        val afterDelete = store.loadDoc(ref)
        assertEquals(listOf(a.id, b.id), afterDelete.meta.order)
        assertFalse(ref.dir.child("$DIR_BODY/${c.id}").exists)

        // 把 B 挂到 A 下面
        assertTrue(store.reparentNode(ref, b.id, a.id))
        val moved = store.loadDoc(ref)
        assertEquals(listOf(a.id), moved.meta.order)
        assertEquals(listOf(b.id), moved.nodes[a.id]!!.children)
        assertTrue(ref.dir.child("$DIR_BODY/${a.id}/${b.id}/$FILE_NODE_INFO").isFile)

        // 不允许把 A 移到自己的子孙里
        val grand = store.addNode(ref, b.id, "孙")
        assertFalse(store.reparentNode(ref, a.id, grand.id))
        assertEquals(listOf(b.id), store.loadDoc(ref).nodes[a.id]!!.children)
    }

    @Test
    fun `手工丢进 book 目录的文件夹会被自动接管`() {
        val store = newStore()
        val ref = store.createBook("手工")
        val manual = "0123456789abcdef0123456789abcdef"
        val dir = ref.dir.child("$DIR_BODY/$manual")
        dir.mkdirs()

        val doc = store.loadDoc(ref)
        assertEquals(listOf(manual), doc.meta.order)
        assertEquals(manual, doc.nodes[manual]!!.title)
        assertTrue(dir.child(FILE_NODE_INFO).isFile)
    }

    // ─────────────────────────── 加密原语 ───────────────────────────

    @Test
    fun `PBKDF2 派生与 AES-CTR 往返`() {
        val salt = CryptoUtil.newSalt()
        val keys = CryptoUtil.deriveKeys("口令".toCharArray(), salt, 1_000)
        val plain = "第四章 · 风雪夜归人".toByteArray(Charsets.UTF_8)

        val cipherText = CryptoUtil.ctr(Cipher.ENCRYPT_MODE, keys.enc).doFinal(plain)
        assertFalse(cipherText.contentEquals(plain))
        assertArrayEquals(plain, CryptoUtil.ctr(Cipher.DECRYPT_MODE, keys.enc).doFinal(cipherText))

        // 同 salt + 同口令 → 同密钥；不同 salt → 不同密钥
        val again = CryptoUtil.deriveKeys("口令".toCharArray(), salt, 1_000)
        assertArrayEquals(keys.enc, again.enc)
        assertArrayEquals(CryptoUtil.verifier(keys), CryptoUtil.verifier(again))
        val other = CryptoUtil.deriveKeys("口令".toCharArray(), CryptoUtil.newSalt(), 1_000)
        assertFalse(CryptoUtil.matches(other, CryptoUtil.verifier(keys)))

        // 口令错 → verifier 不匹配
        val wrong = CryptoUtil.deriveKeys("错误口令".toCharArray(), salt, 1_000)
        assertFalse(CryptoUtil.matches(wrong, CryptoUtil.verifier(keys)))
    }

    // ─────────────────────────── .book 双向解析 ───────────────────────────

    @Test
    fun `明文包导出导入往返`() {
        val store = newStore()
        val ref = prepareBook(store)
        val bytes = export(ref, null)

        val header = BookPackage.readHeader(ByteArrayInputStream(bytes))
        assertFalse(header.encrypted)

        val imported = BookPackage.import(store, ByteArrayInputStream(bytes), null)
        assertNotEquals(ref.id, imported.id)          // 书已存在 → 作为副本导入
        assertNotEquals(ref.dir.name, imported.dir.name)
        assertSameTree(ref.dir, imported.dir)
    }

    @Test
    fun `加密包的密码校验与往返`() {
        val store = newStore()
        val ref = prepareBook(store)
        val password = "s3cret-密码-2026"
        val bytes = export(ref, password)

        val header = BookPackage.readHeader(ByteArrayInputStream(bytes))
        assertTrue(header.encrypted)
        assertEquals(CryptoUtil.DEFAULT_ITERATIONS, header.iterations)
        assertTrue(BookPackage.checkPassword(header, password))
        assertFalse(BookPackage.checkPassword(header, "错误口令"))

        // 没给密码 → NeedPasswordException
        try {
            BookPackage.import(store, ByteArrayInputStream(bytes), null)
            fail("未提供口令时不应导入成功")
        } catch (e: NeedPasswordException) {
            // 预期
        }

        // 密码错 → WrongPasswordException，且不应留下半本书
        try {
            BookPackage.import(store, ByteArrayInputStream(bytes), "错误口令")
            fail("口令错误时不应导入成功")
        } catch (e: WrongPasswordException) {
            // 预期
        }
        assertEquals(1, store.listBooks().size)

        // 密码正确 → 结构与内容完全一致
        val imported = BookPackage.import(store, ByteArrayInputStream(bytes), password)
        assertSameTree(ref.dir, imported.dir)
        assertEquals(2, imported.meta.tags.size)
    }

    @Test
    fun `密文被篡改会被 HMAC 检出`() {
        val store = newStore()
        val ref = prepareBook(store)
        val password = "pw"
        val bytes = export(ref, password)
        bytes[bytes.size - 100] = (bytes[bytes.size - 100].toInt() xor 0x5A).toByte()

        try {
            BookPackage.import(store, ByteArrayInputStream(bytes), password)
            fail("被篡改的包不应导入成功")
        } catch (e: InvalidPackageException) {
            // 预期
        }
        assertEquals(1, store.listBooks().size)
    }

    @Test
    fun `不是 book 包的输入会被拒绝`() {
        val store = newStore()
        try {
            BookPackage.import(store, ByteArrayInputStream("hello, this is not a book".toByteArray()), null)
            fail("非 .book 输入不应导入成功")
        } catch (e: InvalidPackageException) {
            // 预期
        }
    }

    @Test
    fun `导入的书直接出现在书架并可继续编辑`() {
        val store = newStore()
        val ref = prepareBook(store)
        val bytes = export(ref, null)
        val imported = BookPackage.import(store, ByteArrayInputStream(bytes), null)

        val shelf = store.listBooks()
        assertEquals(2, shelf.size)
        assertTrue(shelf.any { it.id == imported.id })
        assertTrue(shelf.any { it.id == ref.id })

        val doc = store.loadDoc(imported)
        val leaf = doc.flatten().first { it.node.children.isEmpty() }.node
        store.writeContent(imported, leaf.id, "导入之后新写的一段")
        assertEquals("导入之后新写的一段", store.readContent(imported, leaf.id))
        assertTrue(store.countChars(imported) > 0)
    }

    // ─────────────────────────── 辅助 ───────────────────────────

    // ─────────────────────────── 辅助 ───────────────────────────

    private fun prepareBook(store: BookStore): BookRef {
        val ref = store.createBook("加密测试", "作者乙", "简介内容", listOf("都市", "悬疑"))
        val vol = store.addNode(ref, null, "卷一")
        val ch1 = store.addNode(ref, vol.id, "第一章")
        val ch2 = store.addNode(ref, vol.id, "第二章")
        store.writeContent(ref, vol.id, "卷首语：故事从这里开始。")
        store.writeContent(ref, ch1.id, "正文一。\n第二行。")
        store.writeContent(ref, ch2.id, "")
        store.updateNode(ref, ch1.id, "第一章", "第一章的简介")
        store.saveGlossary(
            ref,
            Glossary(
                listOf(
                    GlossaryItem("林逸", "林逸", "角色"),
                    GlossaryItem("青云宗", "青云宗", "地名"),
                )
            )
        )
        return ref
    }

    private fun export(ref: BookRef, password: String?): ByteArray {
        val out = ByteArrayOutputStream()
        BookPackage.export(ref.dir, out, password)
        return out.toByteArray()
    }

    /** 比较两本书的正文结构（this.info 里的 id 会变，故按题目/简介/子级顺序比对）。 */
    private fun assertSameTree(expected: FsEntry, actual: FsEntry) {
        val e = bodySnapshot(expected)
        val a = bodySnapshot(actual)
        assertEquals(e.keys, a.keys)
        for ((path, text) in e) {
            when {
                path.endsWith(FILE_CONTENT) -> assertEquals("内容不一致：$path", text, a[path])
                path.endsWith(FILE_NODE_INFO) -> {
                    val ej = JSONObject(text)
                    val aj = JSONObject(a.getValue(path))
                    assertEquals(ej.getString("title"), aj.getString("title"))
                    assertEquals(ej.getString("summary"), aj.getString("summary"))
                    assertEquals(ej.getJSONArray("children").toString(), aj.getJSONArray("children").toString())
                    assertEquals(ej.getLong("createdAt"), aj.getLong("createdAt"))
                }
            }
        }
        val em = JSONObject(expected.child(FILE_BOOK_INFO).readText())
        val am = JSONObject(actual.child(FILE_BOOK_INFO).readText())
        assertEquals(em.getString("title"), am.getString("title"))
        assertEquals(em.getString("author"), am.getString("author"))
        assertEquals(em.getString("summary"), am.getString("summary"))
        assertEquals(em.getJSONArray("tags").toString(), am.getJSONArray("tags").toString())
        assertEquals(em.getJSONArray("order").toString(), am.getJSONArray("order").toString())
        assertEquals(em.getLong("createdAt"), am.getLong("createdAt"))
        assertEquals(
            expected.child(FILE_GLOSSARY).takeIf { it.isFile }?.readText(),
            actual.child(FILE_GLOSSARY).takeIf { it.isFile }?.readText(),
        )
    }

    private fun bodySnapshot(dir: FsEntry): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        fun walk(current: FsEntry, prefix: String) {
            current.list().sortedBy { it.name }.forEach { file ->
                val rel = if (prefix.isEmpty()) file.name else "$prefix/${file.name}"
                if (file.isDirectory) walk(file, rel) else out[rel] = file.readText()
            }
        }
        walk(dir.child(DIR_BODY), "")
        return out
    }
}
