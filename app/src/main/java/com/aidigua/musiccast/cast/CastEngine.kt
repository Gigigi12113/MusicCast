package com.aidigua.musiccast.cast

import android.content.Context
import android.util.Log
import com.yinnho.upnpcast.CastOptions
import com.yinnho.upnpcast.DLNACast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap

/**
 * 投送引擎门面：**优先 UPnPCast，失败自动回退内置 DLNA 实现**。
 *
 * 选择理由（写进 README 也一致）：
 *  - UPnPCast v1.3.0（JitPack `com.github.yinnho:UPnPCast:v1.3.0`，MIT）
 *    是 Cling 停更后的活跃替代品，Kotlin 协程 API，自带 DIDL-Lite 构造与
 *    SSDP 发现，省掉一堆样板；
 *  - 但它是第三方 AAR，个别 ROM 上多播被禁 / 1900 被占用时可能抛错，
 *    因此每当它抛异常，就切换到本工程内置的 [InternalDlnaController]——
 *    纯 JDK 的 SSDP + SOAP 实现，行为与 Cling 的 AVTransport 等价。
 *
 * 两个引擎对外暴露同一套 [CastDevice] / [TransportInfo] 模型。
 */
class CastEngine(private val context: Context) {

    companion object {
        private const val TAG = "CastEngine"

        const val ENGINE_UPNPCAST = "UPnPCast v1.3.0"
        const val ENGINE_INTERNAL = "内置 DLNA（Cling 兼容）"

        /** location 缓存上限，防内存增长 */
        private const val LOCATION_CACHE_MAX = 64

        /** 规范化地址为 host 小写（去端口/协议），作为 location 缓存键 */
        fun hostOf(address: String): String = runCatching { java.net.URL(address).host }
            .getOrDefault(address.substringBeforeLast(':'))
            .lowercase()
    }

    private val internal = InternalDlnaController(context)

    /** 当前生效的引擎名，供 UI 显示。 */
    @Volatile
    var activeEngine: String = ENGINE_UPNPCAST
        private set

    @Volatile
    private var upnpCastReady = false

    /** 当前连接的设备（内部引擎需要 location 才能发 SOAP）。 */
    @Volatile
    private var currentInternalLocation: String? = null

    @Volatile
    private var currentDevice: CastDevice? = null

    /** Cache known device locations to avoid redundant SSDP searches（有上限） */
    private val knownLocations = ConcurrentHashMap<String, String>()

    /** 有上限地写入 location 缓存 */
    private fun cacheLocation(address: String, location: String) {
        if (knownLocations.size >= LOCATION_CACHE_MAX) {
            knownLocations.keys.firstOrNull()?.let { knownLocations.remove(it) }
        }
        knownLocations[hostOf(address)] = location
    }

    enum class Transport { IDLE, PLAYING, PAUSED, STOPPED, TRANSITIONING, UNKNOWN }

    data class CastDevice(
        val id: String,
        val name: String,
        val address: String,
        val isTV: Boolean,
        /** 内部引擎用：设备描述 URL */
        val location: String? = null,
    ) {
        /**
         * 是否音箱类设备：按设备友好名关键词粗判。
         *
         * DLNA 协议本身不区分电视/音箱（都叫 MediaRenderer，isTV 实际
         * 含义是"渲染器"），只能靠名字识别。用于自动选设备时的
         * 优先级：音箱 > 电视/其他渲染器。
         */
        val isSpeaker: Boolean
            get() = SPEAKER_HINTS.any { name.lowercase().contains(it) }

        private companion object {
            /** 音箱关键词（小写比较）：覆盖常见音箱/功放/智能音箱的友好名。 */
            val SPEAKER_HINTS = listOf(
                "音箱", "音响", "喇叭", "功放", "扩音",
                "小爱", "小度", "天猫精灵",
                "soundbar", "sound bar", "sound", "speaker", "audio",
                "sonos", "homepod", "echo", "nest audio",
                "wiim", "linkplay", "heos", "bluesound",
                "denon", "marantz", "yamaha", "onkyo",
            )
        }
    }

    data class TransportInfo(
        val transport: Transport = Transport.UNKNOWN,
        val positionMs: Long = 0L,
        val durationMs: Long = 0L,
        val volume: Int = -1,
        val rawState: String = "",
    )

    // ────────────────────────── 生命周期 ──────────────────────────

    fun init() {
        if (upnpCastReady) return
        runCatching {
            DLNACast.init(context.applicationContext)
            upnpCastReady = true
            activeEngine = ENGINE_UPNPCAST
            Log.i(TAG, "UPnPCast 初始化成功")
        }.onFailure {
            Log.w(TAG, "UPnPCast 初始化失败，回退内置实现", it)
            upnpCastReady = false
            activeEngine = ENGINE_INTERNAL
        }
    }

    fun cleanup() {
        runCatching { DLNACast.cleanup() }
        upnpCastReady = false
        knownLocations.clear()
        currentInternalLocation = null
        currentDevice = null
        runCatching { internal.release() }
    }

    // ────────────────────────── 发现 ──────────────────────────

    /**
     * 发现设备：**两个引擎都跑，结果合并去重**。
     *
     * 踩过的坑（用户实测「有音响+电视时只搜到电视」）：
     *  UPnPCast 的 SSDP 只发 MediaRenderer 一个 ST，且它的 `processedLocations`
     *  缓存会让同一 location 的后续回应被直接丢弃 —— 一台设备先到，另一台就
     *  再也进不来。所以不能「UPnPCast 有结果就不跑内置实现」。
     *
     * 现在：
     *  1. 两个引擎都搜，内置实现用 4 个 ST + 收包/解析分离，能拿到完整列表；
     *  2. 合并去重时 **优先保留带 location 的内置条目**（它的 SOAP 通道是
     *     我们自己实现的，可控、可调试），UPnPCast 条目只在没有同名设备时补入；
     *  3. UI 上引擎名显示为「UPnPCast + 内置 DLNA」，如实反映实际来源。
     */
    suspend fun discover(
        timeoutMs: Long,
        onDeviceFound: (CastDevice) -> Unit = {},
    ): List<CastDevice> = withContext(Dispatchers.IO) {
        fun keyOf(name: String, address: String) =
            name.trim().lowercase() + "@" + address.trim()

        // 同一 location 只转换一次（回调 + 合并循环复用，避免重复解析 URL 与重复写缓存）
        val converted = mutableMapOf<String, CastDevice>()
        fun internalToDevice(info: InternalDlnaController.RendererInfo): CastDevice =
            converted.getOrPut(info.location) {
                val host = runCatching { java.net.URL(info.location).host }.getOrDefault("")
                // Cache location for fast prepare/cast fallback
                cacheLocation(host, info.location)
                CastDevice(
                    id = info.location,
                    name = info.name,
                    address = host,
                    isTV = info.isRenderer,
                    location = info.location,
                )
            }

        // 1) 两个引擎并行搜索
        var upnpOk = false
        coroutineScope {
            val internalDeferred = async {
                runCatching {
                    internal.discover(timeoutMs) { info -> onDeviceFound(internalToDevice(info)) }
                }.onFailure { Log.w(TAG, "内置 DLNA 搜索失败", it) }
                    .getOrDefault(emptyList())
            }
            val upnpDeferred = async {
                if (upnpCastReady ||
                    runCatching { DLNACast.init(context); upnpCastReady = true }.isSuccess
                ) {
                    runCatching { DLNACast.search(timeoutMs) }
                        .onFailure { Log.w(TAG, "UPnPCast 搜索失败", it) }
                        .getOrNull()
                } else null
            }
            val internalList = internalDeferred.await()
            val upnpList = upnpDeferred.await()
            upnpOk = !upnpList.isNullOrEmpty()

            // 2) 合并去重：内置条目优先（带 location），UPnPCast 只补缺
            val merged = LinkedHashMap<String, CastDevice>()
            for (info in internalList) {
                val d = internalToDevice(info)
                merged[keyOf(d.name, d.address)] = d
            }
            if (upnpList != null) {
                for (d in upnpList) {
                    val k = keyOf(d.name, d.address)
                    if (!merged.containsKey(k)) {
                        merged[k] = CastDevice(
                            id = d.id,
                            name = d.name,
                            address = d.address,
                            isTV = d.isTV,
                            location = null,
                        )
                    }
                }
            }

            activeEngine = when {
                upnpOk && internalList.isNotEmpty() -> "$ENGINE_UPNPCAST + 内置 DLNA"
                upnpOk -> ENGINE_UPNPCAST
                internalList.isNotEmpty() -> ENGINE_INTERNAL
                else -> activeEngine
            }
            Log.i(
                TAG,
                "发现结果：内置 ${internalList.size} 台，UPnPCast ${if (upnpOk) "有" else "无"}，" +
                    "合并后 ${merged.size} 台",
            )
            merged.values.toList()
        }
    }

    // ────────────────────────── 投送 ──────────────────────────

    /**
     * @param mediaUrl 手机 HTTP 服务器给出的公网可访问 URL
     * @return true 表示设备已接受并开始播放
     */
    suspend fun cast(device: CastDevice, mediaUrl: String, title: String): Boolean =
        withContext(Dispatchers.IO) {
            // 优先 UPnPCast（若可用且设备来自它的搜索结果）
            if (upnpCastReady && device.location == null) {
                val ok = runCatching {
                    DLNACast.castToDevice(
                        device = DLNACast.Device(
                            id = device.id,
                            name = device.name,
                            address = device.address,
                            isTV = device.isTV,
                        ),
                        url = mediaUrl,
                        title = title,
                        options = CastOptions(
                            mimeType = "audio/mpeg",
                            upnpClass = "object.item.audioItem.musicTrack",
                        ),
                    )
                }.getOrElse {
                    Log.w(TAG, "UPnPCast 投送异常", it)
                    false
                }
                if (ok) {
                    currentDevice = device
                    activeEngine = ENGINE_UPNPCAST
                    return@withContext true
                }
                Log.w(TAG, "UPnPCast 投送失败，改用内置实现")
            }

            // 内置实现：优先使用缓存的 location，避免二次 SSDP 搜索
            val location = device.location
                ?: knownLocations[hostOf(device.address)]
                ?: internal.discover(2500)
                    .firstOrNull { it.name == device.name || it.location == device.location }
                    ?.location
            if (location == null) {
                Log.w(TAG, "设备 ${device.name} 无可用 controlURL")
                return@withContext false
            }
            // Cache for future use
            cacheLocation(device.address, location)
            currentInternalLocation = location
            currentDevice = device.copy(location = location)
            activeEngine = ENGINE_INTERNAL

            internal.setAvTransportUri(location, mediaUrl, title) &&
                internal.play(location)
        }

    // ────────────────────────── 控制 ──────────────────────────

    /**
     * 选中设备后立刻调用：为内置引擎建立 SOAP 控制通道（解析描述文档、
     * 缓存 controlURL），使音量的读写不需要先跑完整 SSDP 搜索。
     */
    suspend fun prepareDevice(device: CastDevice) = withContext(Dispatchers.IO) {
        val location = device.location
            ?: knownLocations[hostOf(device.address)]
            ?: internal.discover(2500)
                .firstOrNull { it.name == device.name || it.location == device.location }
                ?.location
        if (location != null) {
            cacheLocation(device.address, location)
            currentInternalLocation = location
            currentDevice = device.copy(location = location)
            runCatching { internal.registerDevice(location) }
            Log.i(TAG, "已预连接设备 ${device.name}（location=$location）")
        }
        Unit
    }

    /**
     * 只读设备当前音量（0-100），读不到返回 null。
     * 与 [getTransportInfo] 分开：音量轮询不需要拉全部状态。
     */
    suspend fun getVolume(): Int? = withContext(Dispatchers.IO) {
        if (usingUpnpCast()) {
            return@withContext runCatching { DLNACast.getVolume() }.getOrNull()?.first
        }
        val loc = currentInternalLocation ?: return@withContext null
        internal.getVolume(loc)
    }

    suspend fun play(): Boolean = withContext(Dispatchers.IO) {
        if (usingUpnpCast()) return@withContext runCatching { DLNACast.play() }.getOrDefault(false)
        val loc = currentInternalLocation ?: return@withContext false
        internal.play(loc)
    }

    suspend fun pause(): Boolean = withContext(Dispatchers.IO) {
        if (usingUpnpCast()) return@withContext runCatching { DLNACast.pause() }.getOrDefault(false)
        val loc = currentInternalLocation ?: return@withContext false
        internal.pause(loc)
    }

    suspend fun stop(): Boolean = withContext(Dispatchers.IO) {
        if (usingUpnpCast()) return@withContext runCatching { DLNACast.stop() }.getOrDefault(false)
        val loc = currentInternalLocation ?: return@withContext false
        internal.stop(loc)
    }

    suspend fun seek(positionMs: Long): Boolean = withContext(Dispatchers.IO) {
        if (usingUpnpCast()) {
            return@withContext runCatching { DLNACast.seek(positionMs) }.getOrDefault(false)
        }
        val loc = currentInternalLocation ?: return@withContext false
        internal.seek(loc, positionMs)
    }

    suspend fun setVolume(volume: Int): Boolean = withContext(Dispatchers.IO) {
        if (usingUpnpCast()) {
            return@withContext runCatching { DLNACast.setVolume(volume) }.getOrDefault(false)
        }
        val loc = currentInternalLocation ?: return@withContext false
        internal.setVolume(loc, volume)
    }

    /**
     * 统一把不同引擎的原始状态映射为内部 [Transport] 枚举。
     */
    private fun mapUpnpState(state: DLNACast.PlaybackState?): Transport = when (state) {
        DLNACast.PlaybackState.PLAYING -> Transport.PLAYING
        DLNACast.PlaybackState.PAUSED -> Transport.PAUSED
        DLNACast.PlaybackState.STOPPED -> Transport.STOPPED
        DLNACast.PlaybackState.BUFFERING -> Transport.TRANSITIONING
        DLNACast.PlaybackState.IDLE -> Transport.IDLE
        else -> Transport.UNKNOWN
    }

    private fun mapInternalState(state: String?): Transport = when (state) {
        "PLAYING" -> Transport.PLAYING
        "PAUSED_PLAYBACK" -> Transport.PAUSED
        "STOPPED" -> Transport.STOPPED
        "TRANSITIONING" -> Transport.TRANSITIONING
        "NO_MEDIA_PRESENT" -> Transport.IDLE
        else -> Transport.UNKNOWN
    }

    suspend fun getTransportInfo(): TransportInfo = withContext(Dispatchers.IO) {
        if (usingUpnpCast()) {
            val state = runCatching { DLNACast.getPlaybackState() }.getOrNull()
            val progress = runCatching { DLNACast.getProgress() }.getOrNull()
            val vol = runCatching { DLNACast.getVolume() }.getOrNull()?.first ?: -1
            return@withContext TransportInfo(
                transport = mapUpnpState(state),
                positionMs = progress?.first ?: 0L,
                durationMs = progress?.second ?: 0L,
                volume = vol,
                rawState = state?.name ?: "",
            )
        }
        val loc = currentInternalLocation ?: return@withContext TransportInfo()
        // 三个 SOAP 并行：轮询高频路径，串行会放大延迟
        val stDeferred = async { internal.getTransportState(loc) }
        val posDeferred = async { internal.getPosition(loc) }
        val volDeferred = async { internal.getVolume(loc) }
        val st = stDeferred.await()
        val pos = posDeferred.await()
        val vol = volDeferred.await() ?: -1
        TransportInfo(
            transport = mapInternalState(st?.state),
            positionMs = pos?.first ?: 0L,
            durationMs = pos?.second ?: 0L,
            volume = vol,
            rawState = st?.state ?: "",
        )
    }

    private fun usingUpnpCast(): Boolean = activeEngine == ENGINE_UPNPCAST && upnpCastReady
}
