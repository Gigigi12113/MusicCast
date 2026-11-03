// LibraryScreen.kt  ——  页面：播放（顶部当前设备卡片 + 弹出选择框 + 下半播放控件）
package com.aidigua.musiccast.ui.screens

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.aidigua.musiccast.R
import com.aidigua.musiccast.cast.CastEngine
import com.aidigua.musiccast.ui.theme.blockBorder
import com.aidigua.musiccast.vm.PlayMode
import com.aidigua.musiccast.vm.UiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/** 封面内存缓存，按 track uri 做 key，切歌来回不重复解码。 */
private val coverCache = LruCache<String, Bitmap>(20)

/**
 * 「播放」页：
 *  - 顶部：当前投送设备卡片（点击弹窗切换）
 *  - 底部：播放卡片撑满剩余高度，内部按可用高度自适应，不滚动
 */
@Composable
fun LibraryScreen(
    state: UiState,
    // 设备区
    onRefresh: () -> Unit,
    onSelect: (CastEngine.CastDevice) -> Unit,
    onCastAgain: () -> Unit,
    onSyncVolume: () -> Unit,
    // 消息提示
    onShowMessage: (String) -> Unit,
    // 播放控件
    onToggle: () -> Unit,
    onPlayAll: () -> Unit,
    onStop: () -> Unit,
    onSeek: (Long) -> Unit,
    onVolume: (Int) -> Unit,
    onVolumeUp: () -> Unit,
    onVolumeDown: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    onSyncTime: () -> Unit,
    onPlayMode: (PlayMode) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        // ===== 顶部：当前设备卡片（按内容自适应高度）=====
        DevicesSection(
            state = state,
            onRefresh = onRefresh,
            onSelect = onSelect,
            onCastAgain = onCastAgain,
            onSyncVolume = onSyncVolume,
            onShowMessage = onShowMessage,
        )

        // ===== 底部：播放卡片占满剩余高度（内部自适应，不滚动）=====
        NowPlayingCard(
            modifier = Modifier.weight(1f),
            state = state,
            onToggle = onToggle,
            onStop = onStop,
            onSeek = onSeek,
            onVolume = onVolume,
            onVolumeUp = onVolumeUp,
            onVolumeDown = onVolumeDown,
            onNext = onNext,
            onPrev = onPrev,
            onSyncTime = onSyncTime,
            onPlayMode = onPlayMode,
            onPlayAll = onPlayAll,
        )
    }
}

/* =====================================================================
 *  设备区：顶部只放“当前设备卡片”，点击打开选择弹窗
 * =================================================================== */
@Composable
private fun DevicesSection(
    state: UiState,
    onRefresh: () -> Unit,
    onSelect: (CastEngine.CastDevice) -> Unit,
    onCastAgain: () -> Unit,
    onSyncVolume: () -> Unit,
    onShowMessage: (String) -> Unit,
) {
    var pickerOpen by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth()) {
        // 标题 + 搜索按钮
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 12.dp, top = 18.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("投送设备", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "DLNA 引擎：${state.engineName}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedButton(onClick = onRefresh, enabled = !state.discovering) {
                if (state.discovering) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Filled.Refresh, contentDescription = null, Modifier.size(18.dp))
                }
                Spacer(Modifier.width(6.dp))
                Text(if (state.discovering) "搜索中" else "搜索")
            }
        }

        if (!state.serverRunning) {
            Text(
                "注意：本地 HTTP 服务器未启动，投送会失败。请到设置里开启。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
        }

        // 当前设备卡片（点击打开选择框）
        CurrentDeviceCard(
            state = state,
            onClick = {
                if (state.devices.isEmpty()) {
                    onShowMessage("无设备，请重新搜索")
                } else {
                    pickerOpen = true
                }
            },
            onSyncVolume = onSyncVolume,
            onCastAgain = onCastAgain,
        )

        // 设备选择弹窗
        if (pickerOpen) {
            DevicePickerDialog(
                state = state,
                onSelect = { d ->
                    onSelect(d)
                    pickerOpen = false
                },
                onDismiss = { pickerOpen = false },
            )
        }
    }
}

/** 顶部当前设备卡片：未选/已选两种状态，点击弹出选择框。 */
@Composable
private fun CurrentDeviceCard(
    state: UiState,
    onClick: () -> Unit,
    onSyncVolume: () -> Unit,
    onCastAgain: () -> Unit,
) {
    val d = state.selectedDevice
    val selected = d != null

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(
                    if (selected) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surface
                )
                .blockBorder(
                    if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outline,
                    18.dp
                )
                .clickable(onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(
                        if (selected) Color.White.copy(alpha = 0.25f)
                        else MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                    ),
                Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Share,
                    contentDescription = null,
                    tint = if (selected) Color.White else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    d?.name ?: "未选择设备",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (selected) Color.White else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (d == null) "点击选择投送设备"
                    else buildString {
                        append(d.address)
                        if (d.isSpeaker) append(" · 音箱")
                        else if (d.isTV) append(" · 电视/渲染器")
                        append(" · 音量 ${state.volume}")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (selected) Color.White.copy(alpha = 0.85f)
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                Icons.Filled.ArrowDropDown,
                contentDescription = "切换设备",
                tint = if (selected) Color.White else MaterialTheme.colorScheme.primary,
            )
        }

        // 操作按钮始终占位（避免未选设备时布局跳动）
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 2.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = onSyncVolume,
                enabled = state.selectedDevice != null && !state.discovering,
            ) {
                Text("同步音量")
            }
            if (selected && state.nowPlaying != null) {
                Spacer(Modifier.width(4.dp))
                Button(onClick = onCastAgain, enabled = !state.busy) { Text("重投") }
            }
        }
    }
}

/** 设备选择弹窗：只放设备列表，高度随设备数量伸缩（每行约 70dp，封顶 380dp）。 */
@Composable
private fun DevicePickerDialog(
    state: UiState,
    onSelect: (CastEngine.CastDevice) -> Unit,
    onDismiss: () -> Unit,
) {
    val deviceCount = state.devices.size
    val listHeight = ((deviceCount * 70) + 16).coerceIn(70, 380).dp
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight(),
        ) {
            LazyColumn(
                Modifier
                    .fillMaxWidth()
                    .height(listHeight),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(state.devices, key = { it.id }) { d ->
                    val selected = state.selectedDevice?.id == d.id
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(
                                if (selected) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surface
                            )
                            .blockBorder(
                                if (selected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outline,
                                16.dp
                            )
                            .clickable { onSelect(d) }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(
                                    if (selected) Color.White.copy(alpha = 0.25f)
                                    else MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                ),
                            Alignment.Center,
                        ) {
                            Icon(
                                Icons.Filled.Share,
                                contentDescription = null,
                                tint = if (selected) Color.White
                                else MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                d.name,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (selected) Color.White
                                else MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                buildString {
                                    append(d.address)
                                    if (d.isSpeaker) append(" · 音箱")
                                    else if (d.isTV) append(" · 电视/渲染器")
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = if (selected) Color.White.copy(alpha = 0.85f)
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (selected) {
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = "当前设备",
                                tint = Color.White,
                            )
                        }
                    }
                }
            }
        }
    }
}

/* =====================================================================
 *  播放控件：不滚动；按卡片可用高度自适应尺寸，富余空间均匀分布
 * =================================================================== */
@Composable
private fun NowPlayingCard(
    modifier: Modifier = Modifier,
    state: UiState,
    onToggle: () -> Unit,
    onStop: () -> Unit,
    onSeek: (Long) -> Unit,
    onVolume: (Int) -> Unit,
    onVolumeUp: () -> Unit,
    onVolumeDown: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    onSyncTime: () -> Unit,
    onPlayMode: (PlayMode) -> Unit,
    onPlayAll: () -> Unit,
) {
    val track = state.nowPlaying
    val duration = when {
        state.durationMs > 0 -> state.durationMs
        track != null && track.durationMs > 0 -> track.durationMs
        else -> 0L
    }
    val hasTrack = track != null
    val gradBrush = Brush.linearGradient(
        listOf(
            Color(0xFF134E4A),
            Color(0xFF0F3B36),
        )
    )
    val context = LocalContext.current
    val trackUri = track?.uri

    // 封面提取：produceState 自动处理协程取消与缓存
    val coverBitmap by produceState<ImageBitmap?>(null, trackUri) {
        value = null
        val uri = trackUri ?: return@produceState
        coverCache.get(uri)?.let { bmp ->
            value = bmp.asImageBitmap()
            return@produceState
        }
        val extracted = withContext(Dispatchers.IO) { extractCover(context, uri) }
        if (extracted != null) {
            coverCache.put(uri, extracted)
            value = extracted.asImageBitmap()
        }
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .padding(bottom = 12.dp), // 增加一点底部防遮挡安全距离
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
    ) {
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .background(brush = gradBrush, shape = RoundedCornerShape(24.dp))
                .blockBorder(MaterialTheme.colorScheme.outline, 24.dp)
        ) {
            // 判断是否处于紧凑模式（屏幕可用高度偏小或宽度偏窄时触发）
            val isCompact = maxHeight < 520.dp || maxWidth < 360.dp

            Column(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // ================= 1. 顶部：封面与歌曲信息 =================
                Column(
                    Modifier
                        .weight(1f) // 占据所有剩余空间，给封面用
                        .fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    // 封面区域：自动计算能放下的最大正方形，绝不溢出，缩小占比
                    val coverSizeDp = if (isCompact) 220.dp else 280.dp
                    BoxWithConstraints(
                        Modifier
                            .weight(0.6f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            Modifier
                                .size(coverSizeDp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center,
                        ) {
                            val bmp = coverBitmap
                            if (track == null || bmp == null) {
                                Image(
                                    painter = painterResource(id = R.drawable.ic_launcher),
                                    contentDescription = null,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clip(RoundedCornerShape(16.dp)),
                                )
                            } else {
                                Image(
                                    bitmap = bmp,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clip(RoundedCornerShape(16.dp)),
                                )
                            }
                        }
                    }
                    
                    Spacer(Modifier.height(if (isCompact) 4.dp else 8.dp))
                    // 歌名
                    Text(
                        track?.title ?: "未在播放",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(2.dp))
                    // 副标题
                    Text(
                        if (track == null) {
                            state.selectedDevice?.name?.let { "目标设备：$it" } ?: "先选设备，再点曲目投送"
                        } else {
                            listOfNotNull(track.artist, state.selectedDevice?.name).joinToString(" · ")
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.85f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                // ================= 2. 控制区：固定高度，紧凑排列 =================
                Column(
                    Modifier.fillMaxWidth().padding(top = if (isCompact) 4.dp else 8.dp),
                    verticalArrangement = Arrangement.spacedBy(if (isCompact) 2.dp else 4.dp)
                ) {
                    // 播放控制按钮行（紧凑模式下压缩到更小）
                    val btnSize = if (isCompact) 36.dp else 40.dp
                    val iconSize = if (isCompact) 28.dp else 32.dp
                    Row(
                        Modifier.fillMaxWidth().height(btnSize),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onPrev, enabled = hasTrack, modifier = Modifier.size(btnSize)) {
                            Icon(Icons.Filled.SkipPrevious, "上一首", tint = Color.White, modifier = Modifier.size(iconSize))
                        }
                        IconButton(onClick = onToggle, enabled = hasTrack, modifier = Modifier.size(btnSize + 8.dp)) {
                            Icon(
                                if (state.transport == CastEngine.Transport.PLAYING) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                contentDescription = if (state.transport == CastEngine.Transport.PLAYING) "暂停" else "播放",
                                tint = Color.White,
                                modifier = Modifier.size(iconSize + 4.dp)
                            )
                        }
                        IconButton(onClick = onNext, enabled = hasTrack, modifier = Modifier.size(btnSize)) {
                            Icon(Icons.Filled.SkipNext, "下一首", tint = Color.White, modifier = Modifier.size(iconSize))
                        }
                        IconButton(onClick = onStop, enabled = hasTrack, modifier = Modifier.size(btnSize)) {
                            Icon(Icons.Filled.Stop, "停止", tint = Color.White, modifier = Modifier.size(iconSize))
                        }
                    }

                    // 进度条（强制限制高度，挤出多余垂直空间）
                    Slider(
                        value = if (duration > 0) (state.positionMs.toFloat() / duration).coerceIn(0f, 1f) else 0f,
                        onValueChange = { frac -> onSeek((frac * duration).toLong()) },
                        enabled = duration > 0 && hasTrack,
                        colors = SliderDefaults.colors(
                            activeTrackColor = Color.White,
                            inactiveTrackColor = Color.White.copy(alpha = 0.3f),
                            thumbColor = Color.White,
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(if (isCompact) 20.dp else 24.dp),
                    )
                    Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                        Text(formatMs(state.positionMs), style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.9f))
                        Text(formatMs(duration), style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.9f))
                    }

                    // 模式选择 / 音量调整：两列对齐，标签在按钮组正上方
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(0.dp)
                    ) {
                        // 左列：模式选择
                        Column(
                            Modifier.weight(1f),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                "模式选择",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White.copy(alpha = 0.7f),
                            )
                            // 播放模式
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                SegmentedButton("顺序", state.playMode == PlayMode.IN_ORDER, isCompact, matchVolumeHeight = true) { onPlayMode(PlayMode.IN_ORDER) }
                                SegmentedButton("随机", state.playMode == PlayMode.SHUFFLE, isCompact, matchVolumeHeight = true) { onPlayMode(PlayMode.SHUFFLE) }
                            }
                        }
                        
                        // 右列：音量调整
                        Column(
                            Modifier.weight(1f),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                "音量调整",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White.copy(alpha = 0.7f),
                            )
                            // 音量控制
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(if (isCompact) 6.dp else 8.dp)
                            ) {
                                VolumeButton(Icons.Filled.Remove, "减小音量", state.volume > 0, isCompact, onVolumeDown)
                                Text(
                                    state.volume.toString(),
                                    modifier = Modifier.width(if (isCompact) 28.dp else 36.dp),
                                    style = if (isCompact) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color.White,
                                    textAlign = TextAlign.Center,
                                )
                                VolumeButton(Icons.Filled.Add, "增大音量", state.volume < 100, isCompact, onVolumeUp)
                                
                                if (state.volume == 0) {
                                    Text("静音", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.8f))
                                }
                            }
                        }
                    }
                }

                // ================= 3. 播放全部：紧贴底部，永不被挤出 =================
                PlayAllButton(
                    playMode = state.playMode,
                    enabled = state.tracks.isNotEmpty() && state.selectedDevice != null && !state.busy,
                    onClick = onPlayAll,
                    height = if (isCompact) 40.dp else 48.dp, // 紧凑模式压到 40dp
                    modifier = Modifier.fillMaxWidth().padding(top = if (isCompact) 4.dp else 8.dp),
                )
            }
        }
    }
}

/**
 * 播放控件下方的大按钮。
 */
@Composable
private fun PlayAllButton(
    playMode: PlayMode,
    enabled: Boolean,
    onClick: () -> Unit,
    height: Dp,
    modifier: Modifier = Modifier,
) {
    val isShuffle = playMode == PlayMode.SHUFFLE
    val label = if (isShuffle) "随机播放歌曲" else "顺序播放歌曲"
    val icon = if (isShuffle) Icons.Filled.Shuffle else Icons.Filled.PlayArrow

    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(height),
        shape = RoundedCornerShape(height / 2),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(if (height == 40.dp) 18.dp else 22.dp))
        Spacer(Modifier.width(10.dp))
        Text(
            label,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun VolumeButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    desc: String,
    enabled: Boolean,
    isCompact: Boolean,
    onClick: () -> Unit,
) {
    val size = if (isCompact) 30.dp else 38.dp
    val iconSize = if (isCompact) 16.dp else 20.dp
    Surface(
        shape = CircleShape,
        color = if (enabled) Color.White.copy(alpha = 0.25f)
        else Color.White.copy(alpha = 0.12f),
        onClick = { if (enabled) onClick() },
    ) {
        Box(
            Modifier.size(size),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = desc,
                tint = if (enabled) Color.White
                else Color.White.copy(alpha = 0.4f),
                modifier = Modifier.size(iconSize),
            )
        }
    }
}

@Composable
private fun SegmentedButton(
    label: String,
    selected: Boolean,
    isCompact: Boolean,
    matchVolumeHeight: Boolean = false,
    onClick: () -> Unit,
) {
    val hPadding = if (isCompact) 12.dp else 16.dp
    val vPadding = if (isCompact) 4.dp else 6.dp
    val buttonHeight = if (matchVolumeHeight) (if (isCompact) 30.dp else 38.dp) else Dp.Unspecified

    Surface(
        shape = RoundedCornerShape(50),
        color = if (selected) MaterialTheme.colorScheme.primary
        else Color.White.copy(alpha = 0.14f),
        onClick = onClick,
    ) {
        Box(
            modifier = Modifier
                .height(buttonHeight)
                .padding(horizontal = hPadding),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) Color(0xFF062A26) else Color.White,
            )
        }
    }
}

/* =====================================================================
 *  格式化（同包共享，DevicesScreen 也会调用）
 * =================================================================== */
fun formatMs(ms: Long): String {
    if (ms <= 0) return "00:00"
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    else String.format(Locale.US, "%02d:%02d", m, s)
}

fun formatCountdown(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val sec = total % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, sec)
    else String.format(Locale.US, "%02d:%02d", m, sec)
}

fun formatSize(bytes: Long): String = when {
    bytes <= 0 -> ""
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
    else -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
}

/**
 * 从本地音频文件提取嵌入式专辑封面（必须在后台线程调用）。
 */
private fun extractCover(context: Context, uri: String): Bitmap? {
    return try {
        MediaMetadataRetriever().use { retriever ->
            retriever.setDataSource(context, Uri.parse(uri))
            val pic = retriever.embeddedPicture
            pic?.let { data ->
                BitmapFactory.decodeByteArray(data, 0, data.size)
            }
        }
    } catch (e: Exception) {
        null
    }
}