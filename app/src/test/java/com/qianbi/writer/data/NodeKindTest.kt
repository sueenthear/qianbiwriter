package com.qianbi.writer.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 节点类型规则：
 * - **只有「章」能写正文**，目录（卷）只能写简介；
 * - 「章」可以挂到任何一级下面（卷里、卷的卷里、甚至别的章下面）；
 * - 类型会写进 `this.info`，没有 kind 字段的旧数据按"有无子级"推断。
 */
class NodeKindTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun newStore() = BookStore(FileFsEntry(File(tmp.root, "books")))

    @Test
    fun `只有章能写正文，目录（卷）只能写简介`() {
        val store = newStore()
        val ref = store.createBook("类型测试")
        val vol = store.addNode(ref, null, "第一卷", NodeKind.Group)
        val chap = store.addNode(ref, vol.id, "第一章", NodeKind.Chapter)

        // 卷：写正文被拒绝，磁盘上也不会出现 content.txt
        assertNull(store.writeContent(ref, vol.id, "卷首语"))
        assertFalse(ref.dir.child(DIR_BODY).child(vol.id).child(FILE_CONTENT).exists)
        assertEquals("", store.readContent(ref, vol.id))

        // 章：正常写
        assertNotNull(store.writeContent(ref, chap.id, "正文一二三"))
        assertEquals("正文一二三", store.readContent(ref, chap.id))

        // 类型落盘并能读回
        val doc = store.loadDoc(ref)
        assertEquals(NodeKind.Group, doc.nodes[vol.id]!!.kind)
        assertEquals(NodeKind.Chapter, doc.nodes[chap.id]!!.kind)
        assertFalse(doc.nodes[vol.id]!!.writable)
        assertTrue(doc.nodes[chap.id]!!.writable)

        val onDisk = JSONObject(ref.dir.child(DIR_BODY).child(vol.id).child(FILE_NODE_INFO).readText())
        assertEquals("group", onDisk.getString("kind"))
    }

    @Test
    fun `卷可以套卷，章可以挂在任意层级`() {
        val store = newStore()
        val ref = store.createBook("深层测试")
        val v1 = store.addNode(ref, null, "卷一", NodeKind.Group)
        val v2 = store.addNode(ref, v1.id, "卷二", NodeKind.Group)
        val c1 = store.addNode(ref, v2.id, "第一章", NodeKind.Chapter)
        val c2 = store.addNode(ref, c1.id, "章下面再来一章", NodeKind.Chapter)

        val doc = store.loadDoc(ref)
        assertEquals(4, doc.nodes.size)
        assertEquals(listOf(c1.id), doc.nodes[v2.id]!!.children)
        assertEquals(listOf(c2.id), doc.nodes[c1.id]!!.children)
        assertEquals(3, doc.flatten().last().depth) // 卷 › 卷 › 章 › 章

        // 最深一层照样能写正文
        assertNotNull(store.writeContent(ref, c2.id, "最深一层也能写"))
        assertEquals("最深一层也能写", store.readContent(ref, c2.id))

        // 字数按增量维护
        assertEquals(7, store.countChars(ref))
        store.writeContent(ref, c2.id, "更深一层也能写")
        assertEquals(7, store.countChars(ref))
        store.writeContent(ref, c2.id, "")
        assertEquals(0, store.countChars(ref))
    }

    @Test
    fun `旧数据没有 kind 字段时按有无子级推断`() {
        val store = newStore()
        val ref = store.createBook("旧数据")

        // 没有子级 → 当正文（章）
        val leafId = "aabbccddeeff00112233445566778899"
        val leafDir = ref.dir.child(DIR_BODY).child(leafId)
        leafDir.mkdirs()
        leafDir.child(FILE_NODE_INFO).writeText(
            """{"format":1,"id":"$leafId","title":"老的一级","summary":"","createdAt":1,"modifiedAt":1,"children":[]}"""
        )

        // 有子级 → 当目录（卷）
        val groupId = "00112233445566778899aabbccddeeff"
        val groupDir = ref.dir.child(DIR_BODY).child(groupId)
        groupDir.mkdirs()
        groupDir.child(FILE_NODE_INFO).writeText(
            """{"format":1,"id":"$groupId","title":"老的卷","summary":"","createdAt":1,"modifiedAt":1,"children":["$leafId"]}"""
        )

        val doc = store.loadDoc(ref)
        assertEquals(NodeKind.Chapter, doc.nodes[leafId]!!.kind)
        assertTrue(doc.nodes[leafId]!!.writable)
        assertEquals(NodeKind.Group, doc.nodes[groupId]!!.kind)
        assertFalse(doc.nodes[groupId]!!.writable)
    }
}
