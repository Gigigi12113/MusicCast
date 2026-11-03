package com.aidigua.musiccast.media

import android.content.Context
import android.net.Uri
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 轻量 HTTP 媒体服务器（NanoHTTPD 2.3.1）。
 *
 * 为什么自己写而不用现成 DLNA 服务器：本 App 只需要把「一首 MP3」以
 * `http://<手机IP>:<port>/m/<token>/<name>.mp3` 的形式暴露给电视，
 * 支持 Range（电视拖动进度条必需）即可。
 *
 * 关键设计：
 *  - token -> Uri 映射，不暴露真实路径，也天然支持 SAF 的 content://；
 *  - 每次投送新 token，旧的保留（电视可能稍后重连）；
 *  - 只允许 GET/HEAD，其余一律 405。
 */
class MediaHttpServer(
    private val context: Context,
    port: Int,
) : NanoHTTPD(port) {

    companion object {
        const val TAG = "MediaHttpServer"
        const val MIME_MP3 = "audio/mpeg"
        private const val MAX_TOKENS = 64
    }

    /** token -> (uri, 展示名) */
    private val registry = ConcurrentHashMap<String, Entry>()
    private val order = ArrayDeque<String>()
    private val seq = AtomicLong(0)
    private val lengthCache = ConcurrentHashMap<String, Long>()

    private data class Entry(val uri: Uri, val name: String, val sizeHint: Long)

    val port: Int get() = listeningPort

    fun register(uri: Uri, displayName: String, sizeHint: Long = 0L): String {
        val token = "t" + seq.incrementAndGet().toString(36) +
            Integer.toHexString((System.nanoTime() and 0xFFFFFF).toInt())
        synchronized(order) {
            registry[token] = Entry(uri, sanitize(displayName), sizeHint)
            order.addLast(token)
            while (order.size > MAX_TOKENS) {
                val old = order.removeFirst()
                registry.remove(old)
            }
        }
        return token
    }

    fun urlFor(token: String, host: String): String {
        val name = registry[token]?.name ?: "audio.mp3"
        return "http://$host:$listeningPort/m/$token/$name"
    }

    fun unregister(token: String) {
        synchronized(order) {
            registry.remove(token)
            order.remove(token)
        }
    }

    fun clearRegistry() {
        synchronized(order) {
            registry.clear()
            order.clear()
            lengthCache.clear()
        }
    }

    private fun sanitize(name: String): String {
        val base = name.substringAfterLast('/').substringAfterLast('\\')
        val safe = base.map { if (it.isLetterOrDigit() || it in "._- ()[]") it else '_' }
            .joinToString("")
        return if (safe.lowercase().endsWith(".mp3")) safe else "$safe.mp3"
    }

    override fun serve(session: IHTTPSession): Response {
        val method = session.method
        if (method != Method.GET && method != Method.HEAD) {
            return newFixedLengthResponse(
                Response.Status.METHOD_NOT_ALLOWED, "text/plain", "only GET/HEAD"
            )
        }

        val uriPath = session.uri ?: "/"
        if (uriPath == "/" || uriPath == "/health") {
            val body = "MusicCast media server\nport=$listeningPort\nitems=${registry.size}\n"
            return newFixedLengthResponse(Response.Status.OK, "text/plain; charset=utf-8", body)
        }

        // 调试用：列出已注册的媒体 URL，方便在浏览器里直接验证 Range
        if (uriPath == "/list") {
            val host = session.headers["host"] ?: "127.0.0.1:$listeningPort"
            val body = synchronized(order) {
                order.joinToString("\n") { tok ->
                    val e = registry[tok]
                    "http://$host/m/$tok/${e?.name ?: "audio.mp3"}"
                }
            }
            return newFixedLengthResponse(Response.Status.OK, "text/plain; charset=utf-8", body)
        }

        val parts = uriPath.trim('/').split('/')
        // /m/<token>/<filename>
        if (parts.size < 2 || parts[0] != "m") return notFound()
        val token = parts[1]
        val entry = registry[token] ?: return notFound()

        return try {
            streamEntry(session, entry)
        } catch (e: Exception) {
            Log.w(TAG, "serving $token failed", e)
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR, "text/plain", "serve error: ${e.message}"
            )
        }
    }

    private fun notFound(): Response =
        newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "not found")

    private fun streamEntry(session: IHTTPSession, entry: Entry): Response {
        val (length, opener) = openStream(entry)
        if (length < 0) {
            return newFixedLengthResponse(
                Response.Status.NOT_FOUND, "text/plain", "cannot open media"
            )
        }

        val rangeHeader = session.headers["range"]
        val isHead = session.method == Method.HEAD

        if (rangeHeader.isNullOrBlank()) {
            // HEAD 也要带正确的 Content-Length：部分电视/音箱靠它判断可播性
            val res = newFixedLengthResponse(
                Response.Status.OK,
                MIME_MP3,
                if (isHead) InputStream.nullInputStream() else opener(),
                if (isHead) 0L else length,
            )
            decorate(res, null, if (isHead) length else null)
            return res
        }

        // Range: bytes=start-end (optimized parsing)
        val spec = rangeHeader.substringAfter("bytes=").trim()
        val dash = spec.indexOf('-')
        if (dash < 0) {
            val res = newFixedLengthResponse(
                Response.Status.OK, MIME_MP3, opener(), length
            )
            decorate(res, null)
            return res
        }

        val startRaw = spec.substring(0, dash).trim()
        val endRaw = spec.substring(dash + 1).trim()
        var start = if (startRaw.isEmpty()) 0L else startRaw.toLongOrNull() ?: 0L
        var end = if (endRaw.isEmpty()) length - 1 else endRaw.toLongOrNull() ?: length - 1

        // Handle suffix-byte-range-spec: bytes=-500
        if (startRaw.isEmpty() && endRaw.isNotEmpty()) {
            val tail = endRaw.toLongOrNull() ?: 0L
            start = (length - tail).coerceAtLeast(0L)
            end = length - 1
        }
        if (start !in 0 until length || end < start) {
            val res = newFixedLengthResponse(
                Response.Status.RANGE_NOT_SATISFIABLE, "text/plain", ""
            )
            res.addHeader("Content-Range", "bytes */$length")
            return res
        }
        if (end > length - 1) end = length - 1

        val count = end - start + 1
        val stream = opener()
        skipFully(stream, start)
        val limited = LimitedInputStream(stream, count)

        val res = newFixedLengthResponse(
            Response.Status.PARTIAL_CONTENT,
            MIME_MP3,
            if (isHead) InputStream.nullInputStream().also { stream.close() } else limited,
            if (isHead) 0L else count,
        )
        decorate(res, "bytes $start-$end/$length", if (isHead) count else null)
        return res
    }

    /**
     * 统一补 DLNA 响应头。
     *
     * 注意：**不要**在这里写 Content-Length —— NanoHTTPD 会按实际 body 长度自行写入；
     * 206 分片时若在这里写文件总长，会把分片长度覆盖成错误值，电视会一直卡在缓冲。
     */
    private fun decorate(res: Response, contentRange: String?, totalLength: Long? = null) {
        res.addHeader("Accept-Ranges", "bytes")
        res.addHeader("Content-Type", MIME_MP3)
        res.addHeader("transferMode.dlna.org", "Streaming")
        res.addHeader("contentFeatures.dlna.org",
            "DLNA.ORG_PN=MP3;DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000")
        res.addHeader("Cache-Control", "no-cache")
        res.addHeader("Connection", "keep-alive")
        if (contentRange != null) res.addHeader("Content-Range", contentRange)
        totalLength?.let { res.addHeader("Content-Length", it.toString()) }
    }

    /** @return (总长度, 新开流) ; 长度 <0 表示不可用 */
    private fun openStream(entry: Entry): Pair<Long, () -> InputStream> {
        val scheme = entry.uri.scheme
        if (scheme == "file") {
            val f = File(entry.uri.path ?: return -1L to { InputStream.nullInputStream() })
            if (!f.isFile) return -1L to { InputStream.nullInputStream() }
            return f.length() to { FileInputStream(f) }
        }
        // content:// (MediaStore / SAF)
        val cacheKey = entry.uri.toString()
        if (lengthCache.size >= 512) lengthCache.clear()
        val length = lengthCache.getOrPut(cacheKey) {
            val afd = context.contentResolver.openAssetFileDescriptor(entry.uri, "r")
                ?: return@getOrPut -1L
            val len = afd.length
            afd.close()
            len
        }
        val open: () -> InputStream = {
            val fd = context.contentResolver.openAssetFileDescriptor(entry.uri, "r")
                ?: throw IOException("open failed: ${entry.uri}")
            fd.createInputStream()
        }
        return (if (length > 0) length else entry.sizeHint) to open
    }

    private fun skipFully(stream: InputStream, n: Long) {
        var left = n
        while (left > 0) {
            val skipped = stream.skip(left)
            if (skipped > 0) {
                left -= skipped
            } else {
                if (stream.read() < 0) break
                left--
            }
        }
    }

    private class LimitedInputStream(
        private val inner: InputStream,
        private var remaining: Long,
    ) : InputStream() {
        override fun read(): Int {
            if (remaining <= 0) return -1
            val b = inner.read()
            if (b >= 0) remaining--
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (remaining <= 0) return -1
            val toRead = minOf(len.toLong(), remaining).toInt()
            val n = inner.read(b, off, toRead)
            if (n > 0) remaining -= n
            return n
        }

        override fun available(): Int = minOf(inner.available().toLong(), remaining).toInt()

        override fun close() {
            runCatching { inner.close() }
        }
    }
}
