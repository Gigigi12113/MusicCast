package com.aidigua.musiccast.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aidigua.musiccast.ui.screens.DevicesScreen
import com.aidigua.musiccast.ui.screens.LibraryScreen
import com.aidigua.musiccast.ui.screens.SettingsScreen
import com.aidigua.musiccast.vm.MainViewModel
import com.aidigua.musiccast.vm.PlayMode

private data class Tab(val label: String, val icon: ImageVector)

@Composable
fun MusicCastApp(vm: MainViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val listState = rememberLazyListState()

    // 第 0 页 = 播放（设备区 + 播放控件），第 1 页 = 播放列表（曲库）
    val tabs = listOf(
        Tab("播放", Icons.Filled.PlayArrow),
        Tab("播放列表", Icons.Filled.LibraryMusic),
        Tab("设置", Icons.Filled.Settings),
    )

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
    ) { inner ->
        Box(Modifier.fillMaxSize()) {
            // 内容区：底部留出悬浮导航栏的空间
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background,
            ) {
                Box(
                    Modifier
                        .padding(top = inner.calculateTopPadding())
                        .padding(bottom = 96.dp)
                ) {
                    when (tab) {
                        // 0 = 「播放」页：上半设备区 + 下半播放控件
                        0 -> LibraryScreen(
                            state = state,
                            // ── 设备区回调（原 DevicesScreen 的参数） ──
                            onRefresh = vm::discoverDevices,
                            onSelect = vm::selectDevice,
                            onCastAgain = { state.nowPlaying?.let(vm::cast) },
                            onSyncVolume = vm::syncVolumeNow,
                            // 消息提示
                            onShowMessage = vm::showMessage,
                            // ── 播放控件回调（原 LibraryScreen 的播放参数） ──
                            onToggle = vm::togglePlayPause,
                            onPlayAll = vm::playAll,
                            onStop = vm::stop,
                            onSeek = vm::seekTo,
                            onVolume = vm::setVolume,
                            onVolumeUp = vm::volumeUp,
                            onVolumeDown = vm::volumeDown,
                            onNext = { vm.playNext() },
                            onPrev = vm::playPrevious,
                            onSyncTime = vm::syncTimeNow,
                            onPlayMode = vm::setPlayMode,
                        )

                        // 1 = 「播放列表」页：Header + 搜索框 + 曲库列表 + 空态扫描按钮 + 文件夹播放标签页
                        1 -> DevicesScreen(
                            state = state,
                            listState = listState,
                            onScan = { vm.scanLibrary() },
                            onDiscover = vm::discoverDevices,
                            onQuery = vm::setQuery,
                            onCast = vm::cast,
                            onPickFolder = vm::playFolder,
                            onExitFolder = vm::exitFolderPlay,
                            onSwitchToFolderPlay = { vm.switchToFolderPlay() },
                        )

                        else -> SettingsScreen(
                            state = state,
                            onRestartServer = vm::restartServer,
                            onDiscoveryTimeout = vm::setDiscoveryTimeout,
                            onPickFolder = { uri -> vm.onScanFolderPicked(uri) },
                            onClearHistory = vm::clearHistory,
                            onRequestBatteryWhitelist = {
                                val ctx = context
                                runCatching {
                                    val i = android.content.Intent(
                                        android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                        android.net.Uri.parse("package:" + ctx.packageName),
                                    )
                                    ctx.startActivity(i)
                                }.onFailure {
                                    runCatching {
                                        ctx.startActivity(
                                            android.content.Intent(
                                                android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS
                                            )
                                        )
                                    }
                                }
                            },
                        )
                    }
                }
            }

            // ── 顶部固定通知横幅：所有提示（投送/搜索/失败）都显示在最上面 ──
            state.message?.let { msg ->
                TopNoticeBar(
                    message = msg,
                    onDismiss = vm::consumeMessage,
                    modifier = Modifier.align(Alignment.TopCenter),
                )
            }

            // ── 悬浮胶囊导航栏 ─────────────────────────
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(horizontal = 24.dp, vertical = 12.dp),
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp)
                        .shadow(
                            elevation = 12.dp,
                            shape = RoundedCornerShape(28.dp),
                            ambientColor = Color(0xFF000000),
                            spotColor = Color(0xFF000000),
                        ),
                    shape = RoundedCornerShape(28.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        tabs.forEachIndexed { index, t ->
                            val selected = tab == index
                            val container by animateColorAsState(
                                if (selected) MaterialTheme.colorScheme.primary
                                else Color.Transparent,
                                label = "navContainer",
                            )
                            Surface(
                                shape = RoundedCornerShape(24.dp),
                                color = container,
                                onClick = { tab = index },
                            ) {
                                Row(
                                    Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center,
                                ) {
                                    Icon(
                                        t.icon,
                                        contentDescription = t.label,
                                        tint = if (selected) MaterialTheme.colorScheme.onPrimary
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(22.dp),
                                    )
                                    if (selected) {
                                        Spacer(Modifier.size(6.dp))
                                        Text(
                                            t.label,
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onPrimary,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
// 顶部固定通知横幅（原样保留，未改动）
// ─────────────────────────────────────────────────────────────

/** 依据消息内容判断提示类型（决定左侧图标与强调色）。 */
private fun noticeKind(message: String): NoticeKind {
    val errWords = listOf("失败", "未响应", "拒绝", "错误", "无法", "未启动", "没找到", "未接受", "未连接")
    val busyWords = listOf("正在", "搜索中", "扫描")
    return when {
        errWords.any { message.contains(it) } -> NoticeKind.ERROR
        busyWords.any { message.contains(it) } -> NoticeKind.BUSY
        else -> NoticeKind.INFO
    }
}

private enum class NoticeKind(val icon: ImageVector) {
    INFO(Icons.Filled.CheckCircle),
    BUSY(Icons.Filled.Info),
    ERROR(Icons.Filled.WarningAmber),
}

@Composable
private fun TopNoticeBar(message: String, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val kind = noticeKind(message)

    LaunchedEffect(message) {
        kotlinx.coroutines.delay(4000)
        onDismiss()
    }

    val accent = when (kind) {
        NoticeKind.INFO -> MaterialTheme.colorScheme.primary
        NoticeKind.BUSY -> MaterialTheme.colorScheme.secondary
        NoticeKind.ERROR -> MaterialTheme.colorScheme.error
    }

    AnimatedVisibility(
        visible = true,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut(),
        modifier = modifier
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.6f)
                .shadow(
                    elevation = 10.dp,
                    shape = RoundedCornerShape(18.dp),
                    ambientColor = Color(0xFF000000),
                    spotColor = Color(0xFF000000),
                ),
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            border = androidx.compose.foundation.BorderStroke(1.dp, accent),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    kind.icon,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                    maxLines = 3,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "关闭通知",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}