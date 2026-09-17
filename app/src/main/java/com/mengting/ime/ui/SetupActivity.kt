package com.mengting.ime.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mengting.ime.BuildConfig
import com.mengting.ime.core.AppPrefs
import com.mengting.ime.feature.update.UpdateChecker
import kotlinx.coroutines.launch

/** 首次引导 + 设置中心 */
class SetupActivity : ComponentActivity() {

    private val bgPicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            // 持久化读取权限
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {}
            AppPrefs.customBackgroundUri = uri.toString()
            Toast.makeText(this, "自定义背景已应用", Toast.LENGTH_SHORT).show()
        }
    }

    private val audioPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (!ok) Toast.makeText(this, "语音输入需要麦克风权限", Toast.LENGTH_SHORT).show()
    }

    private val notifyPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private var refreshKey by mutableStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) notifyPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        audioPerm.launch(Manifest.permission.RECORD_AUDIO)
        setContent { SetupScreen(bgPicker = { bgPicker.launch("image/*") }) }
    }

    override fun onResume() {
        super.onResume()
        refreshKey++
    }

    private fun isImeEnabled(): Boolean {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        val list = imm.enabledInputMethodList
        return list.any { it.packageName == packageName }
    }

    private fun isImeDefault(): Boolean {
        val id = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD) ?: return false
        // 形如 com.mengting.ime/com.mengting.ime.ime.MengtingIME 或 com.mengting.ime/.ime.MengtingIME
        return try {
            val comp = android.content.ComponentName.unflattenFromString(id)
            comp != null && comp.packageName == packageName
        } catch (e: Exception) {
            id.startsWith("$packageName/")
        }
    }

    @Composable
    private fun SetupScreen(bgPicker: () -> Unit) {
        val ctx = LocalContext.current
        var enabled by remember { mutableStateOf(isImeEnabled()) }
        var isDefault by remember { mutableStateOf(isImeDefault()) }
        LaunchedEffect(refreshKey) {
            enabled = isImeEnabled()
            isDefault = isImeDefault()
        }
        var sound by remember { mutableStateOf(AppPrefs.soundOn) }
        var vib by remember { mutableStateOf(AppPrefs.vibrateOn) }
        var net by remember { mutableStateOf(AppPrefs.netBoost) }
        var anim by remember { mutableStateOf(AppPrefs.bgAnimationOn) }
        var invert by remember { mutableStateOf(AppPrefs.singleHandInvert) }
        var side by remember { mutableIntStateOf(AppPrefs.singleHandSide) }
        var keyColor by remember { mutableIntStateOf(AppPrefs.keyColor) }
        var updateMsg by remember { mutableStateOf("当前版本 ${BuildConfig.VERSION_NAME}") }
        var progress by remember { mutableIntStateOf(-1) }
        val scope = rememberCoroutineScope()

        Column(
            Modifier.fillMaxSize().background(Color(0xFFFFF7FB)).verticalScroll(rememberScrollState()).padding(20.dp)
        ) {
            Text("梦婷输入法", fontSize = 26.sp, fontWeight = FontWeight.Black, color = Color(0xFFB23A8F))
            Text(updateMsg, fontSize = 12.sp, color = Color(0xFF8A6B7A))
            Spacer(Modifier.height(14.dp))

            Section("第一步：启用与默认")
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(if (enabled) "已启用 ✓" else "未启用", fontSize = 14.sp)
                Spacer(Modifier.weight(1f))
                Button(onClick = {
                    startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
                }) { Text("去启用输入法") }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(if (isDefault) "已是默认 ✓" else "未设为默认", fontSize = 14.sp)
                Spacer(Modifier.weight(1f))
                Button(onClick = {
                    val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                    imm.showInputMethodPicker()
                }) { Text("设为默认输入法") }
            }
            Button(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                onClick = {
                    enabled = isImeEnabled(); isDefault = isImeDefault()
                    if (enabled && isDefault) Toast.makeText(ctx, "设置完成，可以开始使用啦", Toast.LENGTH_SHORT).show()
                    else Toast.makeText(ctx, "请在系统列表中勾选并选择梦婷输入法", Toast.LENGTH_LONG).show()
                }
            ) { Text("刷新启用状态") }

            Spacer(Modifier.height(10.dp))
            Section("键盘外观")
            SwitchRow("背景动画（像素动效）", anim) { anim = it; AppPrefs.bgAnimationOn = it }
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("自定义背景", fontSize = 14.sp)
                Spacer(Modifier.weight(1f))
                Button(onClick = bgPicker) { Text("上传图片") }
                Button(colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD9B8C9)),
                    onClick = { AppPrefs.customBackgroundUri = ""; Toast.makeText(ctx, "已恢复时间动态背景", Toast.LENGTH_SHORT).show() }) { Text("恢复默认") }
            }
            Text("按键颜色", fontSize = 14.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val colors = listOf(0 to "默认", 0xFFE86AC0.toInt() to "粉", 0xFF7EC8F7.toInt() to "蓝", 0xFFFFD54D.toInt() to "黄", 0xFF9C8CFF.toInt() to "紫", 0xFF7ED9A5.toInt() to "绿")
                for ((c, name) in colors) {
                    Box(
                        Modifier
                            .height(36.dp).weight(1f)
                            .background(if (c == 0) Color(0xFFEEEEEE) else Color(c), RoundedCornerShape(8.dp))
                            .clickable { keyColor = c; AppPrefs.keyColor = c }
                    )
                }
            }
            Text("(点击色块应用按键颜色，灰=默认半透明白)", fontSize = 11.sp, color = Color(0xFF8A6B7A))

            Spacer(Modifier.height(10.dp))
            Section("输入体验")
            SwitchRow("按键音效（通知铃声通道，免打扰自动屏蔽）", sound) { sound = it; AppPrefs.soundOn = it }
            SwitchRow("按键振动", vib) { vib = it; AppPrefs.vibrateOn = it }
            SwitchRow("联网增强词库（提升词汇准确率，默认开）", net) { net = it; AppPrefs.netBoost = it }

            Spacer(Modifier.height(10.dp))
            Section("单手模式")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("默认偏向：", fontSize = 14.sp)
                for ((v, label) in listOf(0 to "重力感应", 1 to "左", 2 to "右")) {
                    Button(
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (side == v) Color(0xFFB23A8F) else Color(0xFFE5C9D8)),
                        onClick = { side = v; AppPrefs.singleHandSide = v }
                    ) { Text(label, fontSize = 12.sp) }
                }
            }
            SwitchRow("重力偏向逆转（左下→偏右）", invert) { invert = it; AppPrefs.singleHandInvert = it }

            Spacer(Modifier.height(14.dp))
            Section("检查更新")
            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    scope.launch {
                        updateMsg = "正在检测…"
                        val remote = UpdateChecker.check(ctx)
                        val local = UpdateChecker.localVersion(ctx)
                        if (remote == null) {
                            updateMsg = "检测失败：无法连接更新源（当前 $local）"
                        } else if (!UpdateChecker.hasNew(remote, local)) {
                            updateMsg = "已是最新版本 $local（仓库最新 ${remote.tag}）"
                        } else {
                            updateMsg = "发现新版本 ${remote.tag}，开始下载…"
                            val url = remote.apkUrl
                            if (url == null) { updateMsg = "新版本无安装包附件"; return@launch }
                            if (!UpdateChecker.canInstallUnknown(ctx)) {
                                Toast.makeText(ctx, "请先允许安装未知应用", Toast.LENGTH_LONG).show()
                                startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}")))
                            }
                            val f = UpdateChecker.download(ctx, url) { p -> progress = p }
                            if (f != null) {
                                updateMsg = "下载完成，跳转安装 ${remote.tag}"
                                UpdateChecker.install(ctx, f)
                            } else updateMsg = "下载失败，请稍后重试"
                        }
                    }
                }
            ) { Text(if (progress in 0..100) "下载中 $progress%" else "检测更新") }
            Text("更新源：GitHub 仓库 sml1145/jianpan 的最新发布版本", fontSize = 11.sp, color = Color(0xFF8A6B7A))

            Spacer(Modifier.height(10.dp))
            Section("桌面小组件")
            Text("在桌面添加「梦婷打字统计」组件，可查看今日码字与情绪占比；数据每天凌晨 5 点刷新。", fontSize = 12.sp, color = Color(0xFF6B5670))

            Spacer(Modifier.height(24.dp))
        }
    }

    @Composable
    private fun Section(title: String) {
        Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFF4A2B5A),
            modifier = Modifier.padding(vertical = 6.dp))
    }

    @Composable
    private fun SwitchRow(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 14.sp, color = Color(0xFF3A2440), modifier = Modifier.weight(1f))
            Switch(checked = value, onCheckedChange = onChange)
        }
    }
}
