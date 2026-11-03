package com.aidigua.musiccast.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

// ── 暗色系固定调色板 ──────────────────────────────────────────────
// 底色层次:背景最暗 → 区块面 → 悬浮面,靠亮度差分区
private val BgDeep = Color(0xFF0E1116)       // 页面背景
private val BgBlock = Color(0xFF161B22)       // 区块面(卡片/行)
private val BgFloat = Color(0xFF1C232C)       // 悬浮面(导航栏/按钮)
private val TealAccent = Color(0xFF2DD4BF)    // 亮青主色(暗底强调)
private val BorderGray = Color(0xFF2E3A46)    // 区块描边(边界分明)
private val TextPrimary = Color(0xFFE6EDF3)   // 主文字
private val TextSecondary = Color(0xFF8B98A5) // 次文字

private val DarkColors = darkColorScheme(
    primary = TealAccent,
    onPrimary = Color(0xFF062A26),
    primaryContainer = Color(0xFF0F3B36),
    onPrimaryContainer = Color(0xFF9BF0E4),
    secondary = Color(0xFFF59E0B),
    onSecondary = Color(0xFF241700),
    background = BgDeep,
    onBackground = TextPrimary,
    surface = BgBlock,
    onSurface = TextPrimary,
    surfaceVariant = BgFloat,
    onSurfaceVariant = TextSecondary,
    outline = BorderGray,
    error = Color(0xFFF87171),
)

private val AppTypography = Typography(
    headlineSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
    ),
)

@Composable
fun MusicCastTheme(
    @Suppress("UNUSED_PARAMETER") darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    // 全暗色:不跟随系统,固定 DarkColors
    val colors = DarkColors
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            window.statusBarColor = colors.background.toArgb()
            window.navigationBarColor = colors.background.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }
    }
    MaterialTheme(colorScheme = colors, typography = AppTypography, content = content)
}
