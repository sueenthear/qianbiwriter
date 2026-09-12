package com.qianbi.writer.web

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.URLDecoder

/**
 * 一次已经读完请求体的 HTTP 请求。
 *
 * 这里只实现 PC 端页面用得上的那部分 HTTP：请求行 + 头 + `Content-Length` 定长的 body。
 * 不处理 chunked，也不做长连接 —— 每个请求一条连接、答完即关。局域网内一个浏览器，
 * 这点开销完全可以接受，换来的是这套代码没有任何第三方依赖、出问题也容易看懂。
 */
class HttpRequest(
    val method: String,
    val path: String,
    val query: Map<String, String>,
    val headers: Map<String, String>,
    val body: ByteArray,
    /** 客户端地址：手机端那串动态靠它区分是哪台电脑。 */
    val remote: String = "",
) {
    fun header(name: String): String? = headers[name.lowercase()]

    fun param(name: String): String? = query[name]

    val text: String get() = String(body, Charsets.UTF_8)

    fun json(): JSONObject? = try {
        if (body.isEmpty()) null else JSONObject(text)
    } catch (e: Exception) {
        null
    }
}

/** 要写回客户端的一次响应。 */
class HttpResponse(
    val status: Int,
    val contentType: String,
    val body: ByteArray,
    val extraHeaders: Map<String, String> = emptyMap(),
) {
    companion object {
        fun text(status: Int, body: String, type: String = "text/plain; charset=utf-8") =
            HttpResponse(status, type, body.toByteArray(Charsets.UTF_8))

        fun html(status: Int, body: String) = text(status, body, "text/html; charset=utf-8")

        fun json(status: Int, obj: JSONObject) =
            text(status, obj.toString(), "application/json; charset=utf-8")

        fun ok(obj: JSONObject) = json(200, obj)

        fun bytes(status: Int, data: ByteArray, type: String) = HttpResponse(status, type, data)

        fun error(status: Int, message: String, code: String = "") = json(
            status,
            JSONObject().apply {
                put("ok", false)
                put("error", message)
                if (code.isNotEmpty()) put("code", code)
            },
        )

        fun notFound(message: String = "没有这个地址") = error(404, message, "not_found")
    }
}

/** HTTP/1.1 的最小读写实现。 */
object HttpCodec {

    /** 请求体上限：挡住往连接里灌数据吃内存的行为。 */
    const val MAX_BODY_BYTES = 16 * 1024 * 1024

    private const val MAX_LINE_BYTES = 8 * 1024

    /** 读不出完整请求行就返回 null（连接被关掉，或者有人只是探测端口）。 */
    fun readRequest(input: InputStream, remote: String = ""): HttpRequest? {
        val requestLine = readLine(input) ?: return null
        if (requestLine.isBlank()) return null
        val parts = requestLine.split(' ')
        if (parts.size < 2) return null

        val method = parts[0].uppercase()
        val target = parts[1]

        val headers = LinkedHashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            headers[line.take(colon).trim().lowercase()] = line.substring(colon + 1).trim()
        }

        val declared = headers["content-length"]?.toIntOrNull() ?: 0
        val length = declared.coerceIn(0, MAX_BODY_BYTES)
        val body = if (length > 0) readFully(input, length) else ByteArray(0)

        val mark = target.indexOf('?')
        val rawPath = if (mark >= 0) target.take(mark) else target
        val rawQuery = if (mark >= 0) target.substring(mark + 1) else ""

        return HttpRequest(method, decode(rawPath), parseQuery(rawQuery), headers, body, remote)
    }

    fun write(out: OutputStream, response: HttpResponse) {
        val head = buildString {
            append("HTTP/1.1 ").append(response.status).append(' ')
            append(reason(response.status)).append("\r\n")
            append("Content-Type: ").append(response.contentType).append("\r\n")
            append("Content-Length: ").append(response.body.size).append("\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n")
            for ((name, value) in response.extraHeaders) {
                append(name).append(": ").append(value).append("\r\n")
            }
            append("\r\n")
        }
        out.write(head.toByteArray(Charsets.US_ASCII))
        if (response.body.isNotEmpty()) out.write(response.body)
        out.flush()
    }

    /** 按字节读到 LF；头/请求行都是 ASCII，不用考虑多字节字符被截断。 */
    private fun readLine(input: InputStream): String? {
        val buffer = ByteArrayOutputStream(128)
        while (true) {
            val b = input.read()
            if (b == -1) return if (buffer.size() == 0) null else buffer.toString("ISO-8859-1")
            if (b == '\n'.code) break
            if (b == '\r'.code) continue
            buffer.write(b)
            if (buffer.size() > MAX_LINE_BYTES) break
        }
        return buffer.toString("ISO-8859-1")
    }

    private fun readFully(input: InputStream, length: Int): ByteArray {
        val out = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(out, read, length - read)
            if (n <= 0) break
            read += n
        }
        return if (read == length) out else out.copyOf(read)
    }

    private fun parseQuery(raw: String): Map<String, String> {
        if (raw.isEmpty()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (pair in raw.split('&')) {
            if (pair.isEmpty()) continue
            val eq = pair.indexOf('=')
            val key = if (eq >= 0) pair.take(eq) else pair
            val value = if (eq >= 0) pair.substring(eq + 1) else ""
            out[decode(key)] = decode(value)
        }
        return out
    }

    private fun decode(raw: String): String = try {
        URLDecoder.decode(raw, "UTF-8")
    } catch (e: Exception) {
        raw
    }

    private fun reason(status: Int): String = when (status) {
        200 -> "OK"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        403 -> "Forbidden"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        409 -> "Conflict"
        413 -> "Payload Too Large"
        500 -> "Internal Server Error"
        else -> "OK"
    }
}
