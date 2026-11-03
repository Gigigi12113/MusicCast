# MusicCastv2

基于 DLNA/UPnP 的本地音乐投屏播放器（Android / Kotlin / Jetpack Compose）。

AI制作，无需理会
## 功能

- 本地音乐扫描（MediaStore + SAF，支持 SD 卡自选目录）
- 内置 HTTP 媒体服务器（NanoHTTPD，支持 Range/断点续传）
- 局域网 DLNA 渲染器发现（SSDP）
- 双引擎投送：UPnPCast（Cling） / InternalDlnaController（自研）
- 播放控制：播放/暂停、上/下一首、进度拖拽、音量同步、顺序/随机模式
- 文件夹播放：标签页切换“曲库/文件夹”，弹窗选文件夹
- 随机播放隐藏列表：洗牌队列 + 游标，上/下一首对称
- 后台保活：前台服务 + 唤醒锁
- 设置持久化：DataStore（扫描目录、引擎选择、自动播放等）


## 核心依赖

- Compose BOM 2024.08.00 / Material3
- Room 2.6.1 + DataStore 1.1.1 (KSP)
- Cling 2.2.0 (UPnP) + NanoHTTPD 2.3.1
- Kotlin 2.4.21 / Gradle 9.8.2 / AGP 8.5+
- compileSdk 35, minSdk 24, targetSdk 34


## 架构要点

- **单向数据流**：`MainViewModel` → `StateFlow<UiState>` → Compose UI
- **统一顶部通知**：`TopNoticeBar` 展示所有提示（INFO/BUSY/ERROR），4s 自动消失
- **本地播放时钟**：墙钟倒计时兜底进度，不依赖设备回报
- **随机隐藏列表**：`shuffleOrder` + `shuffleIndex` + `shuffleSignature`，支持上/下一首对称

## 目录

```
app/src/main/java/com/aidigua/musiccast/
├── cast/           # CastEngine, InternalDlnaController
├── data/           # Room + DataStore
├── media/          # MediaHttpServer, Mp3Library, LanAddress
├── service/        # CastKeepAliveService
├── ui/screens/     # LibraryScreen, DevicesScreen, SettingsScreen
├── ui/MusicCastApp.kt  # 主导航 + 顶部横幅
└── vm/MainViewModel.kt # 核心状态机
```

## 最近更新 (2026-11-03)

- 播放控制栏重构：模式/音量同行，标签对齐按钮中心，按钮等高垂直居中
- 文件夹名去斜杠：`split('/').filter { it.isNotBlank() }.lastOrNull()`
- 统一提示：移除 Toast，全链路 `showMessage → UiState.message → TopNoticeBar`
- 仅编译 Release，产出固定 `/storage/emulated/0/apks/`

## 许可证

内部项目，仅供学习参考。第三方依赖遵循各自许可证。
