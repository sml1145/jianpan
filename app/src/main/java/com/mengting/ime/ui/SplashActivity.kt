package com.mengting.ime.ui

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mengting.ime.R
import kotlin.math.roundToInt

/**
 * 开屏动画（连贯版，单一时间轴 0→1 驱动，阶段间交叉淡化）。
 * 稳定性设计：组合树节点恒定存在，仅动画 alpha/scale/offset，
 * 不做条件增删节点；烟雾用 Canvas 径向渐变绘制，不依赖 API31+ 的 blur。
 *
 * 时间轴：
 *  0.00-0.18 图标以 9:16 呈现（淡入+轻微缩放）
 *  0.10-0.38 MT 标识缓缓浮现（图标同步渐暗）
 *  0.38-0.62 烟雾化：MT 放大淡出，烟粒上飘渐隐（Canvas）
 *  0.55-0.88 竖排「梦婷输入法」五字自烟雾中依次浮现
 *  0.88-1.00 定格后进入引导页
 */
class SplashActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { SplashTimeline { goNext() } }
    }

    private fun goNext() {
        startActivity(Intent(this, SetupActivity::class.java))
        finish()
    }
}

/** 把全局进度 t 映射到 [start,end] 区间的局部进度 0..1 */
private fun seg(t: Float, start: Float, end: Float): Float =
    ((t - start) / (end - start)).coerceIn(0f, 1f)

private fun ease(x: Float) = FastOutSlowInEasing.transform(x)

@Composable
private fun SplashTimeline(onDone: () -> Unit) {
    val t = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        t.animateTo(1f, tween(durationMillis = 4200, easing = LinearEasing))
        onDone()
    }
    val p = t.value
    val context = LocalContext.current
    val icon = remember(context) {
        try {
            BitmapFactory.decodeResource(context.resources, R.mipmap.ic_launcher_foreground)
        } catch (e: Exception) { null }
    }

    // 阶段量（全部 0..1）
    val iconAlpha = ease(seg(p, 0f, 0.18f)) * (1f - 0.8f * ease(seg(p, 0.36f, 0.56f)))
    val iconScale = 0.85f + 0.15f * ease(seg(p, 0f, 0.18f)) + 0.08f * seg(p, 0.38f, 0.62f)
    val mtAlpha = ease(seg(p, 0.10f, 0.34f)) * (1f - ease(seg(p, 0.42f, 0.60f)))
    val mtScale = 0.75f + 0.35f * ease(seg(p, 0.10f, 0.34f)) + 1.2f * seg(p, 0.40f, 0.62f)
    val smokeA = seg(p, 0.38f, 0.50f) * (1f - seg(p, 0.64f, 0.82f))
    val textP = seg(p, 0.55f, 0.90f)

    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        // 图标 9:16（节点恒在，仅 alpha）
        if (icon != null) {
            Image(
                icon.asImageBitmap(), null,
                Modifier
                    .fillMaxHeight(0.72f)
                    .aspectRatio(9f / 16f)
                    .alpha(iconAlpha.coerceIn(0f, 1f))
                    .scale(iconScale),
                contentScale = ContentScale.Crop
            )
        }

        // 烟雾：Canvas 径向渐变粒子（节点恒在；alpha=0 时无视觉）
        Canvas(
            Modifier.fillMaxSize().alpha(smokeA.coerceIn(0f, 1f))
        ) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val rise = seg(p, 0.38f, 0.80f)
            for (i in 0 until 9) {
                val fi = i.toFloat()
                val px = cx + (fi - 4f) * size.width * 0.055f * (0.3f + rise)
                val py = cy - rise * size.height * 0.22f - fi * size.height * 0.012f
                val r = size.width * (0.045f + 0.10f * rise) * (0.7f + 0.3f * (fi % 3) / 2f)
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(Color(0xE6B23A8F), Color(0x33B23A8F), Color(0x00B23A8F)),
                        center = Offset(px, py),
                        radius = r.coerceAtLeast(1f)
                    ),
                    radius = r.coerceAtLeast(1f),
                    center = Offset(px, py)
                )
            }
        }

        // MT 标识：浮现 → 放大淡出（化烟），节点恒在
        Text(
            "MT", fontSize = 96.sp, fontWeight = FontWeight.Black,
            color = Color(0xFFE86AC0),
            modifier = Modifier
                .alpha(mtAlpha.coerceIn(0f, 1f))
                .scale(mtScale)
        )

        // 竖排五字：五个节点恒在，依次浮现
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            for ((i, ch) in "梦婷输入法".withIndex()) {
                val local = ease(seg(textP, i * 0.15f, i * 0.15f + 0.30f))
                Text(
                    ch.toString(), fontSize = 42.sp, fontWeight = FontWeight.Bold,
                    color = Color(0xFFFBD5EC),
                    modifier = Modifier
                        .alpha(local.coerceIn(0f, 1f))
                        .offset { IntOffset(0, ((1f - local) * 36f).roundToInt()) }
                        .scale(0.8f + 0.2f * local)
                        .padding(vertical = 2.dp)
                )
            }
        }
    }
}
