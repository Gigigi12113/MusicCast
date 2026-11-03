package com.aidigua.musiccast.ui.theme

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 区块描边：给任意容器画一圈圆角边框。
 *
 * 暗色主题的核心需求——每个区块边界清晰可辨。
 *
 * 为什么不用 foundation 的 Modifier.border：本机构建环境里 foundation
 * 产物缺 `androidx.compose.foundation.border` 包（编译期 Unresolved），
 * 所以用 ui-graphics 的 drawBehind 手绘等价描边，纯 Compose UI 模块，
 * 依赖最稳。
 *
 * 用法（放在 background 之后、clickable 之前，描边才会压在底色上）：
 * ```
 * Modifier
 *     .clip(RoundedCornerShape(16.dp))
 *     .background(color)
 *     .blockBorder(MaterialTheme.colorScheme.outline, 16.dp)
 *     .clickable { ... }
 * ```
 */
fun Modifier.blockBorder(
    color: Color,
    cornerRadius: Dp,
    width: Dp = 1.dp,
): Modifier = this.drawBehind {
    val w = width.toPx() / 2f
    drawRoundRect(
        color = color,
        topLeft = Offset(w, w),
        size = Size(this.size.width - w * 2f, this.size.height - w * 2f),
        cornerRadius = CornerRadius(cornerRadius.toPx()),
        style = Stroke(width.toPx()),
    )
}
