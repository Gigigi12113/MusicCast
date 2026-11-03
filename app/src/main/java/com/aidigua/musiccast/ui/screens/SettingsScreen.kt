package com.aidigua.musiccast.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aidigua.musiccast.vm.UiState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SettingsScreen(
    state: UiState,
    onRestartServer: (Int) -> Unit,
    onDiscoveryTimeout: (Long) -> Unit,
    onPickFolder: (android.net.Uri) -> Unit,
    onClearHistory: () -> Unit,
    onRequestBatteryWhitelist: () -> Unit,
) {
    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> uri?.let(onPickFolder) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text("设置", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))

        SectionCard("本地媒体服务器 (NanoHTTPD 2.3.1)") {
            SettingRow("状态", if (state.serverRunning) "运行中" else "已停止")
            SettingRow("地址", state.serverUrl.ifBlank { "—" })
            SettingRow(
                "端口",
                if (state.serverRunning) state.serverActualPort.toString()
                else state.settings.serverPort.toString(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { onRestartServer(state.settings.serverPort) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("重启服务")
            }
        }

        Spacer(Modifier.height(12.dp))
        SectionCard("曲库扫描") {
            SettingRow("扫描目录", state.settings.scanFolderLabel)
            if (state.settings.lastScanAt > 0) {
                val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
                SettingRow("上次扫描", fmt.format(Date(state.settings.lastScanAt)))
            }
            Spacer(Modifier.height(6.dp))
            OutlinedButton(
                onClick = { folderPicker.launch(null) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("选择 MP3 文件夹（支持 SD 卡）") }
        }

        Spacer(Modifier.height(12.dp))
        KeepAliveCard(state, onRequestBatteryWhitelist)

        Spacer(Modifier.height(12.dp))
        SectionCard("投送") {
            Text(
                "引擎：${state.engineName}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "搜索超时 ${state.settings.discoveryTimeoutMs} ms",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Slider(
                value = state.settings.discoveryTimeoutMs / 1000f,
                onValueChange = { v -> onDiscoveryTimeout((v * 1000).toLong()) },
                valueRange = 2f..10f,
                steps = 7,
                modifier = Modifier.semantics { this[SemanticsProperties.ContentDescription] = listOf("设备搜索超时时间，当前 ${state.settings.discoveryTimeoutMs / 1000} 秒") },
            )
        }

        Spacer(Modifier.height(12.dp))
        SectionCard("最近投送") {
            if (state.history.isEmpty()) {
                Text("暂无记录", style = MaterialTheme.typography.bodyMedium)
            } else {
                state.history.take(10).forEach { h ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(h.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                            Text(
                                "${h.deviceName} · ${if (h.success) "成功" else "失败"}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                TextButton(onClick = onClearHistory) { Text("清空记录") }
            }
        }

        Spacer(Modifier.height(12.dp))
        SectionCard("关于") {
            Text(
                "MusicCast · 仅支持 MP3",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Kotlin 2.0.21 · Jetpack Compose (BOM 2024.10.01) · Room 2.6.1 · " +
                    "DataStore 1.1.1 · NanoHTTPD 2.3.1 · UPnPCast v1.3.0",
                style = MaterialTheme.typography.labelSmall,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

/**
 * 后台保活设置。
 *
 * 三件事，缺一件都可能导致"切后台就断流"：
 *  1. 前台服务（已随投送自动启动）；
 *  2. 电池优化白名单（这里提供一键跳转）；
 *  3. 厂商自启动/后台限制（只能提示用户手动开）。
 */
@Composable
private fun KeepAliveCard(state: UiState, onWhitelist: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val ignoring = remember {
        val pm = ctx.getSystemService(android.content.Context.POWER_SERVICE)
            as android.os.PowerManager
        pm.isIgnoringBatteryOptimizations(ctx.packageName)
    }
    SectionCard("后台保活") {
        SettingRow("前台服务", if (state.nowPlaying != null) "投送中运行" else "待机")
        SettingRow("电池优化", if (ignoring) "已豁免" else "未豁免（可能被杀）")
        Spacer(Modifier.height(6.dp))
        Text(
            "投送时会自动启动前台服务并持有唤醒锁，保证本地媒体服务器不断流。" +
                "若手机仍然后台断流，请点击下方按钮把本应用加入电池优化白名单，" +
                "并在系统设置的「自启动/后台运行」里允许本应用。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onWhitelist, modifier = Modifier.fillMaxWidth()) {
            Text(if (ignoring) "已豁免，可再次检查" else "申请忽略电池优化")
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun SettingRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(
            label,
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
    }
}
