package com.aidigua.musiccast.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase

/**
 * 一首本地 MP3 曲目。
 *
 * 只支持 MP3：扫描时就按 `audio/mpeg` 过滤，其他音频格式不会进库。
 */
@Entity(tableName = "tracks")
data class TrackEntity(
    @PrimaryKey val uri: String,
    val title: String,
    val artist: String?,
    val album: String?,
    @ColumnInfo(name = "duration_ms") val durationMs: Long,
    @ColumnInfo(name = "size_bytes") val sizeBytes: Long,
    @ColumnInfo(name = "mtime") val mtime: Long, // 文件修改时间（毫秒），用于增量扫描
    @ColumnInfo(name = "display_path") val displayPath: String,
    @ColumnInfo(name = "added_at") val addedAt: Long,
    @ColumnInfo(name = "folder") val folder: String = "/", // 所在文件夹路径
    /** 嵌入式专辑封面的本地缓存文件 URI（content:// 或 file://） */
    val albumArtUri: String? = null,
)

/** 播放历史 / 最近投送记录。 */
@Entity(tableName = "history")
data class HistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    @ColumnInfo(name = "device_name") val deviceName: String,
    @ColumnInfo(name = "track_uri") val trackUri: String,
    @ColumnInfo(name = "played_at") val playedAt: Long,
    val success: Boolean,
)

@Dao
interface TrackDao {
    @Query("SELECT * FROM tracks ORDER BY title COLLATE NOCASE ASC")
    suspend fun all(): List<TrackEntity>

    @Query("SELECT COUNT(*) FROM tracks")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(tracks: List<TrackEntity>)

    @Query("DELETE FROM tracks")
    suspend fun clear()

    @Query("DELETE FROM tracks WHERE uri = :uri")
    suspend fun delete(uri: String)
}

@Dao
interface HistoryDao {
    @Query("SELECT * FROM history ORDER BY played_at DESC LIMIT :limit")
    suspend fun recent(limit: Int = 50): List<HistoryEntity>

    @Insert
    suspend fun insert(item: HistoryEntity)

    @Query("DELETE FROM history")
    suspend fun clear()
}

@Database(
    entities = [TrackEntity::class, HistoryEntity::class],
    version = 4,
    exportSchema = false,
)
abstract class MusicDatabase : RoomDatabase() {
    abstract fun trackDao(): TrackDao
    abstract fun historyDao(): HistoryDao

    companion object {
        const val NAME = "musiccast.db"
    }
}
