// DevicesScreen.kt  ——  播放列表页（加：自动定位当前播放曲目 + 文件夹播放标签页）
package com.aidigua.musiccast.ui.screens

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aidigua.musiccast.cast.CastEngine
import com.aidigua.musiccast.data.TrackEntity
import com.aidigua.musiccast.ui.theme.blockBorder
import com.aidigua.musiccast.vm.PlaylistType
import com.aidigua.musiccast.vm.UiState
import com.aidigua.musiccast.ui.screens.formatMs
import com.aidigua.musiccast.ui.screens.formatSize

@Composable
fun DevicesScreen(
    state: UiState,
    listState: LazyListState,
    onScan: () -> Unit,
    onDiscover: () -> Unit,
    onQuery: (String) -> Unit,
    onCast: (TrackEntity) -> Unit,
    onPickFolder: (String) -> Unit,
    onExitFolder: () -> Unit,
    onSwitchToFolderPlay: () -> Unit,
) {
    val selectedFolderUri = remember { mutableStateOf<String?>(state.lastFolderUri) }
    val showFolderDialog = remember { mutableStateOf(false) }

    // 根据 playlistType 计算当前可见列表
    val visible = remember(state.tracks, state.folderMap, state.playlistType, state.lastFolderUri, state.query) {
        val base = when (state.playlistType) {
            PlaylistType.FOLDER_PLAY -> {
                state.lastFolderUri?.let { folderUri ->
                    state.folderMap[folderUri] ?: emptyList()
                } ?: emptyList()
            }
            PlaylistType.ALL_SONGS -> state.tracks
        }
        if (state.query.isBlank()) base
        else base.filter { t ->
            t.title.contains(state.query, true) ||
                (t.artist?.contains(state.query, true) ?: false) ||
                (t.album?.contains(state.query, true) ?: false)
        }
    }

    Column(Modifier.fillMaxSize()) {
        Header(state, onScan, onDiscover, showFolderDialog, onExitFolder, onSwitchToFolderPlay)

        OutlinedTextField(
            value = state.query,
            onValueChange = onQuery,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            singleLine = true,
            placeholder = { Text("搜索标题 / 歌手 / 专辑") },
            leadingIcon = {
                Icon(
                    Icons.Filled.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            },
            shape = RoundedCornerShape(28.dp),
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                focusedTextColor = MaterialTheme.colorScheme.onSurface,
                cursorColor = MaterialTheme.colorScheme.primary,
            ),
        )

        if (state.selectedDevice == null) {
            Text(
                "提示：请先在「播放」页选择投送设备。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
            )
        }

        when {
            state.scanning -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(12.dp))
                    Text("正在扫描本地 MP3…", style = MaterialTheme.typography.bodyMedium)
                }
            }
            visible.isEmpty() -> EmptyLibrary(onScan)
            else -> LazyColumn(
                Modifier.fillMaxSize(),
                state = listState,
                contentPadding = PaddingValues(
                    start = 12.dp, end = 12.dp, top = 4.dp, bottom = 24.dp
                ),
            ) {
                items(visible, key = { it.uri }) { track ->
                    TrackRow(
                        track = track,
                        isPlaying = state.nowPlaying?.uri == track.uri &&
                            state.transport == CastEngine.Transport.PLAYING,
                        enabled = !state.busy && state.selectedDevice != null,
                        onClick = { onCast(track) },
                    )
                }
            }
        }
    }

    // 文件夹选择弹窗
    if (showFolderDialog.value) {
        FolderPickerDialog(
            folderMap = state.folderMap,
            selectedFolder = selectedFolderUri.value,
            onSelect = { folderUri ->
                selectedFolderUri.value = folderUri
                onPickFolder(folderUri)
                showFolderDialog.value = false
            },
            onDismiss = { showFolderDialog.value = false },
        )
    }
}

@Composable
private fun Header(
    state: UiState,
    onScan: () -> Unit,
    onDiscover: () -> Unit,
    showFolderDialog: androidx.compose.runtime.MutableState<Boolean>,
    onExitFolder: () -> Unit,
    onSwitchToFolderPlay: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        // 标题栏
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 12.dp, top = 18.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("播放列表", style = MaterialTheme.typography.headlineSmall)
                Text(
                    buildString {
                        val count = when (state.playlistType) {
                            PlaylistType.FOLDER_PLAY -> state.lastFolderUri?.let { state.folderMap[it]?.size ?: 0 } ?: 0
                            PlaylistType.ALL_SONGS -> state.tracks.size
                        }
                        append("$count 首 · ")
                        append(if (state.serverRunning) "服务器 ${state.serverUrl}" else "服务器未启动")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onDiscover) {
                Icon(Icons.Filled.Search, contentDescription = "扫描设备")
            }
            IconButton(onClick = onScan) {
                Icon(Icons.Filled.Refresh, contentDescription = "重新扫描")
            }
        }

        // 标签页：曲库 / 文件夹播放
        val folderTitle = state.lastFolderUri?.let { state.folderMap[it]?.firstOrNull()?.folder?.let { path ->
            if (path == "/") "根目录" else path.split('/').filter { it.isNotBlank() }.lastOrNull() ?: path
        } }
        TabBar(
            selectedType = state.playlistType,
            folderTitle = folderTitle,
            onAllSongsClick = {
                if (state.playlistType == PlaylistType.FOLDER_PLAY) onExitFolder()
            },
            onFolderPlayClick = {
                if (state.playlistType != PlaylistType.FOLDER_PLAY) {
                    if (!state.hasFolderPlaylist) {
                        showFolderDialog.value = true
                    } else {
                        onSwitchToFolderPlay()
                    }
                }
            },
            onFolderPlayReclick = { showFolderDialog.value = true },
        )
    }
}

@Composable
private fun TabBar(
    selectedType: PlaylistType,
    folderTitle: String?,
    onAllSongsClick: () -> Unit,
    onFolderPlayClick: () -> Unit,
    onFolderPlayReclick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TabItem(
            label = "曲库",
            icon = Icons.Filled.MusicNote,
            selected = selectedType == PlaylistType.ALL_SONGS,
            modifier = Modifier.weight(1f),
            onClick = onAllSongsClick,
        )
        TabItem(
            label = folderTitle ?: "文件夹播放",
            icon = Icons.Filled.Folder,
            selected = selectedType == PlaylistType.FOLDER_PLAY,
            enabled = true,
            modifier = Modifier.weight(1f),
            onClick = {
                if (selectedType == PlaylistType.FOLDER_PLAY) {
                    onFolderPlayReclick()
                } else {
                    onFolderPlayClick()
                }
            },
        )
    }
}

@Composable
private fun TabItem(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val textColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
    val iconTint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
    // 两个标签都用深色背景，选中态加亮色边框
    val backgroundColor = MaterialTheme.colorScheme.surfaceContainerHighest
    val borderColor = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent
    val borderWidth = if (selected) 2.dp else 0.dp

    Box(
        modifier
            .height(48.dp)
            .background(backgroundColor, RoundedCornerShape(24.dp))
            .border(borderWidth, borderColor, RoundedCornerShape(24.dp))
            .clickable(
                enabled = enabled,
                onClick = onClick,
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            )
            .padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(20.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, color = textColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun TrackRow(
    track: TrackEntity,
    isPlaying: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .blockBorder(MaterialTheme.colorScheme.outline, 16.dp)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            Alignment.Center,
        ) {
            Icon(
                Icons.Filled.PlayArrow,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                track.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOfNotNull(
                    track.artist,
                    track.album,
                    if (track.durationMs > 0) formatMs(track.durationMs) else null,
                    formatSize(track.sizeBytes),
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun EmptyLibrary(onScan: () -> Unit) {
    Box(Modifier.fillMaxSize(), Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("还没有 MP3", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                "授予音频权限后扫描，或在设置里指定一个文件夹",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(14.dp))
            Button(onClick = onScan) { Text("扫描本地 MP3") }
        }
    }
}

/** 文件夹选择弹窗 */
@Composable
private fun FolderPickerDialog(
    folderMap: Map<String, List<TrackEntity>>,
    selectedFolder: String?,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val folders = folderMap.keys.toList().sortedBy { it }
    if (folders.isEmpty()) return

    // 使用 Dialog 自定义宽度 2/3 屏幕
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        androidx.compose.material3.Card(
            modifier = Modifier
                .fillMaxWidth(0.66f)  // 宽度 2/3 屏幕
                .padding(16.dp)
                .wrapContentSize(),
            shape = RoundedCornerShape(16.dp),
            elevation = androidx.compose.material3.CardDefaults.cardElevation(defaultElevation = 8.dp),
            colors = androidx.compose.material3.CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
        ) {
            Column(
                modifier = Modifier.padding(12.dp),  // 减小内边距
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // 标题
                Text(
                    "选择播放文件夹",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(8.dp))  // 减小间距
                
                // 分隔线
                Divider(
                    color = MaterialTheme.colorScheme.outlineVariant,
                    thickness = 1.dp,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(4.dp))  // 减小间距

                // 文件夹列表 - 增加高度，压缩间距
                LazyColumn(
                    Modifier
                        .fillMaxWidth()
                        .height(400.dp),  // 增加列表高度
                ) {
                    items(folders, key = { it }) { folder ->
                        val trackCount = folderMap[folder]?.size ?: 0
                        val isSelected = folder == selectedFolder
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp, horizontal = 8.dp)  // 压缩垂直间距
                                .background(
                                    if (isSelected) MaterialTheme.colorScheme.primaryContainer 
                                    else MaterialTheme.colorScheme.surfaceContainer,
                                    RoundedCornerShape(8.dp)  // 减小圆角
                                )
                                .border(
                                    if (isSelected) 2.dp else 0.dp,
                                    if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                                    RoundedCornerShape(8.dp)
                                )
                                .clickable(onClick = { onSelect(folder) })
                                .clip(RoundedCornerShape(8.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(
                                modifier = Modifier.padding(vertical = 6.dp),  // 压缩内边距
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text(
                                    folder.let { path ->
                                        if (path == "/") "根目录"
                                        else path.split('/').filter { it.isNotBlank() }.lastOrNull() ?: path
                                    },
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    textAlign = TextAlign.Center,
                                    color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    "$trackCount 首",
                                    style = MaterialTheme.typography.bodySmall,
                                    textAlign = TextAlign.Center,
                                    color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(4.dp))  // 减小间距
                Divider(
                    color = MaterialTheme.colorScheme.outlineVariant,
                    thickness = 1.dp,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(4.dp))  // 减小间距

                // 底部按钮 - 只有关闭按钮，居中
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Button(
                        onClick = onDismiss,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                        ),
                    ) { Text("关闭") }
                }
            }
        }
    }
}
