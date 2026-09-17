package com.mengting.ime.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color

/**
 * 设置页背景的像素风粉色泡泡动画：
 * 泡泡以像素方块绘制，自底部缓缓上飘并左右轻摆，到达顶部后循环。
 */
@Composable
fun PinkBubbleCanvas(modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "bubbles")
    val drift by t.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(26000, easing = LinearEasing), RepeatMode.Restart),
        label = "drift"
    )
    // 泡泡参数：x 起点比例、大小(像素格)、速度倍率、相位
    val bubbles = remember {
        listOf(
            floatArrayOf(0.06f, 4f, 1.00f, 0.10f),
            floatArrayOf(0.18f, 6f, 0.75f, 0.45f),
            floatArrayOf(0.31f, 3f, 1.20f, 0.70f),
            floatArrayOf(0.44f, 7f, 0.60f, 0.25f),
            floatArrayOf(0.56f, 4f, 1.05f, 0.85f),
            floatArrayOf(0.67f, 5f, 0.85f, 0.40f),
            floatArrayOf(0.79f, 3f, 1.30f, 0.15f),
            floatArrayOf(0.88f, 6f, 0.70f, 0.60f),
            floatArrayOf(0.12f, 5f, 0.90f, 0.90f),
            floatArrayOf(0.62f, 3f, 1.15f, 0.55f)
        )
    }
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val px = 5f * (w / 360f).coerceAtLeast(0.7f)
        for (b in bubbles) {
            val phase = (drift * b[2] + b[3]) % 1f
            val y = h - phase * (h + 60f)
            val sway = kotlin.math.sin((phase * 6.283f * 2) + b[3] * 10f) * 14f
            val x = b[0] * w + sway
            val r = b[1]
            val ri = r.toInt().coerceAtLeast(1)
            // 像素泡泡：圆形轮廓用方块拼，中心留高光
            val cx = x / px; val cy = y / px
            for (dx in -ri..ri) {
                for (dy in -ri..ri) {
                    val d = kotlin.math.sqrt((dx * dx + dy * dy).toFloat())
                    if (d > r) continue
                    val edge = d > r - 1.4f
                    val hi = (-r * 0.35f).toInt()
                    val highlight = (dx == hi && dy == hi)
                    val color = when {
                        highlight -> Color(0xFFFFFFFF)
                        edge -> Color(0x88FF9ECE)
                        else -> Color(0x22FF9ECE)
                    }
                    drawRect(
                        color,
                        Offset((cx + dx) * px, (cy + dy) * px),
                        Size(px, px)
                    )
                }
            }
        }
    }
}
