package com.qianbi.writer.web

/**
 * 手机端「Web 协作」面板里那串实时动态。
 *
 * 只留在内存里（环形缓冲，超了丢最旧的），服务器一停就清空 —— 这是给用户看
 * 「刚才哪台电脑动了什么」的，不是审计日志，所以不落盘，免得在书稿目录里塞进
 * 一堆和作品无关的文件。也因此它永远不会跟着 `.book` 导出。
 */
object WebLog {

    private const val CAPACITY = 150

    /** 一条动态：什么时候、哪台电脑、做了什么、结果如何。 */
    data class Entry(
        val at: Long,
        val from: String,
        val action: String,
        val detail: String,
        val status: Int,
    ) {
        val ok: Boolean get() = status in 200..299
    }

    private val entries = ArrayDeque<Entry>()
    private val lock = Any()

    fun record(from: String?, action: String, detail: String = "", status: Int = 200) {
        val entry = Entry(
            at = System.currentTimeMillis(),
            from = from?.takeIf { it.isNotBlank() } ?: "-",
            action = action,
            detail = detail,
            status = status,
        )
        synchronized(lock) {
            entries.addLast(entry)
            while (entries.size > CAPACITY) entries.removeFirst()
        }
    }

    /** 最新的排在最前面，界面直接铺即可。 */
    fun snapshot(): List<Entry> = synchronized(lock) { entries.toList().asReversed() }

    fun clear() {
        synchronized(lock) { entries.clear() }
    }
}
