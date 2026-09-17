package com.mengting.ime.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.graphics.BitmapFactory
import com.mengting.ime.R
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * 开屏动画（连贯版，单一时间轴 0→1 驱动，阶段间交叉淡化）：
 *  0.00-0.18 图标以 9:16 呈现（淡入+轻微缩放）
 *  0.10-0.38 MT 标识缓缓浮现（叠在图标中央，图标渐暗）
 *  0.38-0.62 烟雾化：MT 模糊扩散，烟粒上飘渐隐
 *  0.55-0.85 竖排「梦婷输入法」五字自烟雾中依次浮现
 *  0.85-1.00 整体定格后进入引导页
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
    val progress = t.value
    val context = LocalContext.current
    val icon = remember(context) {
        try {
            BitmapFactory.decodeResource(context.resources, R.mipmap.ic_launcher_foreground)
        } catch (e: Exception) { null }
    }

    // 阶段量
    val iconAlpha = ease(seg(progress, 0f, 0.18f)) * (1f - 0.75f * ease(seg(progress, 0.38f, 0.55f)))
    val iconScale = 0.85f + 0.15f * ease(seg(progress, 0f, 0.18f)) + 0.06f * seg(progress, 0.38f, 0.62f)
    val mtAlpha = ease(seg(progress, 0.10f, 0.34f)) * (1f - ease(seg(progress, 0.42f, 0.58f)))
    val mtScale = 0.75f + 0.35f * ease(seg(progress, 0.10f, 0.34f))
    val mtBlur = 0f + 26f * seg(progress, 0.40f, 0.60f)
    val smokeAlpha = seg(progress, 0.38f, 0.50f) * (1f - seg(progress, 0.62f, 0.80f))
    val textProgress = seg(progress, 0.55f, 0.88f)

    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        // 图标 9:16
        if (icon != null && iconAlpha > 0.01f) {
            Image(
                icon.asImageBitmap(), null,
                Modifier
                    .fillMaxHeight(0.72f)
                    .aspectRatio(9f / 16f)
                    .alpha(iconAlpha)
                    .scale(iconScale),
                contentScale = ContentScale.Crop
            )
        }
        // MT 浮现 → 模糊化烟
        if (mtAlpha > 0.01f) {
            Text(
                "MT", fontSize = 96.sp, fontWeight = FontWeight.Black,
                color = Color(0xFFE86AC0),
                modifier = Modifier.alpha(mtAlpha).scale(mtScale)
                    .then(if (mtBlur > 0.5f) Modifier.blur(mtBlur.dp) else Modifier)
            )
        }
        // 烟雾粒子（从中心上飘渐隐）
        if (smokeAlpha > 0.01f) {
            for (i in 0 until 9) {
                val rise = seg(progress, 0.38f + i * 0.015f, 0.72f + i * 0.01f)
                val spread = (i - 4) * 26f * rise
                val a = smokeAlpha * (1f - rise) * (0.55f - Math.abs(i - 4) * 0.05f)
                if (a <= 0.01f) continue
                Box(
                    Modifier
                        .offset { IntOffset(spread.roundToInt(), (-120f * rise - i * 8).roundToInt()) }
                        .scale(0.6f + rise * 1.8f)
                        .alpha(a.coerceIn(0f, 1f))
                        .blur(16.dp)
                        .background(Color(0xFFB23A8F), CircleShape)
                        .padding(22.dp)
                )
            }
        }
        // 竖排五字依次浮现
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            for ((i, ch) in "梦婷输入法".withIndex()) {
                val local = ease(seg(textProgress, i * 0.16f, (i + 1) * 0.22f))
                if (local <= 0.01f) continue
                Text(
                    ch.toString(), fontSize = 42.sp, fontWeight = FontWeight.Bold,
                    color = Color(0xFFFBD5EC),
                    modifier = Modifier
                        .alpha(local)
                        .offset { IntOffset(0, ((1f - local) * 36f).roundToInt()) }
                        .scale(0.8f + 0.2f * local)
                        .padding(vertical = 2.dp)
                )
            }
        }
    }
}
