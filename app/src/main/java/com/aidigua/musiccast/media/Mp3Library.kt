package com.aidigua.musiccast.media

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import com.aidigua.musiccast.data.TrackEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * 仅扫描 MP3（audio/mpeg）。
 *
 * 两条来源：
 *  1. MediaStore —— 系统媒体库里的 MP3；
 *  2. SAF 目录树 —— 用户手选的文件夹（可跨分区，如 SD 卡）。
 *
 * 元数据优先取 MediaStore 的 TITLE/ARTIST/ALBUM/DURATION 列，
 * SAF 来源则用 MediaMetadataRetriever 从文件头读 ID3。
 */

/** 扫描结果：全部曲目 + 按文件夹分组的 Map */
data class FolderResult(
    val allTracks: List<TrackEntity>,
    val folderMap: Map<String, List<TrackEntity>>, // folder path -> tracks
)

class Mp3Library(private val context: Context) {

    private companion object {
        const val TAG = "Mp3Library"
        const val MP3_MIME = "audio/mpeg"
        /** 并行度：CPU 核心数 * 2，避免过多线程争抢 MediaMetadataRetriever 内部锁 */
        private const val PARALLELISM = 8
    }

    /** 用于并行提取元数据的固定线程池（IO 调度器之上再跑一层，避免阻塞协程池） */
    private val metadataExecutor = ThreadPoolExecutor(
        PARALLELISM, PARALLELISM,
        60L, TimeUnit.SECONDS,
        LinkedBlockingQueue(),
        Executors.defaultThreadFactory()
    ).also { it.allowCoreThreadTimeOut(true) }

    private val metadataScope = CoroutineScope(SupervisorJob() + metadataExecutor.asCoroutineDispatcher())

    /**
     * 两阶段增量扫描（快）：
     * 阶段1：每个目录一次 cursor 批量拿到 name/mime/mtime/size（DocumentsContract 直查，无逐文件 IPC）；
     * 阶段2：只对【新增/变更】文件解析 ID3，未变更文件直接复用数据库已有元数据。
     */
    suspend fun scanTree(treeUri: Uri, existing: Map<String, TrackEntity> = emptyMap()): FolderResult = withContext(Dispatchers.IO) {
        // 阶段1：快速目录遍历
        val files = listSafFiles(treeUri)
        if (files.isEmpty()) return@withContext FolderResult(emptyList(), emptyMap())

        // 阶段2：增量解析 ID3（只处理新增/变更文件）
        val batchSize = PARALLELISM * 4
        val results = mutableListOf<TrackEntity>()
        val folderMap = mutableMapOf<String, MutableList<TrackEntity>>()
        for (i in 0 until files.size step batchSize) {
            val batch = files.subList(i, (i + batchSize).coerceAtMost(files.size))
            val deeds = batch.map { f ->
                metadataScope.async {
                    val old = existing[f.uri.toString()]
                    if (old != null && f.mtime > 0 && f.size >= 0
                        && old.mtime == f.mtime && old.sizeBytes == f.size) {
                        old // 未变更：零解析，直接复用 DB 元数据
                    } else {
                        extractTrackFromSaf(f)
                    }
                }
            }
            for (track in deeds.awaitAll().filterNotNull()) {
                results += track
                folderMap.getOrPut(track.folder) { mutableListOf() }.add(track)
            }
        }
        FolderResult(results, folderMap.mapValues { it.value.toList() })
    }

    /** 阶段1：DocumentsContract 直查，每个目录一次 cursor，批量拿到名称/MIME/修改时间/大小 */
    private fun listSafFiles(treeUri: Uri): List<SafFile> {
        val out = mutableListOf<SafFile>()
        val treeDocId = DocumentsContract.getTreeDocumentId(treeUri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeDocId)
        walkSaf(treeUri, childrenUri, "/", out, 0)
        return out
    }

    private fun walkSaf(
        treeUri: Uri,
        childrenUri: Uri,
        folderPath: String,
        out: MutableList<SafFile>,
        depth: Int,
    ) {
        if (depth > 12) return
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_SIZE,
        )
        val cursor = runCatching {
            context.contentResolver.query(childrenUri, projection, null, null, null)
        }.getOrNull() ?: return
        cursor.use { c ->
            val idCol = c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameCol = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeCol = c.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val mtimeCol = c.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            val sizeCol = c.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
            while (c.moveToNext()) {
                val docId = if (idCol >= 0) c.getString(idCol) else null
                if (docId == null) continue
                val name = if (nameCol >= 0) c.getString(nameCol) else null
                if (name == null) continue
                val mime = if (mimeCol >= 0) c.getString(mimeCol) else null
                val mtime = if (mtimeCol >= 0) c.getLong(mtimeCol) else 0L
                val size = if (sizeCol >= 0) c.getLong(sizeCol) else -1L
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    val childFolder = if (folderPath == "/") "/$name" else "$folderPath/$name"
                    val subChildren = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
                    walkSaf(treeUri, subChildren, childFolder, out, depth + 1)
                    continue
                }
                val nameLower = name.lowercase()
                if (!nameLower.endsWith(".mp3")) continue
                if (mime != null && mime != MP3_MIME && !mime.lowercase().startsWith("audio")) continue
                val fileUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                out += SafFile(fileUri, name, mtime, size, folderPath)
            }
        }
    }

    /** 阶段1结果：SAF 文件摘要（含文件夹路径） */
    private data class SafFile(
        val uri: Uri,
        val name: String,
        val mtime: Long,
        val size: Long,
        val folderPath: String,
    )

    /** 阶段2：从 SAF 文件解析 TrackEntity（仅新增/变更文件走到这里） */
    private fun extractTrackFromSaf(file: SafFile): TrackEntity? {
        val meta = readId3(file.uri)
        return try {
            TrackEntity(
                uri = file.uri.toString(),
                title = meta.title ?: file.name.substringBeforeLast('.'),
                artist = meta.artist,
                album = meta.album,
                durationMs = meta.durationMs,
                sizeBytes = if (file.size >= 0) file.size else 0L,
                mtime = file.mtime,
                displayPath = file.name,
                addedAt = System.currentTimeMillis(),
                folder = file.folderPath,
                albumArtUri = null,
            )
        } catch (e: Exception) {
            Log.w(TAG, "构建 TrackEntity 失败: ${file.uri}", e)
            null
        }
    }

    private data class Id3(
        val title: String?,
        val artist: String?,
        val album: String?,
        val durationMs: Long,
    )

    /** 复用单个 MediaMetadataRetriever 实例读取多个字段，减少对象创建开销 */
    private fun readId3(uri: Uri): Id3 {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(context, uri)
            Id3(
                title = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE),
                artist = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST),
                album = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                durationMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L,
            )
        } catch (e: Exception) {
            Log.w(TAG, "读取 ID3 失败: $uri", e)
            Id3(null, null, null, 0)
        } finally {
            runCatching { r.release() }
        }
    }

    /** 从文件路径提取文件夹路径（父目录） */
    private fun extractFolderFromPath(path: String): String {
        // 处理以 / 结尾的目录路径
        val cleanPath = if (path.endsWith("/")) path.substring(0, path.length - 1) else path
        val idx = cleanPath.lastIndexOf('/')
        return if (idx > 0) cleanPath.substring(0, idx) else if (idx == 0) "/" else "/"
    }
}
