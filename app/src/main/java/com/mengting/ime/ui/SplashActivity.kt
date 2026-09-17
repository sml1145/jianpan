package com.mengting.ime.ui

import android.app.Activity
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mengting.ime.R
import kotlinx.coroutines.delay

/**
 * 开屏动画：图标以 9:16 呈现 → 中间 MT 标识缓缓浮现 →
 * 化为烟雾散开 → 浮现竖排「梦婷输入法」五字 → 进入引导页。
 */
class SplashActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { SplashAnimation { goNext() } }
    }

    private fun goNext() {
        startActivity(Intent(this, SetupActivity::class.java))
        finish()
    }
}

@Composable
private fun SplashAnimation(onDone: () -> Unit) {
    var phase by remember { mutableIntStateOf(0) } // 0 图标 1 MT浮现 2 烟雾 3 竖排文字
    LaunchedEffect(Unit) {
        delay(500); phase = 1
        delay(1400); phase = 2
        delay(900); phase = 3
        delay(1500); onDone()
    }
    val context = androidx.compose.ui.platform.LocalContext.current
    val icon = remember(context) {
        try {
            BitmapFactory.decodeResource(context.resources, R.mipmap.ic_launcher_foreground)
        } catch (e: Exception) { null }
    }
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        // 阶段0/1：9:16 图标容器
        if (phase <= 1) {
            val appear by animateFloatAsState(if (phase >= 1) 1f else 0.15f, tween(1200, easing = FastOutSlowInEasing), label = "mt")
            if (icon != null) {
                Image(
                    icon.asImageBitmap(), null,
                    Modifier.fillMaxHeight(0.72f).aspectRatio(9f / 16f).alpha(if (phase == 0) 1f else 0.35f),
                    contentScale = ContentScale.Crop
                )
            }
            Text(
                "MT", fontSize = 96.sp, fontWeight = FontWeight.Black,
                color = Color(0xFFE86AC0), modifier = Modifier.alpha(appear).scale(0.8f + appear * 0.3f)
            )
        }
        // 阶段2：烟雾
        if (phase == 2) {
            for (i in 0 until 7) {
                val a by animateFloatAsState(0f, tween(100), label = "smoke$i")
                val s by animateFloatAsState(1f + i * 0.35f, tween(900), label = "smokes$i")
                Box(
                    Modifier
                        .offset(y = (-i * 14).dp)
                        .scale(s)
                        .alpha((0.5f - i * 0.06f).coerceAtLeast(0.05f))
                        .blur(18.dp)
                        .background(Color(0xFFB23A8F), androidx.compose.foundation.shape.CircleShape)
                        .fillMaxHeight(0.06f)
                )
            }
        }
        // 阶段3：竖排五字
        if (phase >= 3) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for ((i, ch) in "梦婷输入法".withIndex()) {
                    val a by animateFloatAsState(if (phase >= 3) 1f else 0f, tween(300 + i * 120), label = "v$i")
                    Text(
                        ch.toString(), fontSize = 40.sp, color = Color(0xFFFBD5EC),
                        fontWeight = FontWeight.Bold, modifier = Modifier.alpha(a)
                    )
                }
            }
        }
    }
}
