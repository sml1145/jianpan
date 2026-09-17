package com.mengting.ime.ui.background

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.sin

/**
 * 四套像素风动态背景，按北京时间自动切换：
 *  06:00-16:59 太阳 + 蓝天白云
 *  17:00-18:59 夕阳 + 火烧云
 *  19:00-00:59 月亮 + 夜空云彩
 *  01:00-05:59 流星 + 满天星
 */
enum class PixelScene { DAY, SUNSET, NIGHT, METEOR;
    companion object {
        fun forHour(h: Int) = when (h) {
            in 6..16 -> DAY
            in 17..18 -> SUNSET
            in 19..23, 0 -> NIGHT
            else -> METEOR
        }
    }
}

@Composable
fun rememberBeijingHour(): Int {
    val cal = remember { Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai")) }
    cal.timeInMillis = System.currentTimeMillis()
    return cal.get(Calendar.HOUR_OF_DAY)
}

private val PIXEL = 6f

@Composable
fun PixelArtBackground(modifier: Modifier = Modifier, animOn: Boolean = true) {
    val hour = rememberBeijingHour()
    val scene = remember(hour) { PixelScene.forHour(hour) }

    val t = rememberInfiniteTransition(label = "pixel")
    val drift by t.animateFloat(0f, 1f, infiniteRepeatable(tween(if (animOn) 24000 else Int.MAX_VALUE, easing = LinearEasing), RepeatMode.Restart), label = "drift")
    val twinkle by t.animateFloat(0.35f, 1f, infiniteRepeatable(tween(if (animOn) 1800 else Int.MAX_VALUE), RepeatMode.Reverse), label = "twinkle")
    val meteor by t.animateFloat(0f, 1f, infiniteRepeatable(tween(if (animOn) 4200 else Int.MAX_VALUE, easing = LinearEasing), RepeatMode.Restart), label = "meteor")

    val stars = remember { (0 until 46).map { i -> Triple((i * 37) % 100, (i * 53) % 100, (i * 17) % 10) } }
    val clouds = remember { listOf(Triple(8, 18, 3), Triple(48, 10, 4), Triple(72, 30, 3), Triple(26, 42, 2)) }

    Canvas(modifier = modifier.fillMaxSize()) {
        val w = size.width; val h = size.height
        val px = PIXEL * (w / 360f).coerceAtLeast(0.7f)
        fun pxRect(cx: Float, cy: Float, cw: Float, ch: Float, color: Color, alpha: Float = 1f) {
            drawRect(color, Offset(cx * px, cy * px), Size(cw * px, ch * px), alpha = alpha)
        }
        // 天空
        val sky = when (scene) {
            PixelScene.DAY -> Brush.verticalGradient(listOf(Color(0xFF7EC8F7), Color(0xFFBFE7FF)))
            PixelScene.SUNSET -> Brush.verticalGradient(listOf(Color(0xFF5B2A6E), Color(0xFFE2584A), Color(0xFFFFB25E)))
            PixelScene.NIGHT -> Brush.verticalGradient(listOf(Color(0xFF0B1030), Color(0xFF22306B)))
            PixelScene.METEOR -> Brush.verticalGradient(listOf(Color(0xFF05070F), Color(0xFF141B3C)))
        }
        drawRect(sky, size = size)

        val cols = (w / px).toInt()
        val rows = (h / px).toInt()

        // 天体
        when (scene) {
            PixelScene.DAY -> { // 太阳：像素圆 + 闪烁光芒
                val cx = cols * 0.74f; val cy = rows * 0.22f
                pxRect(cx - 2, cy - 2, 5f, 5f, Color(0xFFFFD54D))
                pxRect(cx - 3, cy - 1, 7f, 3f, Color(0xFFFFD54D))
                pxRect(cx - 1, cy - 3, 3f, 7f, Color(0xFFFFD54D))
                val g = if (animOn) (sin(drift * 6.283f * 4) + 1) / 2 else 0.5f
                pxRect(cx - 4, cy, 1f, 1f, Color(0xFFFFF59D), 0.4f + 0.6f * g)
                pxRect(cx + 4, cy, 1f, 1f, Color(0xFFFFF59D), 0.4f + 0.6f * (1 - g))
                pxRect(cx, cy - 4, 1f, 1f, Color(0xFFFFF59D), 0.4f + 0.6f * g)
                pxRect(cx, cy + 4, 1f, 1f, Color(0xFFFFF59D), 0.4f + 0.6f * (1 - g))
            }
            PixelScene.SUNSET -> { // 下沉的落日
                val cx = cols * 0.5f; val cy = rows * 0.52f
                pxRect(cx - 3, cy - 1, 7f, 3f, Color(0xFFFF7043))
                pxRect(cx - 2, cy - 2, 5f, 5f, Color(0xFFFF8A50))
                pxRect(cx - 1, cy - 3, 3f, 1f, Color(0xFFFFAB6E))
            }
            PixelScene.NIGHT -> { // 月亮 + 云
                val cx = cols * 0.76f; val cy = rows * 0.2f
                pxRect(cx - 2, cy - 2, 4f, 4f, Color(0xFFF5F3CE))
                pxRect(cx - 1, cy - 3, 3f, 6f, Color(0xFFF5F3CE))
                pxRect(cx, cy - 1, 2f, 2f, Color(0xFF0B1030))
            }
            PixelScene.METEOR -> Unit
        }

        // 星
        if (scene == PixelScene.NIGHT || scene == PixelScene.METEOR) {
            for ((i, s) in stars.withIndex()) {
                val a = if (animOn) (0.3f + 0.7f * abs(sin(drift * 6.283f * 2 + i * 0.7f))) else 0.8f
                pxRect(s.first * cols / 100f, s.second * rows / 100f, 1f, 1f, Color(0xFFFFFFFF), a * twinkle.coerceAtLeast(0.4f))
            }
        }

        // 云 / 火烧云 / 夜云
        val cloudColor = when (scene) {
            PixelScene.DAY -> Color(0xFFFFFFFF)
            PixelScene.SUNSET -> Color(0xFFFF6E52)
            PixelScene.NIGHT -> Color(0xFF2E3A6E)
            PixelScene.METEOR -> Color(0xFF1B2450)
        }
        val cloudColor2 = when (scene) {
            PixelScene.DAY -> Color(0xFFE8F6FF)
            PixelScene.SUNSET -> Color(0xFFFFA26B)
            PixelScene.NIGHT -> Color(0xFF3A4A8C)
            PixelScene.METEOR -> Color(0xFF232E63)
        }
        for ((i, c) in clouds.withIndex()) {
            val baseX = c.first / 100f * cols
            val move = if (animOn) drift * cols * 0.35f else 0f
            val x = ((baseX + move + i * 7) % (cols + 14)) - 7
            val y = c.second / 100f * rows
            val cc = if (i % 2 == 0) cloudColor else cloudColor2
            pxRect(x, y, c.third * 2f, 1.5f, cc, 0.9f)
            pxRect(x + 1, y - 1, c.third * 1.2f, 1f, cc, 0.75f)
            pxRect(x + 0.5f, y + 1.5f, c.third * 1.6f, 1f, cc, 0.6f)
        }

        // 流星
        if (scene == PixelScene.METEOR && animOn) {
            val mx = meteor * (cols + 20) - 10
            val my = rows * 0.15f + meteor * rows * 0.45f
            for (k in 0 until 6) {
                pxRect(mx - k * 1.2f, my - k * 1.2f, 1f, 1f, Color(0xFFFFFFFF), 1f - k * 0.15f)
            }
            pxRect(mx + 1, my, 1f, 1f, Color(0xFFB3E5FC))
        }
    }
}
