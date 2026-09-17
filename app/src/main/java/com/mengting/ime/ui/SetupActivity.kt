package com.mengting.ime.ui

import android.Manifest
import android.appwidget.AppWidgetManager
import android.content.ComponentName
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.mengting.ime.widget.TypingStatsWidget
import kotlinx.coroutines.launch

/** 首次引导 + 设置中心（主页 + 两个副页） */
class SetupActivity : ComponentActivity() {

    private val bgPicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
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
        return try {
            val comp = ComponentName.unflattenFromString(id)
            comp != null && comp.packageName == packageName
        } catch (e: Exception) {
            id.startsWith("$packageName/")
        }
    }

    /** 一键添加桌面小组件（API 26+ requestPinAppWidget） */
    private fun pinWidget() {
        if (Build.VERSION.SDK_INT >= 26) {
            val mgr = AppWidgetManager.getInstance(this)
            val provider = ComponentName(this, TypingStatsWidget::class.java)
            if (mgr.isRequestPinAppWidgetSupported) {
                val ok = mgr.requestPinAppWidget(provider, null, null)
                Toast.makeText(this, if (ok) "已请求添加桌面组件，请在弹窗中确认" else "添加请求失败", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "当前桌面不支持一键添加，请长按桌面手动添加「梦婷打字统计」组件", Toast.LENGTH_LONG).show()
            }
        } else {
            Toast.makeText(this, "系统版本较低，请长按桌面手动添加「梦婷打字统计」组件", Toast.LENGTH_LONG).show()
        }
    }

    // 副页导航：0=主页 1=前置准备 2=键盘设置
    private var subPage by mutableStateOf(0)

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
        var pack by remember { mutableIntStateOf(AppPrefs.soundPack) }
        var net by remember { mutableStateOf(AppPrefs.netBoost) }
        var anim by remember { mutableStateOf(AppPrefs.bgAnimationOn) }
        var invert by remember { mutableStateOf(AppPrefs.singleHandInvert) }
        var side by remember { mutableIntStateOf(AppPrefs.singleHandSide) }
        var keyColor by remember { mutableIntStateOf(AppPrefs.keyColor) }
        var updateMsg by remember { mutableStateOf("当前版本 ${BuildConfig.VERSION_NAME}") }
        var progress by remember { mutableIntStateOf(-1) }
        var checking by remember { mutableStateOf(false) }
        var downloading by remember { mutableStateOf(false) }
        var pendingUpdate by remember { mutableStateOf<UpdateChecker.Remote?>(null) }
        var showUpdateDialog by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()
        val localVer = remember { UpdateChecker.localVersion(ctx) }

        fun startDownload(remote: UpdateChecker.Remote) {
            scope.launch {
                showUpdateDialog = false
                pendingUpdate = null
                downloading = true
                progress = 0
                updateMsg = "正在下载 ${remote.tag}…"
                if (!UpdateChecker.canInstallUnknown(ctx)) {
                    downloading = false
                    updateMsg = "需要「安装未知应用」权限，授权后请重新检测更新"
                    UpdateChecker.openUnknownSourcesSettings(ctx)
                    return@launch
                }
                val f = UpdateChecker.download(ctx, remote.apkUrl ?: "", remote.sha256) { p -> progress = p }
                downloading = false
                if (f != null) {
                    progress = -1
                    updateMsg = "下载完成并通过校验，正在拉起安装 ${remote.tag}"
                    UpdateChecker.install(ctx, f)
                } else {
                    val reason = UpdateChecker.lastDownloadError ?: "未知错误"
                    updateMsg = "下载失败：$reason（可重试，已支持断点续传）"
                }
            }
        }

        Column(
            Modifier.fillMaxSize().background(Color(0xFFFFF7FB)).verticalScroll(rememberScrollState()).padding(20.dp)
        ) {
            if (subPage > 0) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("‹ 返回", fontSize = 16.sp, color = Color(0xFFB23A8F), fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable { subPage = 0 }.padding(vertical = 4.dp, horizontal = 2.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(if (subPage == 1) "前置准备" else "键盘设置", fontSize = 20.sp,
                        fontWeight = FontWeight.Black, color = Color(0xFF4A2B5A))
                }
                Spacer(Modifier.height(8.dp))
            }

            when (subPage) {
                1 -> {
                    // ===== 副页：前置准备 =====
                    Section("启用与默认")
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (enabled) "已启用 ✓" else "未启用", fontSize = 14.sp)
                        Spacer(Modifier.weight(1f))
                        Button(onClick = { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }) { Text("去启用输入法") }
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
                            Toast.makeText(ctx, if (enabled && isDefault) "设置完成，可以开始使用啦" else "请在系统列表中勾选并选择梦婷输入法",
                                Toast.LENGTH_LONG).show()
                        }
                    ) { Text("刷新启用状态") }
                    Spacer(Modifier.height(8.dp))
                    Text("按顺序完成以上两步后，在任意输入框点击即可呼出梦婷键盘。", fontSize = 12.sp, color = Color(0xFF8A6B7A))
                }
                2 -> {
                    // ===== 副页：键盘设置（外观+输入体验） =====
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
                    val colors = listOf(
                        0 to "默认",
                        0xFFE86AC0.toInt() to "粉",
                        0xFFFF8A9E.toInt() to "桃",
                        0xFFE5533D.toInt() to "红",
                        0xFFFF9F45.toInt() to "橙",
                        0xFFFFD54D.toInt() to "黄",
                        0xFFC9E265.toInt() to "柠绿",
                        0xFF7ED9A5.toInt() to "绿",
                        0xFF3FBFA0.toInt() to "青碧",
                        0xFF6ED3E8.toInt() to "湖蓝",
                        0xFF7EC8F7.toInt() to "天蓝",
                        0xFF4F7DF3.toInt() to "宝蓝",
                        0xFF9C8CFF.toInt() to "淡紫",
                        0xFF7A5AF8.toInt() to "紫",
                        0xFFD96AA7.toInt() to "玫红",
                        0xFF8D6E63.toInt() to "棕",
                        0xFF90A4AE.toInt() to "灰蓝",
                        0xFF37474F.toInt() to "墨"
                    )
                    for (rowColors in colors.chunked(6)) {
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 3.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            for ((c, name) in rowColors) {
                                val selected = keyColor == c
                                Box(
                                    Modifier
                                        .height(40.dp).weight(1f)
                                        .background(if (c == 0) Color(0xFFEEEEEE) else Color(c), RoundedCornerShape(8.dp))
                                        .clickable { keyColor = c; AppPrefs.keyColor = c },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        if (selected) "✓$name" else name,
                                        fontSize = 10.sp,
                                        color = if (selected) Color.White else Color(0xFF3A2440)
                                    )
                                }
                            }
                        }
                    }
                    Text("(点击色块应用按键颜色，✓为当前选中；灰=默认半透明白)", fontSize = 11.sp, color = Color(0xFF8A6B7A))

                    Spacer(Modifier.height(10.dp))
                    Section("输入体验")
                    SwitchRow("按键音效（通知铃声通道，免打扰自动屏蔽）", sound) { sound = it; AppPrefs.soundOn = it }
                    Text("音效包", fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                        for ((v, label) in listOf(0 to "清脆", 1 to "机械", 2 to "泡泡")) {
                            Button(
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (pack == v) Color(0xFFB23A8F) else Color(0xFFE5C9D8)),
                                onClick = { pack = v; AppPrefs.soundPack = v }
                            ) { Text(label, fontSize = 12.sp) }
                        }
                    }
                    SwitchRow("按键振动", vib) { vib = it; AppPrefs.vibrateOn = it }
                    SwitchRow("联网增强词库（提升词汇准确率，默认开）", net) { net = it; AppPrefs.netBoost = it }
                }
                else -> {
                    // ===== 主页 =====
                    Text("梦婷输入法", fontSize = 26.sp, fontWeight = FontWeight.Black, color = Color(0xFFB23A8F))
                    Text(updateMsg, fontSize = 12.sp, color = Color(0xFF8A6B7A))
                    Spacer(Modifier.height(14.dp))

                    MainCard("🚀 前置准备",
                        subtitle = { Text("启用输入法并设为默认（首次使用必读）", fontSize = 12.sp, color = Color(0xFF6B5670)) },
                        onClick = { subPage = 1 })

                    MainCard("⌨ 键盘设置",
                        subtitle = { Text("键盘外观、按键颜色、音效振动、联网增强", fontSize = 12.sp, color = Color(0xFF6B5670)) },
                        onClick = { subPage = 2 })

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
                    Text("提示：单手模式在键盘底部「单手」键开启；左右倾斜手机即可切换偏向（需系统重力传感器）。",
                        fontSize = 11.sp, color = Color(0xFF8A6B7A))

                    Spacer(Modifier.height(14.dp))
                    Section("检查更新")
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !checking && !downloading,
                        onClick = {
                            scope.launch {
                                checking = true
                                progress = -1
                                updateMsg = "正在检测更新…"
                                val result = UpdateChecker.check(ctx)
                                val remote = result.remote
                                checking = false
                                if (remote == null) {
                                    updateMsg = "检测失败（当前版本 $localVer）：\n" + result.errors.joinToString("\n")
                                    return@launch
                                }
                                if (!UpdateChecker.hasNew(remote, localVer)) {
                                    updateMsg = "已是最新版本 $localVer（仓库最新 ${remote.tag}，来源：${remote.source}）"
                                    return@launch
                                }
                                pendingUpdate = remote
                                showUpdateDialog = true
                                updateMsg = "发现新版本 ${remote.tag}，等待确认…"
                            }
                        }
                    ) {
                        Text(
                            when {
                                downloading && progress in 0..100 -> "正在更新 $progress%"
                                downloading -> "准备下载…"
                                checking -> "检测中…"
                                else -> "检测更新"
                            }
                        )
                    }
                    if (downloading) {
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { (progress.coerceIn(0, 100)) / 100f },
                            modifier = Modifier.fillMaxWidth().height(10.dp),
                            color = Color(0xFFB23A8F),
                            trackColor = Color(0xFFE5C9D8)
                        )
                        Text(
                            if (progress in 0..100) "正在更新（$progress%），请勿退出页面"
                            else "正在连接更新源…",
                            fontSize = 12.sp, color = Color(0xFF6B5670),
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                    Text("更新源：GitHub 仓库 sml1145/jianpan 最新发布（四通道自动容错：API / 网页 / Atom / CDN）",
                        fontSize = 11.sp, color = Color(0xFF8A6B7A))

                    Spacer(Modifier.height(10.dp))
                    Section("桌面小组件")
                    Text("「梦婷打字统计」组件：今日码字数 + 情绪占比，每天凌晨 5 点刷新。", fontSize = 12.sp, color = Color(0xFF6B5670))
                    Button(
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                        onClick = { pinWidget() }
                    ) { Text("一键添加到桌面") }

                    Spacer(Modifier.height(24.dp))
                }
            }
        }

        // 发现新版本确认弹窗
        if (showUpdateDialog) {
            val remote = pendingUpdate
            AlertDialog(
                onDismissRequest = { showUpdateDialog = false; pendingUpdate = null },
                title = { Text("发现新版本", fontWeight = FontWeight.Bold) },
                text = {
                    Column {
                        Text("当前版本：$localVer")
                        Text("最新版本：${remote?.tag ?: ""}")
                        if (!remote?.notes.isNullOrBlank()) {
                            Spacer(Modifier.height(8.dp))
                            Text("更新说明：", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            Text(remote?.notes ?: "", fontSize = 13.sp, color = Color(0xFF6B5670))
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { remote?.let { startDownload(it) } }) {
                        Text("立即更新", color = Color(0xFFB23A8F), fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = {
                        showUpdateDialog = false
                        pendingUpdate = null
                        updateMsg = "已取消更新（最新版本 ${remote?.tag}）"
                    }) { Text("稍后再说") }
                }
            )
        }
    }

    @Composable
    private fun MainCard(title: String, subtitle: @Composable () -> Unit, onClick: () -> Unit) {
        Box(
            Modifier.fillMaxWidth().padding(vertical = 5.dp)
                .background(Color.White, RoundedCornerShape(14.dp))
                .clickable { onClick() }
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            Column {
                Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFF4A2B5A))
                Spacer(Modifier.height(2.dp))
                subtitle()
            }
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

@Composable
private fun SpacerWidth12() = Spacer(Modifier.height(12.dp))
