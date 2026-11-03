package com.aidigua.musiccast.vm

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import com.aidigua.musiccast.cast.CastEngine
import com.aidigua.musiccast.data.AppSettings
import com.aidigua.musiccast.data.HistoryEntity
import com.aidigua.musiccast.data.MusicDatabase
import com.aidigua.musiccast.data.SettingsRepository
import com.aidigua.musiccast.data.TrackEntity
import com.aidigua.musiccast.media.LanAddress
import com.aidigua.musiccast.media.MediaHttpServer
import com.aidigua.musiccast.media.Mp3Library
import com.aidigua.musiccast.service.CastKeepAliveService
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll

/** 界面状态（单一来源，Compose 直接订阅）。 */
data class UiState(
    val tracks: List<TrackEntity> = emptyList(),
    val devices: List<CastEngine.CastDevice> = emptyList(),
    val selectedDevice: CastEngine.CastDevice? = null,
    val nowPlaying: TrackEntity? = null,
    val transport: CastEngine.Transport = CastEngine.Transport.UNKNOWN,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val volume: Int = 40,
    val scanning: Boolean = false,
    val discovering: Boolean = false,
    val busy: Boolean = false,
    val serverRunning: Boolean = false,
    /** 真实监听地址（IP + 实际端口，端口被占时会自动分配） */
    val serverUrl: String = "",
    /** 真实监听端口（与 serverUrl 一致；未运行时为 0） */
    val serverActualPort: Int = 0,
    val engineName: String = CastEngine.ENGINE_UPNPCAST,
    val settings: AppSettings = AppSettings(),
    val history: List<HistoryEntity> = emptyList(),
    val message: String? = null,
    val query: String = "",
    /** 播放倒计时剩余毫秒（跟随投送端进度，归零自动切下一首） */
    val countdownMs: Long = 0L,
    /** 是否开启自动连播 */
    val autoNext: Boolean = true,
    /** 播放模式：顺序 / 随机 */
    val playMode: PlayMode = PlayMode.IN_ORDER,
    /** 全部播完后是否从头再来（循环整个曲库） */
    val repeatAll: Boolean = true,
    /** 本次会话是否已按“播放起点的墙钟”校准过一次，避免每次轮询都重设 */
    val timeSynced: Boolean = false,
    /** 上次投送的设备名（下次启动时用于默认选中） */
    val lastDeviceName: String? = null,
    /** 文件夹 -> 曲目 Map（用于文件夹播放标签页） */
    val folderMap: Map<String, List<TrackEntity>> = emptyMap(),
    /** 当前选中的文件夹 URI（文件夹播放模式） */
    val lastFolderUri: String? = null,
    /** 是否有文件夹播放列表（已选过文件夹） */
    val hasFolderPlaylist: Boolean = false,
    /** 当前播放列表类型：ALL_SONGS 或 FOLDER_PLAY */
    val playlistType: PlaylistType = PlaylistType.ALL_SONGS,
)

enum class PlaylistType { ALL_SONGS, FOLDER_PLAY }

enum class PlayMode { IN_ORDER, SHUFFLE }

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private companion object {
        const val TAG = "MainViewModel"
        /** 自动发现周期（毫秒）：周期重搜，等价 Cling RegistryListener 的被动设备维护 */
        private const val AUTO_REDISCOVER_MS = 10000L
    }

    private val db = Room.databaseBuilder(app, MusicDatabase::class.java, MusicDatabase.NAME)
        .fallbackToDestructiveMigration()
        .build()

    private val settingsRepo = SettingsRepository(app)
    private val library = Mp3Library(app)
    private val engine = CastEngine(app)

    private var server: MediaHttpServer? = null
    private var currentToken: String? = null
    private var progressJob: Job? = null
    private var volumeJob: Job? = null
    /** 已预连接（prepareDevice）的设备 id：周期发现时避免重复 SOAP 注册 */
    private var lastPreparedDeviceId: String? = null

    /**
     * 防音量反馈环：用户按 +/- 或快捷键后，设备端应用新值有延迟
     * （典型 0.5~2s），期间轮询读回的还是旧值，会把 UI 拉回去
     * 造成"按了没反应、数字来回跳"。设一个时间戳，在这个窗口内
     * 轮询读到的设备音量不回写 UI。
     */
    @Volatile
    private var volumeHoldUntil: Long = 0L

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            // Parallel init: engine + server + cached data + settings
            val initDeferred = async { withContext(Dispatchers.IO) { engine.init() } }
            val serverDeferred = async { startServer() }
            val tracksDeferred = async { db.trackDao().all() }
            val historyDeferred = async { db.historyDao().recent(30) }

            // Await all and batch state update
            val tracks = tracksDeferred.await()
            val history = historyDeferred.await()
            val folderMap = tracks.groupBy { it.folder }
            awaitAll(initDeferred, serverDeferred)
            _state.update { it.copy(
                engineName = engine.activeEngine,
                tracks = tracks,
                history = history,
                folderMap = folderMap,
            ) }

            // Settings flow (autoStartServer handled inside)
            settingsRepo.settings.collect { s ->
                _state.update { it.copy(
                    settings = s,
                    volume = s.volume,
                    playMode = if (s.playMode == "SHUFFLE") PlayMode.SHUFFLE else PlayMode.IN_ORDER,
                    repeatAll = s.repeatAll,
                    autoNext = s.autoNext,
                    lastDeviceName = s.lastDeviceName,
                ) }
                if (s.autoStartServer && server == null) startServer()
            }
        }

        // 快速重连：有缓存 location 时直接 prepareDevice（SOAP 通道），不等待 SSDP。
        // 与自动发现并行——重连成功也不再跳过设备列表刷新（用户需要看到全部设备）。
        viewModelScope.launch {
            val settings = withTimeoutOrNull(3000) { settingsRepo.settings.first() }
            val location = settings?.lastDeviceLocation
            if (location != null && location.isNotBlank()) {
                Log.i(TAG, "尝试快速重连缓存设备 location=$location")
                runCatching {
                    engine.prepareDevice(CastEngine.CastDevice(
                        id = location,
                        name = settings.lastDeviceName ?: "Cached Device",
                        address = runCatching { java.net.URL(location).host }.getOrDefault(""),
                        isTV = false,
                        location = location,
                    ))
                }.onSuccess {
                    Log.i(TAG, "快速重连成功")
                    lastPreparedDeviceId = location
                    _state.update { it.copy(message = "已恢复连接：${settings.lastDeviceName ?: "设备"}") }
                    syncVolumeOnce()
                    startVolumePolling()
                }.onFailure { e ->
                    Log.w(TAG, "快速重连失败", e)
                }
            }
        }

        // 自动发现（移植 DLNAPlayer 的 Cling 架构方法）：
        // 引擎就绪后立即触发一次 SSDP 搜索（对应 Cling 构造器里的 controlPoint.search()），
        // 之后周期性重搜维持设备列表在线（对应 Cling RegistryListener 的被动维护——
        // 我们的引擎没有被动注册表，用定时主动搜索等价实现，列表整体替换天然下线设备，
        // 同时维持设备连接，避免久置断连）。
        viewModelScope.launch {
            settingsRepo.settings.first()
            delay(400)
            discoverDevices()
            while (isActive) {
                delay(AUTO_REDISCOVER_MS)
                discoverDevices(quiet = true)
            }
        }
    }

    // ───────────────────────── HTTP 服务器 ─────────────────────────

    fun startServer(port: Int? = null) {
        viewModelScope.launch {
            val desired = port ?: _state.value.settings.serverPort
            if (server?.isAlive == true) {
                if (server?.port == desired) return@launch
                runCatching { server?.stop() }
                server = null
            }
            val s = withContext(Dispatchers.IO) {
                runCatching {
                    MediaHttpServer(getApplication(), desired).also { it.start(4000, true) }
                }.onFailure { Log.w(TAG, "启动 HTTP 服务器失败（端口 $desired）", it) }
                    .getOrNull()
                    ?: runCatching {
                        // 端口被占则让系统挑一个
                        MediaHttpServer(getApplication(), 0).also { it.start(4000, true) }
                    }.getOrNull()
            }

            if (s == null) {
                _state.update { it.copy(serverRunning = false, message = "HTTP 服务器启动失败") }
                return@launch
            }
            server = s
            val ip = LanAddress.wifiIpv4(getApplication()) ?: "未联网"
            _state.update {
                it.copy(
                    serverRunning = true,
                    serverUrl = "http://$ip:${s.port}/",
                    serverActualPort = s.port,
                    message = if (ip == "未联网") "未连接局域网，电视将无法访问音频" else null,
                )
            }
        }
    }

    fun stopServer() {
        runCatching { server?.stop() }
        server = null
        _state.update { it.copy(serverRunning = false, serverUrl = "", serverActualPort = 0) }
    }

    fun restartServer(port: Int) {
        viewModelScope.launch { settingsRepo.setServerPort(port) }
        stopServer()
        startServer(port)
    }

    private fun mediaUrlFor(track: TrackEntity): String? {
        val srv = server ?: return null
        val ip = LanAddress.wifiIpv4(getApplication()) ?: return null
        currentToken?.let { srv.unregister(it) }
        val token = srv.register(Uri.parse(track.uri), track.title, track.sizeBytes)
        currentToken = token
        return srv.urlFor(token, ip)
    }

    // ───────────────────────── 扫描 ─────────────────────────

    /**
     * 只扫描用户设置的目录及其子目录（不再全盘扫描 MediaStore）。
     * 增量扫描：按 uri + mtime + size 判断文件是否变化，只 upsert 新增/变更条目。
     * 若未设置扫描目录，则清空曲库并提示用户先选择。
     */
    fun scanLibrary(treeUri: Uri? = null) {
        viewModelScope.launch {
            _state.update { it.copy(scanning = true, message = null) }
            val tree = treeUri
                ?: _state.value.settings.scanFolderUri?.let(Uri::parse)
            if (tree == null) {
                db.trackDao().clear()
                settingsRepo.setLastScanAt(0L)
                _state.update {
                    it.copy(
                        tracks = emptyList(),
                        folderMap = emptyMap(),
                        scanning = false,
                        message = "未设置扫描目录，请在“设置”里选择 MP3 文件夹",
                    )
                }
                return@launch
            }

            // 1. 先读现有曲库（按 uri 索引），用于增量比对
            val existing = db.trackDao().all().associateBy { it.uri }

            // 2. 扫描 SAF 目录树 - 两阶段增量扫描（未变更文件直接复用 DB 元数据）
            var folderMap = emptyMap<String, List<TrackEntity>>()
            val found = ArrayList<TrackEntity>()
            runCatching {
                val result = library.scanTree(tree, existing)
                found += result.allTracks
                folderMap = result.folderMap
            }.onFailure { Log.w(TAG, "SAF 扫描失败", it) }

            // 3. 去重（同一文件可能既在 MediaStore 又在 SAF 里）
            val dedup = LinkedHashMap<String, TrackEntity>()
            for (t in found) {
                val key = "${t.title}|${t.sizeBytes}|${t.durationMs}"
                if (!dedup.containsKey(key)) dedup[key] = t
            }
            val scanned = dedup.values.toList()

            // 4. 增量比对：分新增、变更、未变三类
            val toUpsert = mutableListOf<TrackEntity>()
            val scannedUris = mutableSetOf<String>()
            for (track in scanned) {
                scannedUris.add(track.uri)
                val old = existing[track.uri]
                val changed = old == null
                    || old.mtime != track.mtime
                    || old.sizeBytes != track.sizeBytes
                if (changed) {
                    // 保留 addedAt（首次入库时间），其余字段更新
                    toUpsert += track.copy(addedAt = old?.addedAt ?: track.addedAt)
                }
            }

            // 5. 删除已不在扫描目录里的曲目（可选：只删当前目录下的，保留其它来源）
            val toDelete = existing.keys.filter { !scannedUris.contains(it) }
            if (toDelete.isNotEmpty()) {
                for (uri in toDelete) db.trackDao().delete(uri)
            }

            // 6. 只 upsert 变更项
            if (toUpsert.isNotEmpty()) db.trackDao().upsertAll(toUpsert)

            // 7. 读回完整曲库（含未变项）供 UI 显示
            val list = db.trackDao().all().sortedBy { it.title.lowercase() }
            
            // 重建 folderMap（基于数据库中的 folder 字段）
            val dbFolderMap = list.groupBy { it.folder }
            
            settingsRepo.setLastScanAt(System.currentTimeMillis())
            _state.update {
                it.copy(
                    tracks = list,
                    folderMap = dbFolderMap,
                    scanning = false,
                    message = if (list.isEmpty()) "没有找到 MP3 文件" else "已载入 ${list.size} 首 MP3 (增量更新 ${toUpsert.size} 首)",
                )
            }
        }
    }

    fun onScanFolderPicked(uri: Uri) {
        val ctx = getApplication<Application>()
        runCatching {
            ctx.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        val label = uri.lastPathSegment?.substringAfterLast(':') ?: "已选目录"
        viewModelScope.launch {
            settingsRepo.setScanFolder(uri.toString(), label)
            scanLibrary(uri)
        }
    }

    // ───────────────────────── 发现设备 ─────────────────────────

    fun discoverDevices(quiet: Boolean = false) {
        if (_state.value.discovering) return
        val userPicked = _state.value.selectedDevice != null
        viewModelScope.launch {
            if (!quiet) _state.update { it.copy(discovering = true, message = "正在搜索局域网内的播放设备…") }
            val timeout = _state.value.settings.discoveryTimeoutMs
            val seen = LinkedHashMap<String, CastEngine.CastDevice>()

            val list = withContext(Dispatchers.IO) {
                runCatching {
                    engine.discover(timeout) { d ->
                        seen[d.id] = d
                        // Only update devices list, not selection (selection happens after discovery)
                        _state.update { st ->
                            st.copy(devices = seen.values.toList())
                        }
                    }
                }.getOrDefault(emptyList())
            }

            val remembered = _state.value.settings
            val rememberedMatch = list.firstOrNull { it.id == remembered.lastDeviceId }
                ?: list.firstOrNull { remembered.lastDeviceName != null && it.name == remembered.lastDeviceName }
                ?: list.firstOrNull { it.address == remembered.lastDeviceAddress }
            val current = _state.value.selectedDevice

            val selected = when {
                userPicked -> current?.takeIf { sel -> list.any { it.id == sel.id } }
                    ?: rememberedMatch
                    ?: list.firstOrNull { it.isSpeaker }
                    ?: list.firstOrNull()
                else -> current?.takeIf { sel -> sel.isSpeaker && list.any { it.id == sel.id } }
                    ?: rememberedMatch?.takeIf { it.isSpeaker }
                    ?: list.firstOrNull { it.isSpeaker }
                    ?: rememberedMatch
                    ?: list.firstOrNull()
            }
            _state.update { st ->
                st.copy(
                    devices = list,
                    selectedDevice = selected,
                    discovering = if (quiet) st.discovering else false,
                    engineName = engine.activeEngine,
                    message = if (quiet) st.message else if (list.isEmpty())
                        "没找到可投送的设备（确认电视与手机在同一 Wi-Fi，且已开启 DLNA）"
                    else
                        "找到 ${list.size} 台设备 · 引擎：${engine.activeEngine}",
                )
            }
            // 只在选中变化时预连接，避免周期发现重复跑 SOAP 注册
            if (selected != null && lastPreparedDeviceId != selected.id) {
                lastPreparedDeviceId = selected.id
                engine.prepareDevice(selected)
                syncVolumeOnce()
                startVolumePolling()
            }
        }
    }

    fun selectDevice(device: CastEngine.CastDevice) {
        _state.update {
            it.copy(selectedDevice = device, lastDeviceName = device.name)
        }
        viewModelScope.launch {
            settingsRepo.setLastDevice(device.id, device.name, device.address, device.location)
        }
        // 选中即连：预建立 SOAP 通道，然后立刻读一次设备音量同步到 UI，
        // 并启动常驻音量轮询（用户在设备遥控器上改音量，App 也要跟着变）。
        viewModelScope.launch {
            lastPreparedDeviceId = device.id
            engine.prepareDevice(device)
            syncVolumeOnce()
            startVolumePolling()
        }
    }

    /**
     * 向设备读一次当前音量并写进 UI。
     * [fromUser] = false 表示这是轮询回读：处于防反馈窗口时跳过。
     */
    private suspend fun syncVolumeOnce(fromUser: Boolean = false) {
        if (!fromUser && System.currentTimeMillis() < volumeHoldUntil) return
        val vol = runCatching { engine.getVolume() }.getOrNull()
        if (vol != null && vol in 0..100) {
            _state.update { it.copy(volume = vol) }
        }
    }

    /**
     * 常驻音量轮询：每 2s 读一次设备音量。
     *
     * 与播放进度轮询分开：没有在播时也要跑（用户可能只想调音量）。
     * 换设备时 [startVolumePolling] 会重启并立即同步一次。
     */
    private fun startVolumePolling() {
        volumeJob?.cancel()
        volumeJob = viewModelScope.launch {
            while (true) {
                if (_state.value.selectedDevice == null) {
                    delay(5000) // Longer delay when no device selected
                    continue
                }
                runCatching { syncVolumeOnce() }
                // 播放中 2s 高频；空闲 5s 低频（省电省流量，远程改音量仍能跟上）
                val playing = _state.value.transport == CastEngine.Transport.PLAYING
                delay(if (playing) 2000 else 5000)
            }
        }
    }

    /** 手动「同步音量」入口（设备页按钮）：忽略防反馈窗口强制读一次。 */
    fun syncVolumeNow() {
        viewModelScope.launch {
            syncVolumeOnce(fromUser = true)
            _state.update {
                it.copy(message = "已同步设备音量：${_state.value.volume}")
            }
        }
    }

    // ───────────────────────── 投送与播放控制 ─────────────────────────

    fun cast(track: TrackEntity) {
        castInternal(track, announce = true)
    }

    /**
     * 投送一首歌。
     *
     * @param announce 是否在 UI 上提示（自动切歌时用 false，避免刷屏）
     */
    private fun castInternal(track: TrackEntity, announce: Boolean = false) {
        val device = _state.value.selectedDevice
        if (device == null) {
            _state.update { it.copy(message = "请先选择一台播放设备") }
            return
        }
        viewModelScope.launch {
            _state.update {
                if (announce) it.copy(busy = true, message = "正在投送《" + track.title + "》…")
                else it.copy(busy = true)
            }
            val url = mediaUrlFor(track)
            if (url == null) {
                _state.update {
                    it.copy(busy = false, message = "无法生成媒体地址：请检查 Wi-Fi 与 HTTP 服务器")
                }
                return@launch
            }
            val ok = runCatching { engine.cast(device, url, track.title) }.getOrDefault(false)
            db.historyDao().insert(
                HistoryEntity(
                    title = track.title,
                    deviceName = device.name,
                    trackUri = track.uri,
                    playedAt = System.currentTimeMillis(),
                    success = ok,
                )
            )
            if (ok) {
                // 关键：投送成功即刻启动本地时钟，切歌不再依赖设备回报。
                // armClock 会递增 generation，旧轮询循环随即退出。
                armClock(track)
                // 前台保活：让 HTTP 服务器在后台不被冻结，电视才不会断流
                CastKeepAliveService.start(getApplication(), track.title, device.name)
                // 投送成功后设备连接已真正建立，同步一次设备音量并确保轮询在跑
                syncVolumeOnce()
                startVolumePolling()
            }
            _state.update {
                it.copy(
                    busy = false,
                    nowPlaying = if (ok) track else it.nowPlaying,
                    transport = if (ok) CastEngine.Transport.PLAYING else it.transport,
                    durationMs = if (ok && track.durationMs > 0) track.durationMs else it.durationMs,
                    positionMs = if (ok) 0L else it.positionMs,
                    countdownMs = if (ok) track.durationMs else it.countdownMs,
                    timeSynced = if (ok) track.durationMs > 0 else it.timeSynced,
                    engineName = engine.activeEngine,
                    message = when {
                        ok -> if (announce) "已投送到 " + device.name else it.message
                        else -> "投送失败：设备拒绝或网络不通"
                    },
                )
            }
            db.historyDao().recent(30).let { h -> _state.update { s -> s.copy(history = h) } }
            if (ok) startProgressPolling()
        }
    }

    fun togglePlayPause() {
        viewModelScope.launch {
            val playing = _state.value.transport == CastEngine.Transport.PLAYING
            val ok = runCatching { if (playing) engine.pause() else engine.play() }
                .getOrDefault(false)
            if (ok) {
                if (playing) pauseClock() else resumeClock()
                _state.update {
                    it.copy(
                        transport = if (playing) CastEngine.Transport.PAUSED
                        else CastEngine.Transport.PLAYING,
                    )
                }
            } else {
                _state.update { it.copy(message = "设备未响应") }
            }
        }
    }

    fun stop() {
        viewModelScope.launch {
            val ok = runCatching { engine.stop() }.getOrDefault(false)
            if (ok) clock = Clock()
            _state.update {
                it.copy(
                    transport = if (ok) CastEngine.Transport.STOPPED else it.transport,
                    positionMs = if (ok) 0L else it.positionMs,
                    countdownMs = if (ok) 0L else it.countdownMs,
                    message = if (ok) "已停止" else "设备未响应",
                )
            }
        }
    }

    fun seekTo(ms: Long) {
        viewModelScope.launch {
            val ok = runCatching { engine.seek(ms) }.getOrDefault(false)
            if (ok) {
                // 拖动进度后必须重设基准，否则倒计时会按旧起点继续跑
                seekClock(ms)
                _state.update {
                    it.copy(
                        positionMs = ms,
                        countdownMs = (clock.durationMs - ms).coerceAtLeast(0L),
                    )
                }
            }
        }
    }

    /** 音量步进 ±2（UI 用加减按钮，不用横向滑条）。 */
    fun volumeUp(step: Int = 2) = setVolume(_state.value.volume + step)

    fun volumeDown(step: Int = 2) = setVolume(_state.value.volume - step)

    /**
     * 设定音量。三个来源都走这里：
     *  - +/- 按钮（步进）；
     *  - 快捷键 10 / 20 / 30（直接设固定值）；
     *  - 进度轮询的设备回读（绕过这里，见 [syncVolumeOnce]）。
     *
     * 用户操作后开启 2.5s 防反馈窗口：设备把新音量真正生效有延迟，
     * 窗口内轮询读到的旧值不回写 UI，避免数字"跳回去"。
     */
    fun setVolume(v: Int) {
        val vol = v.coerceIn(0, 100)
        _state.update { it.copy(volume = vol) }
        volumeHoldUntil = System.currentTimeMillis() + 2500L
        viewModelScope.launch {
            settingsRepo.setVolume(vol)
            val ok = runCatching { engine.setVolume(vol) }.getOrDefault(false)
            if (!ok) {
                _state.update { it.copy(message = "设备未接受音量设置") }
            } else {
                // 设置成功后立刻回读校准一次（趁防反馈窗口仍在，只更新数据不动 UI 逻辑）
                runCatching { syncVolumeOnce(fromUser = true) }
            }
        }
    }

    fun setAutoStartServer(enabled: Boolean) {
        viewModelScope.launch { settingsRepo.setAutoStart(enabled) }
        if (enabled) startServer() else stopServer()
    }

    fun setDiscoveryTimeout(ms: Long) {
        viewModelScope.launch { settingsRepo.setDiscoveryTimeout(ms) }
    }

    fun setQuery(q: String) {
        _state.update { it.copy(query = q) }
        // 搜索词变了，随机隐藏列表作废，下次切歌按新筛选结果重新洗牌
        shuffleSignature = emptyList()
    }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    fun showMessage(msg: String) = _state.update { it.copy(message = msg) }

    fun clearHistory() {
        viewModelScope.launch {
            db.historyDao().clear()
            _state.update { it.copy(history = emptyList()) }
        }
    }

    // ════════════════════ 播放控制核心（切歌靠本地墙钟） ════════════════════
    //
    // 为什么不能等设备回报"播完了"：
    //   - 多数 DLNA 渲染器播完只报 STOPPED/NO_MEDIA_PRESENT，不报"下一首"；
    //   - 有些设备 GetPositionInfo 返回的 TrackDuration 是 00:00:00；
    //   - 还有些设备进度条根本不动（缓存式播放），靠它判断永远不触发。
    //
    // foobar2000 的 UPnP 插件和酷狗音乐的做法一致：**投送时记录本地起点，
    // 用歌曲时长在本地跑一个倒计时**，到点就投下一首，设备状态只用来修正基准。
    // 这里照此实现。

    /** 本地播放时钟：墙钟基准，用于计算进度与剩余时间。 */
    private data class Clock(
        val startedAt: Long = 0L,
        val pausedAt: Long = 0L,
        val offsetMs: Long = 0L,
        val durationMs: Long = 0L,
        val playing: Boolean = false,
    ) {
        fun position(now: Long): Long = when {
            !playing -> offsetMs
            startedAt <= 0L -> offsetMs
            else -> offsetMs + (now - startedAt)
        }

        fun remaining(now: Long): Long =
            if (durationMs <= 0L) 0L else (durationMs - position(now)).coerceAtLeast(0L)
    }

    @Volatile
    private var clock: Clock = Clock()

    /** 每次投送递增；轮询循环用它判断自己是否已过期，避免旧歌的校准污染新歌。 */
    @Volatile
    private var generation: Long = 0L

    /** 记录一次投送的时间基准。 */
    private fun armClock(track: TrackEntity) {
        val dur = track.durationMs.takeIf { it > 0 } ?: 0L
        generation += 1
        clock = Clock(
            startedAt = System.currentTimeMillis(),
            offsetMs = 0L,
            durationMs = dur,
            playing = true,
        )
    }

    private fun pauseClock() {
        val c = clock
        if (!c.playing) return
        clock = c.copy(
            offsetMs = c.position(System.currentTimeMillis()),
            startedAt = 0L,
            playing = false,
        )
    }

    private fun resumeClock() {
        val c = clock
        if (c.playing) return
        clock = c.copy(startedAt = System.currentTimeMillis(), playing = true)
    }

    private fun seekClock(ms: Long) {
        val c = clock
        clock = c.copy(
            offsetMs = ms.coerceAtLeast(0L),
            startedAt = if (c.playing) System.currentTimeMillis() else 0L,
        )
    }

    /**
     * 主循环：每 500ms 刷新 UI 进度，并检查是否该切歌。
     *
     * 切歌靠**本地墙钟倒计时**（和 foobar2000 的 UPnP 插件、酷狗音乐一致），
     * 不依赖设备回报"播完了"——多数渲染器播完只报 STOPPED，有的干脆
     * 连 TrackDuration 都返回 00:00:00，靠它判断永远切不了歌。
     *
     * 两个必须防的坑（都实际踩到过）：
     *  1. **旧歌的校准污染新歌**：切歌瞬间设备还在报上一首的 position，
     *     若此时用设备进度重设基准，新歌会一开播就显示 03:20。用
     *     [generation] 让旧循环失效，并且新歌头 4 秒内不接受设备进度校准。
     *  2. **连跳两首**：切歌后 clock 归零，若循环没及时退出会在同一轮
     *     再次判定"播完"。用 advancing 标志 + generation 双重保护。
     */
    private fun startProgressPolling() {
        progressJob?.cancel()
        val myGen = generation
        progressJob = viewModelScope.launch {
            var lastSyncAt = 0L
            var advancing = false
            var lastUiUpdate = 0L

            while (true) {
                // 已被新的投送取代 -> 本循环退出，交给新循环
                if (myGen != generation) return@launch

                val now = System.currentTimeMillis()
                val c = clock
                val pos = c.position(now)
                val remain = c.remaining(now)

                // ── 1) 刷新 UI（进度条平滑，播放时 500ms，暂停/无曲时降频）
                val updateInterval = if (c.playing && c.durationMs > 0) 500L else 2000L
                if (now - lastUiUpdate >= updateInterval) {
                    lastUiUpdate = now
                    val transport = when {
                        !c.playing -> CastEngine.Transport.PAUSED
                        c.durationMs > 0 && remain <= 0L -> CastEngine.Transport.STOPPED
                        else -> CastEngine.Transport.PLAYING
                    }
                    _state.update { s ->
                        s.copy(
                            positionMs = if (c.durationMs > 0) pos.coerceAtMost(c.durationMs) else pos,
                            durationMs = if (c.durationMs > 0) c.durationMs else s.durationMs,
                            countdownMs = remain,
                            timeSynced = c.durationMs > 0,
                            transport = transport,
                        )
                    }
                }

                // ── 2) 每 3 秒与设备校准（只修正基准，绝不作为切歌依据）
                val sinceArm = now - c.startedAt
                if (c.playing && now - lastSyncAt > 3000) {
                    lastSyncAt = now
                    // 新歌头 4 秒不采信设备进度：设备此时多半还在报上一首
                    if (!(c.startedAt > 0 && sinceArm < 4000)) {
                        val info = runCatching { engine.getTransportInfo() }.getOrNull()
                        if (info != null && myGen == generation) {
                            _state.update { it.copy(engineName = engine.activeEngine) }

                            // 顺带同步音量（防反馈窗口内会被跳过）
                            if (info.volume in 0..100) {
                                val now2 = System.currentTimeMillis()
                                if (now2 >= volumeHoldUntil) {
                                    _state.update { it.copy(volume = info.volume) }
                                }
                            }

                            if (clock.durationMs <= 0L && info.durationMs > 0) {
                                clock = clock.copy(durationMs = info.durationMs)
                            }
                            val devPos = info.positionMs
                            if (devPos > 0 &&
                                kotlin.math.abs(devPos - clock.position(now)) > 5000
                            ) {
                                clock = clock.copy(offsetMs = devPos, startedAt = now)
                            }
                            if (info.transport == CastEngine.Transport.PAUSED && clock.playing) {
                                pauseClock()
                            }
                        }
                    }
                }

                // ── 3) 切歌判定：本地倒计时归零
                if (!advancing && _state.value.autoNext) {
                    val c2 = clock
                    if (c2.durationMs > 0 && c2.playing &&
                        c2.position(System.currentTimeMillis()) >= c2.durationMs
                    ) {
                        advancing = true
                        if (_state.value.tracks.isNotEmpty()) {
                            playNext(auto = true)
                        } else {
                            clock = Clock()
                        }
                        return@launch
                    }
                }

                delay(250) // Fine-grained loop, UI update throttled above
            }
        }
    }

    /**
     * 当前播放队列。
     *
     * **搜索框有内容时，队列就是筛选结果** —— 这样「搜歌手，然后连播」
     * 只会在他/她的歌里循环，和酷狗/网易云的行为一致。
     *
     * 根据 playlistType、搜索词、选中的文件夹过滤。
     */
    private fun currentQueue(st: UiState): List<TrackEntity> {
        val q = st.query.trim()
        var base: List<TrackEntity> = when (st.playlistType) {
            PlaylistType.FOLDER_PLAY -> {
                st.lastFolderUri?.let { folderUri ->
                    st.folderMap[folderUri] ?: emptyList()
                } ?: emptyList()
            }
            PlaylistType.ALL_SONGS -> st.tracks
        }
        if (q.isEmpty()) return base
        return base.filter { t ->
            t.title.contains(q, true) ||
                (t.artist?.contains(q, true) ?: false) ||
                (t.album?.contains(q, true) ?: false)
        }
    }

    /** 选择文件夹：切换到文件夹播放模式，显示该文件夹下的歌曲列表（不自动播放）。 */
    fun playFolder(folderUri: String) {
        val st = _state.value
        val folderTracks = st.folderMap[folderUri] ?: emptyList()
        if (folderTracks.isEmpty()) {
            _state.update { it.copy(message = "该文件夹没有 MP3 文件") }
            return
        }
        _state.update { it.copy(
            playlistType = PlaylistType.FOLDER_PLAY,
            lastFolderUri = folderUri,
            hasFolderPlaylist = true,
            message = "已切换到文件夹：${folderTracks.size} 首歌曲",
        ) }
    }

    /** 退出文件夹播放模式，回到全部歌曲模式（保留文件夹选择，下次点击文件夹播放直接恢复）。 */
    fun exitFolderPlay() {
        _state.update { it.copy(
            playlistType = PlaylistType.ALL_SONGS,
            // 保留 lastFolderUri 和 hasFolderPlaylist
        ) }
    }

    /** 切换到文件夹播放模式（不弹框），使用上次选的文件夹。 */
    fun switchToFolderPlay() {
        val st = _state.value
        if (st.hasFolderPlaylist && st.lastFolderUri != null) {
            _state.update { it.copy(
                playlistType = PlaylistType.FOLDER_PLAY,
            ) }
        }
    }

    /** 播放全部：遵循当前搜索筛选后的列表。
     *  若当前歌曲在列表中，播放下一首；否则播放第一首（顺序）或随机一首。
     */
    fun playAll() {
        val st = _state.value
        val list = currentQueue(st)
        if (list.isEmpty()) {
            _state.update { it.copy(message = "当前列表为空，无法播放") }
            return
        }
        val curUri = st.nowPlaying?.uri
        if (curUri != null && list.any { it.uri == curUri }) {
            playNext(auto = false)
            return
        }
        val target = if (st.playMode == PlayMode.SHUFFLE) list.random() else list.first()
        cast(target)
    }

    /** 播下一首。随机模式下按隐藏洗牌列表的游标取下一首。 */
    fun playNext(auto: Boolean = false) {
        val st = _state.value
        val list = currentQueue(st)
        if (list.isEmpty()) {
            _state.update { it.copy(message = "当前列表为空，无法切歌") }
            return
        }
        val next = when {
            st.playMode == PlayMode.IN_ORDER -> {
                val idx = list.indexOfFirst { it.uri == st.nowPlaying?.uri }
                when {
                    idx < 0 -> list.first()
                    idx < list.lastIndex -> list[idx + 1]
                    // 末尾：开启循环就重头开始，否则停在最后一首
                    st.repeatAll -> {
                        if (auto) _state.update {
                            it.copy(message = if (st.playlistType == PlaylistType.FOLDER_PLAY) "文件夹播完了，从头再来" else "曲库播完了，从头再来")
                        }
                        list.first()
                    }
                    else -> {
                        _state.update { it.copy(message = if (st.playlistType == PlaylistType.FOLDER_PLAY) "已播完该文件夹" else "已播完整个曲库") }
                        return
                    }
                }
            }
            // 随机模式：遵循隐藏洗牌列表的下一首（文件夹/全库共用一套游标逻辑）
            st.playMode == PlayMode.SHUFFLE -> pickShuffleNext(st) ?: return
            else -> list.first()
        }
        if (auto) {
            _state.update { it.copy(message = "自动播放：" + next.title) }
        }
        castInternal(next)
    }

    /**
     * 随机模式隐藏播放列表：洗牌后的完整队列 + 当前位置游标。
     *
     * 上一首/下一首都遵循该隐藏列表（与主流音乐 App 的洗牌行为一致）：
     * 下一首 = 游标后移（到尾循环），上一首 = 游标前移（到头循环）。
     * 队列内容变化（搜索词 / 文件夹 / 播放列表类型切换）时自动重洗，
     * 重洗后尽量定位到当前正在播的那首，从它继续。
     */
    private var shuffleOrder: List<TrackEntity> = emptyList()
    private var shuffleIndex: Int = -1
    private var shuffleSignature: List<String> = emptyList()

    /** 确保 [shuffleOrder] 与当前队列一致，返回隐藏列表。 */
    private fun ensureShuffleOrder(st: UiState): List<TrackEntity> {
        val queue = currentQueue(st)
        val sig = queue.map { it.uri }
        if (shuffleSignature != sig || shuffleOrder.size != queue.size) {
            shuffleOrder = queue.shuffled()
            shuffleSignature = sig
            shuffleIndex = st.nowPlaying?.uri?.let { uri ->
                shuffleOrder.indexOfFirst { it.uri == uri }.coerceAtLeast(0)
            } ?: 0
        }
        return shuffleOrder
    }

    /** 随机模式下一首：隐藏列表后移一位（到尾循环回到第一首）。 */
    private fun pickShuffleNext(st: UiState): TrackEntity? {
        val order = ensureShuffleOrder(st)
        if (order.isEmpty()) return null
        shuffleIndex = (shuffleIndex + 1) % order.size
        return order[shuffleIndex]
    }

    /** 随机模式上一首：隐藏列表前移一位（到头循环回到最后一首）。 */
    private fun pickShufflePrev(st: UiState): TrackEntity? {
        val order = ensureShuffleOrder(st)
        if (order.isEmpty()) return null
        shuffleIndex = (shuffleIndex - 1 + order.size) % order.size
        return order[shuffleIndex]
    }

    fun playPrevious() {
        val st = _state.value
        val list = currentQueue(st)
        if (list.isEmpty()) return
        val prev = when (st.playMode) {
            PlayMode.IN_ORDER -> {
                val idx = list.indexOfFirst { it.uri == st.nowPlaying?.uri }
                if (idx <= 0) list.last() else list[idx - 1]
            }
            // 随机模式：上一首 = 隐藏洗牌列表的前一项（与下一首对称，到头循环）
            PlayMode.SHUFFLE -> pickShufflePrev(st) ?: list.first()
        }
        castInternal(prev)
    }

    /** 切换播放模式（顺序 <-> 随机）。 */
    fun setPlayMode(mode: PlayMode) {
        _state.update {
            it.copy(
                playMode = mode,
                message = if (mode == PlayMode.SHUFFLE) "随机播放" else "顺序播放",
            )
        }
        viewModelScope.launch {
            settingsRepo.setPlayMode(if (mode == PlayMode.SHUFFLE) "SHUFFLE" else "IN_ORDER")
        }
        shuffleOrder = emptyList()
        shuffleIndex = -1
        shuffleSignature = emptyList()
    }

    /** 全部播完是否重头开始。 */
    fun setRepeatAll(enabled: Boolean) {
        _state.update { it.copy(repeatAll = enabled) }
        viewModelScope.launch { settingsRepo.setRepeatAll(enabled) }
    }

    fun setAutoNext(enabled: Boolean) {
        _state.update { it.copy(autoNext = enabled) }
        viewModelScope.launch { settingsRepo.setAutoNext(enabled) }
    }

    /** 手动「同步一次时间」：立刻向设备取一次进度并重设本地基准。 */
    fun syncTimeNow() {
        viewModelScope.launch {
            val info = runCatching { engine.getTransportInfo() }.getOrNull()
            val c = clock
            val now = System.currentTimeMillis()
            if (info == null || (info.positionMs <= 0 && info.durationMs <= 0)) {
                // 设备不报进度时，用本地时钟 + 曲目时长兜底，仍然可以切歌
                val track = _state.value.nowPlaying
                val dur = if (c.durationMs > 0) c.durationMs else (track?.durationMs ?: 0L)
                if (dur > 0) {
                    clock = Clock(
                        startedAt = now,
                        offsetMs = 0L,
                        durationMs = dur,
                        playing = c.playing,
                    )
                    _state.update {
                        it.copy(
                            countdownMs = dur,
                            durationMs = dur,
                            timeSynced = true,
                            message = "设备不回报进度，已按歌曲时长重建倒计时",
                        )
                    }
                } else {
                    _state.update { it.copy(message = "无法从设备读取播放进度") }
                }
                return@launch
            }
            val dur = if (info.durationMs > 0) info.durationMs else c.durationMs
            clock = Clock(
                startedAt = now,
                offsetMs = info.positionMs,
                durationMs = dur,
                playing = true,
            )
            val remain = (dur - info.positionMs).coerceAtLeast(0L)
            _state.update {
                it.copy(
                    positionMs = info.positionMs,
                    durationMs = dur,
                    countdownMs = remain,
                    timeSynced = true,
                    message = "已同步设备时间，剩余 " + (remain / 1000) + " 秒",
                )
            }
        }
    }

    override fun onCleared() {
        CastKeepAliveService.stop(getApplication())
        progressJob?.cancel()
        volumeJob?.cancel()
        engine.cleanup()
        runCatching { server?.stop() }
        super.onCleared()
    }
}
