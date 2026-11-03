package com.aidigua.musiccast.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "musiccast_settings")

/** 用户设置：全部走 DataStore(Preferences)。 */
data class AppSettings(
    val lastDeviceId: String? = null,
    val lastDeviceName: String? = null,
    val serverPort: Int = 8099,
    val autoStartServer: Boolean = true,
    val scanFolderUri: String? = null,
    val scanFolderLabel: String = "Music",
    val lastScanAt: Long = 0L,
    val volume: Int = 40,
    val discoveryTimeoutMs: Long = 4000L,
    /** 播放模式：IN_ORDER / SHUFFLE（持久化，下次启动沿用） */
    val playMode: String = "IN_ORDER",
    /** 全部播完是否重头开始 */
    val repeatAll: Boolean = true,
    /** 是否开启自动连播 */
    val autoNext: Boolean = true,
    /** 上次投送设备的地址，用于重连时校验（设备可能换 IP） */
    val lastDeviceAddress: String? = null,
    /** 上次投送设备的 DLNA 描述文档 URL（location），优先用于快速重连，比 IP 更稳定 */
    val lastDeviceLocation: String? = null,
)

class SettingsRepository(private val context: Context) {

    private object Keys {
        val LAST_DEVICE_ID = stringPreferencesKey("last_device_id")
        val LAST_DEVICE_NAME = stringPreferencesKey("last_device_name")
        val SERVER_PORT = intPreferencesKey("server_port")
        val AUTO_START = booleanPreferencesKey("auto_start_server")
        val SCAN_FOLDER_URI = stringPreferencesKey("scan_folder_uri")
        val SCAN_FOLDER_LABEL = stringPreferencesKey("scan_folder_label")
        val LAST_SCAN_AT = longPreferencesKey("last_scan_at")
        val VOLUME = intPreferencesKey("volume")
        val DISCOVERY_TIMEOUT = longPreferencesKey("discovery_timeout_ms")
        val PLAY_MODE = stringPreferencesKey("play_mode")
        val REPEAT_ALL = booleanPreferencesKey("repeat_all")
        val AUTO_NEXT = booleanPreferencesKey("auto_next")
        val LAST_DEVICE_ADDR = stringPreferencesKey("last_device_address")
        val LAST_DEVICE_LOCATION = stringPreferencesKey("last_device_location")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            lastDeviceId = p[Keys.LAST_DEVICE_ID],
            lastDeviceName = p[Keys.LAST_DEVICE_NAME],
            serverPort = p[Keys.SERVER_PORT] ?: 8099,
            autoStartServer = p[Keys.AUTO_START] ?: true,
            scanFolderUri = p[Keys.SCAN_FOLDER_URI],
            scanFolderLabel = p[Keys.SCAN_FOLDER_LABEL] ?: "Music",
            lastScanAt = p[Keys.LAST_SCAN_AT] ?: 0L,
            volume = p[Keys.VOLUME] ?: 40,
            discoveryTimeoutMs = p[Keys.DISCOVERY_TIMEOUT] ?: 4000L,
            playMode = p[Keys.PLAY_MODE] ?: "IN_ORDER",
            repeatAll = p[Keys.REPEAT_ALL] ?: true,
            autoNext = p[Keys.AUTO_NEXT] ?: true,
            lastDeviceAddress = p[Keys.LAST_DEVICE_ADDR],
            lastDeviceLocation = p[Keys.LAST_DEVICE_LOCATION],
        )
    }

    suspend fun setLastDevice(id: String, name: String, address: String? = null, location: String? = null) =
        context.dataStore.edit {
            it[Keys.LAST_DEVICE_ID] = id
            it[Keys.LAST_DEVICE_NAME] = name
            if (address != null) it[Keys.LAST_DEVICE_ADDR] = address
            if (location != null) it[Keys.LAST_DEVICE_LOCATION] = location
        }

    suspend fun setPlayMode(mode: String) = context.dataStore.edit { it[Keys.PLAY_MODE] = mode }

    suspend fun setRepeatAll(enabled: Boolean) =
        context.dataStore.edit { it[Keys.REPEAT_ALL] = enabled }

    suspend fun setAutoNext(enabled: Boolean) =
        context.dataStore.edit { it[Keys.AUTO_NEXT] = enabled }

    suspend fun setServerPort(port: Int) = context.dataStore.edit { it[Keys.SERVER_PORT] = port }

    suspend fun setAutoStart(enabled: Boolean) = context.dataStore.edit { it[Keys.AUTO_START] = enabled }

    suspend fun setScanFolder(uri: String, label: String) = context.dataStore.edit {
        it[Keys.SCAN_FOLDER_URI] = uri
        it[Keys.SCAN_FOLDER_LABEL] = label
    }

    suspend fun setLastScanAt(at: Long) = context.dataStore.edit { it[Keys.LAST_SCAN_AT] = at }

    suspend fun setVolume(v: Int) = context.dataStore.edit { it[Keys.VOLUME] = v.coerceIn(0, 100) }

    suspend fun setDiscoveryTimeout(ms: Long) = context.dataStore.edit {
        it[Keys.DISCOVERY_TIMEOUT] = ms.coerceIn(1000L, 15000L)
    }
}
