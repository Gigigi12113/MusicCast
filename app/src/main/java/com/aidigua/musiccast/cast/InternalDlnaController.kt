package com.aidigua.musiccast.cast

import android.content.Context
import android.net.wifi.WifiManager
import com.aidigua.musiccast.media.LanAddress
import android.util.Log
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.net.DatagramPacket
import java.net.MulticastSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 内置的极简 DLNA/UPnP 控制实现（零第三方依赖）。
 *
 * 职责与 Cling 的 `AVTransport` + `RenderingControl` 服务等价：
 *   - SSDP M-SEARCH 发现 MediaRenderer；
 *   - 拉取 device description，定位 controlURL；
 *   - SOAP 调用 SetAVTransportURI / Play / Pause / Stop / Seek /
 *     GetTransportInfo / GetPositionInfo / SetVolume / GetVolume。
 *
 * 为什么需要它：UPnPCast 是首选引擎，但它在部分 ROM（无多播、端口被占）
 * 或库版本不匹配时可能初始化失败。此时本类顶上，保证 App 仍能投送。
 */
internal class InternalDlnaController(private val context: Context) {

    companion object {
        private const val TAG = "InternalDlna"
        private const val SSDP_ADDR = "239.255.255.250"
        private const val SSDP_PORT = 1900
        private const val AVT = "urn:schemas-upnp-org:service:AVTransport:1"
        private const val RCS = "urn:schemas-upnp-org:service:RenderingControl:1"
        // Reuse parser factory to avoid allocation overhead
        private val parserFactory = XmlPullParserFactory.newInstance().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        }
        // 轮询高频路径：Regex 预编译，避免每次 SOAP 解析重建
        private val RE_CURRENT_VOLUME = Regex("<CurrentVolume>(\\d+)</CurrentVolume>")
        private val RE_TRANSPORT_STATE = Regex("<CurrentTransportState>(\\w+)</CurrentTransportState>")
        private val RE_TRANSPORT_STATUS = Regex("<CurrentTransportStatus>(\\w+)</CurrentTransportStatus>")
        private val RE_REL_TIME = Regex("<RelTime>([^<]+)</RelTime>")
        private val RE_TRACK_DURATION = Regex("<TrackDuration>([^<]+)</TrackDuration>")
        /** 设备描述缓存上限，防内存无限增长 */
        private const val KNOWN_CACHE_MAX = 64
    }

    private val wifiLock: WifiManager.MulticastLock? = runCatching {
        val wm = context.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as WifiManager
        wm.createMulticastLock("musiccast-dlna").apply { setReferenceCounted(false) }
    }.getOrNull()

    /** 已发现设备的描述缓存：location -> RendererInfo（有上限，防内存增长） */
    private val known = ConcurrentHashMap<String, RendererInfo>()

    /** 有上限地写入描述缓存 */
    private fun cacheKnown(location: String, info: RendererInfo) {
        if (known.size >= KNOWN_CACHE_MAX) known.keys.firstOrNull()?.let { known.remove(it) }
        known[location] = info
    }

    /** 清空描述缓存（引擎清理时调用） */
    fun release() {
        known.clear()
    }

    data class RendererInfo(
        val name: String,
        val location: String,
        val avTransportControl: String?,
        val renderingControlControl: String?,
        val isRenderer: Boolean,
    )

    data class TransportState(val state: String, val status: String)

    /**
     * 直接把一个已知 location 注册进控制缓存（不经过 SSDP 搜索）。
     *
     * 用途：用户在设备列表里点选一台设备时，立刻解析它的描述文档并缓存
     * controlURL，这样后续 GetVolume / SetVolume 等 SOAP 调用无需先跑一遍
     * 完整搜索（搜索要 4s+，还会占用多播 socket）。
     *
     * @return 描述文档解析成功（即可控）时为 true
     */
    suspend fun registerDevice(location: String): Boolean = withContext(Dispatchers.IO) {
        if (known.containsKey(location)) return@withContext true
        val info = fetchDescription(location) ?: return@withContext false
        if (info.avTransportControl == null && info.renderingControlControl == null) {
            return@withContext false
        }
        cacheKnown(location, info)
        Log.i(TAG, "已直接注册设备 ${info.name}（$location）")
        true
    }

    // ────────────────────────────── 发现 ──────────────────────────────

    /**
     * SSDP 发现。
     *
     * 三个关键点，缺一个就会出现「只能搜到一台设备」：
     *
     *  1. **多 ST**：只发 `MediaRenderer:1` 会漏掉大量设备，很多电视/音箱
     *     只回应 `ssdp:all` 或只声明 `AVTransport`。这里发 4 种 ST。
     *  2. **收包与解析分离**：以前在收包循环里同步 `fetchDescription()`，
     *     一台响应慢的设备会把后面所有设备的回应堵在 socket 缓冲区里，
     *     超时一到就只剩第一台。现在先收完所有回应，再并行解析。
     *  3. **判据看服务**：以描述文档里是否有 AVTransport 为准，而不是只看
     *     deviceType，避免漏掉只声明 MediaServer 的音箱。
     */
    suspend fun discover(
        timeoutMs: Long,
        onDeviceFound: (RendererInfo) -> Unit = {},
    ): List<RendererInfo> = withContext(Dispatchers.IO) {
        val locations = LinkedHashMap<String, String>()   // location -> ST 头
        val parsed = ConcurrentHashMap<String, RendererInfo>()
        runCatching { wifiLock?.acquire() }
        val parseJobs = mutableListOf<Deferred<Unit>>()
        var socket: MulticastSocket? = null
        try {
            // MulticastSocket：才能绑定 wlan 网卡，多网卡设备（VPN/以太网）避免 M-SEARCH 走错出口
            socket = MulticastSocket().apply {
                reuseAddress = true
                broadcast = true
                soTimeout = 300   // 短周期醒一次，好检查静默退出条件
            }
            val group = InetAddress.getByName(SSDP_ADDR)
            val target = InetSocketAddress(group, SSDP_PORT)
            val ni = NetworkInterface.getNetworkInterfaces()?.toList()
                ?.firstOrNull { it.isUp && it.name.startsWith("wlan") }
            if (ni != null) {
                runCatching { socket.networkInterface = ni }
            }

            val searchTargets = listOf(
                "urn:schemas-upnp-org:device:MediaRenderer:1",
                "urn:schemas-upnp-org:service:AVTransport:1",
                "upnp:rootdevice",
                "ssdp:all",
            )
            fun buildSearch(st: String) = buildString {
                append("M-SEARCH * HTTP/1.1\r\n")
                append("HOST: $SSDP_ADDR:$SSDP_PORT\r\n")
                append("MAN: \"ssdp:discover\"\r\n")
                append("MX: 1\r\n")
                append("ST: $st\r\n")
                append("\r\n")
            }.toByteArray()

            fun sendAll() {
                for (st in searchTargets) {
                    val msg = buildSearch(st)
                    runCatching { socket.send(DatagramPacket(msg, msg.size, target)) }
                }
            }

            sendAll()

            val buf = ByteArray(8192)
            val startTime = System.currentTimeMillis()
            val deadline = startTime + timeoutMs
            var lastResend = startTime
            var lastNewLocationAt = startTime
            while (isActive && System.currentTimeMillis() < deadline) {
                // 静默期提前退出：收到过设备且 1.5s 没有新面孔 → 收工。
                // 这是提速的核心：绝大多数网络 1~2s 内就搜干净了。
                if (locations.isNotEmpty() &&
                    System.currentTimeMillis() - lastNewLocationAt > 1500
                ) {
                    Log.i(TAG, "静默期退出：${locations.size} 个位置已收齐")
                    break
                }
                // 每 1s 补发一轮，覆盖丢包
                if (System.currentTimeMillis() - lastResend > 1000) {
                    sendAll()
                    lastResend = System.currentTimeMillis()
                }
                try {
                    val packet = DatagramPacket(buf, buf.size)
                    socket.receive(packet)
                    val text = String(packet.data, 0, packet.length, StandardCharsets.UTF_8)
                    val headers = parseHeaders(text)
                    val location = headers["location"] ?: continue
                    if (!locations.containsKey(location)) {
                        locations[location] = headers["st"] ?: ""
                        lastNewLocationAt = System.currentTimeMillis()
                        // 解析放并行协程：慢设备不堵收包，也不堵别的设备
                        parseJobs += async(Dispatchers.IO) {
                            val info = fetchDescription(location)
                            val st = headers["st"] ?: ""
                            val rendererBySt = st.contains("MediaRenderer", true) ||
                                st.contains("AVTransport", true)
                            when {
                                info != null && (info.avTransportControl != null || rendererBySt) -> {
                                    if (parsed.putIfAbsent(location, info) == null) {
                                        cacheKnown(location, info)
                                        onDeviceFound(info)
                                    }
                                }
                                info != null && info.isRenderer -> {
                                    if (parsed.putIfAbsent(location, info) == null) {
                                        cacheKnown(location, info)
                                        onDeviceFound(info)
                                    }
                                }
                                else -> {}
                            }
                        }
                    }
                } catch (_: SocketTimeoutException) {
                    // 继续等，直到总超时 / 静默退出
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "SSDP 收包阶段失败", e)
        } finally {
            runCatching { socket?.close() }
            runCatching { wifiLock?.release() }
        }

        // 等待描述解析收尾：收包循环已结束，慢设备在这里等齐，避免结果丢失
        runCatching { withTimeoutOrNull(2500) { parseJobs.awaitAll() } }

        // SSDP 收包结束后才启动 ARP 兜底（避免作用域问题）：
        //  - SSDP 一个都没搜到 → 兜底全量等它（反正没有更快的事可做）；
        //  - SSDP 有结果 → 最多陪它 800ms，超时就用已有结果。
        // probeKnownHosts 内部并行 + 500ms 连接超时，最多 ~1s 完成。
        val bySsdp = parsed.values.toList()
        val probed = if (bySsdp.isEmpty()) {
            runCatching { probeKnownHosts(900) }.getOrDefault(emptyList())
        } else {
            withTimeoutOrNull(800) {
                runCatching { probeKnownHosts(900) }.getOrDefault(emptyList())
            } ?: emptyList()
        }

        val merged = LinkedHashMap<String, RendererInfo>()
        for (p in bySsdp) merged[p.location] = p
        for (p in probed) {
            if (!merged.containsKey(p.location)) {
                merged[p.location] = p
                cacheKnown(p.location, p)
                onDeviceFound(p)
            }
        }
        Log.i(
            TAG,
            "SSDP 回应 ${locations.size} 个，主动探测补充 ${probed.size} 个，" +
                "可用渲染器 ${merged.size} 台",
        )
        merged.values.toList()
    }


    /**
     * 主动探测兜底：从 ARP 表取同网段主机，逐个尝试常见 UPnP 描述路径。
     *
     * 为什么需要：SSDP 是 UDP 多播，丢包、路由器 IGMP snooping、设备省电休眠
     * 都会让「一台在、另一台不在」。ARP 表里的主机是确实在线过的，补一遍
     * HTTP 探测能显著提高「同时有电视和音箱」时的发现率。
     */
    private suspend fun probeKnownHosts(timeoutMs: Long): List<RendererInfo> = coroutineScope {
        val self = LanAddress.wifiIpv4(context)
        val hosts = readArpHosts()
            .filter { it != self }
            .take(16)
        if (hosts.isEmpty()) return@coroutineScope emptyList()

        val paths = listOf(
            "/description.xml", "/dmr.xml", "/upnp/desc.xml",
            "/rootDesc.xml", "/DeviceDescription.xml", "/",
        )
        // 每台主机并行探测；单台主机内路径仍串行（先命中先停）
        // 整体受 timeoutMs 约束，避免 ARP 兜底拖住发现流程
        withTimeoutOrNull(timeoutMs) {
            hosts.map { host ->
                async(Dispatchers.IO) {
                    for (path in paths) {
                        val info = fetchDescription("http://$host:80$path", connectTimeoutMs = 500)
                            ?: continue
                        if (info.avTransportControl != null || info.isRenderer) {
                            return@async info
                        }
                    }
                    null
                }
            }.awaitAll().filterNotNull()
        } ?: emptyList()
    }

    /** 读 /proc/net/arp，返回 IPv4 列表。 */
    private fun readArpHosts(): List<String> = runCatching {
        val f = java.io.File("/proc/net/arp")
        if (!f.canRead()) return emptyList()
        f.readLines().drop(1).mapNotNull { line ->
            val cols = line.trim().split(Regex("\\s+"))
            if (cols.size < 4) return@mapNotNull null
            val ip = cols[0]
            val flags = cols[2]
            // 0x2 = 完整条目（有 MAC）
            if (flags == "0x2" && ip.count { it == '.' } == 3) ip else null
        }
    }.getOrDefault(emptyList())

    private fun parseHeaders(raw: String): Map<String, String> {
        val map = HashMap<String, String>()
        raw.split("\r\n", "\n").forEach { line ->
            val idx = line.indexOf(':')
            if (idx > 0) {
                map[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
            }
        }
        return map
    }

    private fun fetchDescription(
        location: String,
        connectTimeoutMs: Int = 4000,
    ): RendererInfo? = runCatching {
        val conn = (URL(location).openConnection() as HttpURLConnection).apply {
            connectTimeout = connectTimeoutMs
            readTimeout = 4000
            requestMethod = "GET"
            setRequestProperty("Connection", "close")
        }
        try {
            val xml = conn.inputStream.bufferedReader().use { it.readText() }
            parseDescriptionXml(location, xml)
        } finally {
            conn.disconnect()
        }
    }.getOrElse {
        Log.w(TAG, "解析设备描述失败: $location", it)
        null
    }

    /** 解析设备描述 XML：提取友好名与 AVTransport/RenderingControl 控制 URL。 */
    private fun parseDescriptionXml(location: String, xml: String): RendererInfo {
        val parser = parserFactory.newPullParser().apply { setInput(StringReader(xml)) }
        var friendlyName: String? = null
        var deviceType: String? = null
        var currentServiceType: String? = null
        var currentControlUrl: String? = null
        var avt: String? = null
        var rcs: String? = null
        var inService = false
        var event: Int = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    when (parser.name.lowercase()) {
                        "service" -> {
                            inService = true
                            currentServiceType = null
                            currentControlUrl = null
                        }
                        "friendlyname" -> friendlyName = parser.nextText().trim()
                        "devicetype" -> if (deviceType == null) deviceType = parser.nextText().trim()
                        "servicetype" -> if (inService) currentServiceType = parser.nextText().trim()
                        "controlurl" -> if (inService) currentControlUrl = parser.nextText().trim()
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name.equals("service", true) && inService) {
                        val type = currentServiceType
                        val ctrl = currentControlUrl
                        if (type != null && ctrl != null) {
                            val abs = absolutize(location, ctrl)
                            when {
                                type.startsWith(AVT) -> avt = abs
                                type.startsWith(RCS) -> rcs = abs
                            }
                        }
                        inService = false
                    }
                }
            }
            event = parser.next()
        }
        return RendererInfo(
            name = friendlyName ?: "DLNA Renderer",
            location = location,
            avTransportControl = avt,
            renderingControlControl = rcs,
            isRenderer = deviceType?.contains("MediaRenderer", true) == true ||
                avt != null,
        )
    }

    private fun absolutize(baseLocation: String, controlUrl: String): String {
        if (controlUrl.startsWith("http://") || controlUrl.startsWith("https://")) return controlUrl
        val base = URL(baseLocation)
        return URL(base, controlUrl).toString()
    }

    // ────────────────────────────── 控制 ──────────────────────────────

    suspend fun setAvTransportUri(
        location: String,
        mediaUrl: String,
        title: String,
        mime: String = "audio/mpeg",
        upnpClass: String = "object.item.audioItem.musicTrack",
    ): Boolean {
        val didl = buildDidl(title, mediaUrl, mime, upnpClass)
        return soap(
            location, AVT, "SetAVTransportURI",
            "<InstanceID>0</InstanceID>" +
                "<CurrentURI>${escapeXml(mediaUrl)}</CurrentURI>" +
                "<CurrentURIMetaData>${escapeXml(didl)}</CurrentURIMetaData>",
        )
    }

    suspend fun play(location: String, speed: String = "1"): Boolean = soap(
        location, AVT, "Play",
        "<InstanceID>0</InstanceID><Speed>$speed</Speed>",
    )

    suspend fun pause(location: String): Boolean = soap(
        location, AVT, "Pause", "<InstanceID>0</InstanceID>",
    )

    suspend fun stop(location: String): Boolean = soap(
        location, AVT, "Stop", "<InstanceID>0</InstanceID>",
    )

    suspend fun seek(location: String, positionMs: Long): Boolean = soap(
        location, AVT, "Seek",
        "<InstanceID>0</InstanceID><Unit>REL_TIME</Unit>" +
            "<Target>${formatTime(positionMs)}</Target>",
    )

    suspend fun setVolume(location: String, volume: Int): Boolean = soap(
        location, RCS, "SetVolume",
        "<InstanceID>0</InstanceID><Channel>Master</Channel>" +
            "<DesiredVolume>${volume.coerceIn(0, 100)}</DesiredVolume>",
        serviceType = RCS,
    )

    suspend fun getVolume(location: String): Int? {
        val body = soapRaw(
            location, RCS, "GetVolume",
            "<InstanceID>0</InstanceID><Channel>Master</Channel>",
            serviceType = RCS,
        ) ?: return null
        return RE_CURRENT_VOLUME.find(body)?.groupValues?.get(1)?.toIntOrNull()
    }

    suspend fun getTransportState(location: String): TransportState? {
        val body = soapRaw(location, AVT, "GetTransportInfo", "<InstanceID>0</InstanceID>")
            ?: return null
        val state = RE_TRANSPORT_STATE.find(body)?.groupValues?.get(1) ?: return null
        val status = RE_TRANSPORT_STATUS.find(body)?.groupValues?.get(1) ?: ""
        return TransportState(state, status)
    }

    /** @return (已播放毫秒, 总时长毫秒)，取不到返回 null */
    suspend fun getPosition(location: String): Pair<Long, Long>? {
        val body = soapRaw(location, AVT, "GetPositionInfo", "<InstanceID>0</InstanceID>")
            ?: return null
        val pos = RE_REL_TIME.find(body)?.groupValues?.get(1)?.let(::parseTime) ?: return null
        val dur = RE_TRACK_DURATION.find(body)?.groupValues?.get(1)?.let(::parseTime) ?: 0L
        return pos to dur
    }

    private fun controlUrlFor(location: String, serviceType: String): String? {
        val info = known[location] ?: return null
        return if (serviceType == AVT) info.avTransportControl else info.renderingControlControl
    }

    private suspend fun soap(
        location: String,
        type: String,
        action: String,
        innerXml: String,
        serviceType: String = type,
    ): Boolean = soapRaw(location, type, action, innerXml, serviceType) != null

    private suspend fun soapRaw(
        location: String,
        type: String,
        action: String,
        innerXml: String,
        serviceType: String = type,
    ): String? = withContext(Dispatchers.IO) {
        val controlUrl = controlUrlFor(location, serviceType)
        if (controlUrl == null) {
            Log.w(TAG, "找不到 $serviceType 的 controlURL（$location）")
            return@withContext null
        }
        // Pre-allocate StringBuilder to avoid multiple allocations
        val envelope = StringBuilder(512).apply {
            append("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
            .append("<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"")
            .append(" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">")
            .append("<s:Body>")
            .append("<u:$action xmlns:u=\"$type\">$innerXml</u:$action>")
            .append("</s:Body>")
            .append("</s:Envelope>")
        }
        try {
            val conn = (URL(controlUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000
                readTimeout = 8000
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
                setRequestProperty("SOAPACTION", "\"$type#$action\"")
                setRequestProperty("Connection", "close")
            }
            conn.outputStream.use { it.write(envelope.toString().toByteArray(StandardCharsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            conn.disconnect()
            if (code in 200..299) {
                body
            } else {
                Log.w(TAG, "$action 失败 HTTP $code: ${body.take(300)}")
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "$action 请求异常", e)
            null
        }
    }

    private fun buildDidl(title: String, url: String, mime: String, upnpClass: String): String {
        val id = "musiccast-${UUID.randomUUID()}"
        return "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\"" +
            " xmlns:dc=\"http://purl.org/dc/elements/1.1/\"" +
            " xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\"" +
            " xmlns:dlna=\"urn:schemas-dlna-org:metadata-1-0/\">" +
            "<item id=\"$id\" parentID=\"0\" restricted=\"1\">" +
            "<dc:title>${escapeXml(title)}</dc:title>" +
            "<upnp:class>$upnpClass</upnp:class>" +
            "<res protocolInfo=\"http-get:*:$mime:DLNA.ORG_PN=MP3;DLNA.ORG_OP=01;" +
            "DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000\">" +
            "${escapeXml(url)}</res></item></DIDL-Lite>"
    }

    private fun escapeXml(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    private fun formatTime(ms: Long): String {
        val totalSec = (ms / 1000).coerceAtLeast(0)
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
    }

    private fun parseTime(text: String): Long {
        val t = text.trim()
        if (t.isEmpty() || t.startsWith("NOT_IMPLEMENTED")) return 0L
        val parts = t.split(':').mapNotNull { it.toDoubleOrNull() }
        return when (parts.size) {
            3 -> ((parts[0] * 3600 + parts[1] * 60 + parts[2]) * 1000).toLong()
            2 -> ((parts[0] * 60 + parts[1]) * 1000).toLong()
            else -> 0L
        }
    }
}
