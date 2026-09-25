package com.mengting.ime.ui

import android.Manifest
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mengting.ime.BuildConfig
import com.mengting.ime.core.AppPrefs
import com.mengting.ime.core.MediaImporter
import java.io.File
import com.mengting.ime.feature.update.UpdateChecker
import com.mengting.ime.widget.TypingStatsWidget
import com.mengting.ime.widget.WidgetPinCallback
import kotlinx.coroutines.launch

/** 首次引导 + 设置中心（主页 + 两个副页，系统返回键先回主页） */
class SetupActivity : ComponentActivity() {

    /**
     * 背景图选择：复制进私有目录后记录路径。
     *
     * 不能直接存 Uri —— GetContent 用的是 ACTION_GET_CONTENT，只授予临时读权限，
     * 且不支持 takePersistableUriPermission（原实现调用它并把 SecurityException 静默吞掉，
     * 于是弹出「已应用」却对输入法服务不可读，背景永远不生效）。
     * 复制成私有文件后读取不再依赖任何授权。失败时把原因如实告知，不再谎报成功。
     */
    private val bgPicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        when (val r = MediaImporter.importBackground(this, uri)) {
            is MediaImporter.Result.Ok -> {
                AppPrefs.setCustomBackground(r.path)
                bgStatus = "已应用自定义背景（背景动画已自动关闭）"
                refreshKey++
                Toast.makeText(this, "自定义背景已应用", Toast.LENGTH_SHORT).show()
            }
            is MediaImporter.Result.Fail -> {
                bgStatus = "未生效：${r.reason}"
                Toast.makeText(this, "背景未生效：${r.reason}", Toast.LENGTH_LONG).show()
            }
        }
    }

    /** 自定义音效选择：同样复制进私有目录，并立即试听给出可感知反馈。 */
    private val soundPicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        when (val r = MediaImporter.importSound(this, uri)) {
            is MediaImporter.Result.Ok -> {
                AppPrefs.setCustomSound(r.path)
                soundStatus = "已应用自定义音效：${File(r.path).name}"
                refreshKey++
                // 立即试听：让用户当场听到效果，而不是等到打字时才发现没声音
                SoundPreview.playFile(this, r.path)
                Toast.makeText(this, "自定义音效已应用，正在试听", Toast.LENGTH_SHORT).show()
            }
            is MediaImporter.Result.Fail -> {
                soundStatus = "未生效：${r.reason}"
                Toast.makeText(this, "音效未生效：${r.reason}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private val audioPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (!ok) Toast.makeText(this, "语音输入需要麦克风权限", Toast.LENGTH_SHORT).show()
    }

    private val smsPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        Toast.makeText(this, if (ok) "短信验证码捕获已开启" else "未授权则无法自动捕获短信验证码（不影响其他功能）", Toast.LENGTH_SHORT).show()
    }

    private val notifyPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private var refreshKey by mutableStateOf(0)

    // 背景/音效导入的即时状态文案（类级别：picker 回调在此，需能刷新 UI）。
    // 为空表示无提示；失败时如实展示原因，绝不再无脑弹「已应用」。
    private var bgStatus by mutableStateOf("")
    private var soundStatus by mutableStateOf("")

    // 副页导航：0=主页 1=前置准备 2=键盘设置
    private var subPage by mutableStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 按需请求：已授权就不再弹，避免每次进设置页重复骚扰用户（旧实现无条件 launch 三个权限）。
        // 这些权限分别服务于语音输入、短信验证码捕获、更新通知，缺失时对应功能会自行降级提示，
        // 因此首次未授权也允许稍后在对应功能处再补授，不在启动时强制三连弹。
        requestIfMissing(Manifest.permission.RECORD_AUDIO) { audioPerm.launch(it) }
        requestIfMissing(Manifest.permission.RECEIVE_SMS) { smsPerm.launch(it) }
        if (Build.VERSION.SDK_INT >= 33) {
            requestIfMissing(Manifest.permission.POST_NOTIFICATIONS) { notifyPerm.launch(it) }
        }
        // 系统返回键：副页先回主页，主页才退出应用
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (subPage != 0) {
                    subPage = 0
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
        setContent {
            SetupScreen(
                bgPicker = { bgPicker.launch("image/*") },
                soundPicker = { soundPicker.launch("audio/*") }
            )
        }
    }

    override fun onResume() {
        super.onResume()
        refreshKey++
    }

    /** 仅在权限尚未授予时发起请求，避免每次进入设置页重复弹窗。 */
    private fun requestIfMissing(permission: String, launch: (String) -> Unit) {
        val granted = ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
        if (!granted) launch(permission)
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

    /** 一键添加桌面小组件：系统确认弹窗 + 结果回调；不支持时直接跳系统小组件选择页 */
    private fun pinWidget() {
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                val mgr = AppWidgetManager.getInstance(this)
                val provider = ComponentName(this, TypingStatsWidget::class.java)
                if (mgr.isRequestPinAppWidgetSupported) {
                    // 回调：系统确认结果通过广播送达
                    val cb = android.app.PendingIntent.getBroadcast(
                        this, 20260917,
                        Intent(this, WidgetPinCallback::class.java),
                        android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_MUTABLE
                    )
                    val ok = mgr.requestPinAppWidget(provider, null, cb)
                    if (ok) {
                        Toast.makeText(this, "已拉起系统确认框，请在其中点「添加」；若未弹出请看下方教程", Toast.LENGTH_LONG).show()
                    } else {
                        openWidgetChooser()
                    }
                } else {
                    openWidgetChooser()
                }
            } catch (e: Exception) {
                openWidgetChooser()
            }
        } else {
            Toast.makeText(this, "系统版本较低，请长按桌面手动添加「梦婷打字统计」组件", Toast.LENGTH_LONG).show()
        }
    }

    /** 直接跳转系统小组件选择页（真实系统 UI，非空壳） */
    private fun openWidgetChooser() {
        val tried = listOf(
            Intent().setComponent(ComponentName("com.android.settings", "com.android.settings.Settings\$AppWidgetPickerActivity")),
            Intent().setComponent(ComponentName("com.android.settings", "com.android.settings.AppWidgetPicker")),
            Intent(Intent.ACTION_MAIN).addCategory("android.appwidget.category.WIDGET_PICKER")
        )
        for (i in tried) {
            try {
                startActivity(i)
                Toast.makeText(this, "请在系统小组件列表中找到「梦婷打字统计」并添加", Toast.LENGTH_LONG).show()
                return
            } catch (_: Exception) {}
        }
        Toast.makeText(this, "当前桌面不支持自动添加，请长按桌面 → 小组件 → 梦婷打字统计", Toast.LENGTH_LONG).show()
    }

    @Composable
    private fun SetupScreen(bgPicker: () -> Unit, soundPicker: () -> Unit) {
        val ctx = LocalContext.current
        var enabled by remember { mutableStateOf(isImeEnabled()) }
        var isDefault by remember { mutableStateOf(isImeDefault()) }
        LaunchedEffect(refreshKey) {
            enabled = isImeEnabled()
            isDefault = isImeDefault()
        }
        var sound by remember { mutableStateOf(AppPrefs.soundOn) }
        var vib by remember { mutableStateOf(AppPrefs.vibrateOn) }
        // 外观类设置改为订阅 StateFlow：picker 回调改值后此处自动刷新，无需手动 refreshKey
        val pack by AppPrefs.soundPack.collectAsState()
        val customSound by AppPrefs.customSoundPath.collectAsState()
        var net by remember { mutableStateOf(AppPrefs.netBoost) }
        val anim by AppPrefs.bgAnimationOn.collectAsState()
        val customBg by AppPrefs.customBackgroundPath.collectAsState()
        var invert by remember { mutableStateOf(AppPrefs.singleHandInvert) }
        var side by remember { mutableIntStateOf(AppPrefs.singleHandSide) }
        var keyColor by remember { mutableIntStateOf(AppPrefs.keyColor) }
        var updateMsg by remember { mutableStateOf("当前版本 ${BuildConfig.VERSION_NAME}") }
        var progress by remember { mutableIntStateOf(-1) }
        var checking by remember { mutableStateOf(false) }
        var downloading by remember { mutableStateOf(false) }
        var pendingUpdate by remember { mutableStateOf<UpdateChecker.Remote?>(null) }
        var showUpdateDialog by remember { mutableStateOf(false) }
        var showBrowserFallback by remember { mutableStateOf(false) }
        var showWidgetHelp by remember { mutableStateOf(false) }
        var widgetChars by remember { mutableIntStateOf(0) }
        var widgetMood by remember { mutableStateOf(Triple(0, 0, 100)) }
        val scope = rememberCoroutineScope()
        val localVer = remember { UpdateChecker.localVersion(ctx) }
        LaunchedEffect(refreshKey) {
            widgetChars = com.mengting.ime.core.TypingStats.todayChars()
            widgetMood = com.mengting.ime.core.TypingStats.moodPercents()
        }

        fun startDownload(remote: UpdateChecker.Remote) {
            showUpdateDialog = false
            pendingUpdate = null
            downloading = true
            progress = 0
            updateMsg = "正在下载 ${remote.tag}（多连接并行加速）…"
            if (!UpdateChecker.canInstallUnknown(ctx)) {
                downloading = false
                updateMsg = "需要「安装未知应用」权限，授权后请重新检测更新"
                UpdateChecker.openUnknownSourcesSettings(ctx)
                return
            }
            UpdateChecker.startDownload(
                ctx, remote,
                onProgress = { p -> progress = p },
                onSlow = { msg -> updateMsg = msg },
                onDone = { f ->
                    downloading = false
                    if (f != null) {
                        progress = -1
                        updateMsg = "下载完成并通过校验，正在拉起安装 ${remote.tag}"
                        UpdateChecker.install(ctx, f)
                    } else {
                        progress = -1
                        val reason = UpdateChecker.lastDownloadError ?: "未知错误"
                        updateMsg = "下载失败：$reason\n可稍后重试（已下载进度会保留，支持断点续传）"
                        showBrowserFallback = true
                    }
                }
            )
        }

        Box(Modifier.fillMaxSize().background(Color(0xFFFFF7FB))) {
            // 泡泡仅作背景层：最先绘制，在内容之下
            if (anim) {
                PinkBubbleCanvas(Modifier.fillMaxSize().alpha(0.5f))
            }
            Column(
                Modifier.fillMaxSize()
                    .verticalScroll(rememberScrollState()).padding(20.dp)
            ) {
                if (subPage > 0) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("‹ 返回", fontSize = 16.sp, color = Color(0xFFB23A8F), fontWeight = FontWeight.Bold,
                            modifier = Modifier.clickable { subPage = 0 }.padding(vertical = 4.dp, horizontal = 2.dp))
                        Spacer(Modifier.width(12.dp))
                        Text(
                            when (subPage) {
                                1 -> "前置准备"
                                2 -> "键盘设置"
                                else -> "单手模式"
                            },
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Black, color = Color(0xFF4A2B5A)
                        )
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
                        // 背景动画与自定义背景互斥：开动画会清除自定义背景，反之亦然
                        SwitchRow("背景动画（像素动效）", anim) { AppPrefs.setBgAnimation(it) }
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("自定义背景", fontSize = 14.sp)
                            Spacer(Modifier.weight(1f))
                            Button(onClick = bgPicker) { Text("上传图片") }
                            Button(colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD9B8C9)),
                                onClick = {
                                    AppPrefs.setCustomBackground("")
                                    bgStatus = "已恢复默认像素背景（背景动画已重新开启）"
                                    Toast.makeText(ctx, "已恢复默认背景", Toast.LENGTH_SHORT).show()
                                }) { Text("恢复默认") }
                        }
                        // 当前背景状态如实展示：是否已设自定义背景、导入是否失败及原因
                        Text(
                            when {
                                bgStatus.isNotEmpty() -> bgStatus
                                customBg.isNotEmpty() -> "当前：已使用自定义背景（动画已关闭）"
                                else -> "当前：默认像素背景（随时间变化）"
                            },
                            fontSize = 11.sp,
                            color = if (bgStatus.startsWith("未生效")) Color(0xFFC0392B) else Color(0xFF8A6B7A)
                        )
                        Text("按键颜色", fontSize = 14.sp)
                        val colors = listOf(
                            0 to "默认", 0xFFE86AC0.toInt() to "粉", 0xFFFF8A9E.toInt() to "桃",
                            0xFFE5533D.toInt() to "红", 0xFFFF9F45.toInt() to "橙", 0xFFFFD54D.toInt() to "黄",
                            0xFFC9E265.toInt() to "柠绿", 0xFF7ED9A5.toInt() to "绿", 0xFF3FBFA0.toInt() to "青碧",
                            0xFF6ED3E8.toInt() to "湖蓝", 0xFF7EC8F7.toInt() to "天蓝", 0xFF4F7DF3.toInt() to "宝蓝",
                            0xFF9C8CFF.toInt() to "淡紫", 0xFF7A5AF8.toInt() to "紫", 0xFFD96AA7.toInt() to "玫红",
                            0xFF8D6E63.toInt() to "棕", 0xFF90A4AE.toInt() to "灰蓝", 0xFF37474F.toInt() to "墨"
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
                        Text("音效包（点击切换并试听）", fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                            for ((v, label) in listOf(0 to "清脆", 1 to "机械", 2 to "泡泡")) {
                                Button(
                                    colors = ButtonDefaults.buttonColors(
                                        // 自定义音效生效时不选中任何内置包
                                        containerColor = if (pack == v && customSound.isEmpty()) Color(0xFFB23A8F) else Color(0xFFE5C9D8)),
                                    onClick = {
                                        AppPrefs.setSoundPack(v)
                                        soundStatus = ""
                                        if (!AppPrefs.soundOn) {
                                            AppPrefs.soundOn = true
                                            sound = true
                                        }
                                        SoundPreview.play(ctx, v)
                                    }
                                ) { Text(label, fontSize = 12.sp) }
                            }
                        }
                        // 自定义音效：上传后仅替换点按音，空格/删除仍用内置
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("自定义点按音效", fontSize = 14.sp)
                            Spacer(Modifier.weight(1f))
                            Button(onClick = soundPicker) { Text("上传音效") }
                            if (customSound.isNotEmpty()) {
                                Button(colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD9B8C9)),
                                    onClick = {
                                        MediaImporter.clearCustomSound(ctx)
                                        AppPrefs.setCustomSound("")
                                        soundStatus = "已恢复内置音效包"
                                        SoundPreview.release()
                                    }) { Text("恢复内置") }
                            }
                        }
                        Text(
                            when {
                                soundStatus.isNotEmpty() -> soundStatus
                                customSound.isNotEmpty() -> "当前：自定义音效 ${File(customSound).name}"
                                else -> "当前：内置音效包（支持 wav/mp3/ogg/m4a/aac/flac，建议 1 秒内短音）"
                            },
                            fontSize = 11.sp,
                            color = if (soundStatus.startsWith("未生效")) Color(0xFFC0392B) else Color(0xFF8A6B7A)
                        )
                        SwitchRow("按键振动", vib) { vib = it; AppPrefs.vibrateOn = it }
                        SwitchRow("联网增强词库（提升词汇准确率，默认开）", net) { net = it; AppPrefs.netBoost = it }

                        Spacer(Modifier.height(10.dp))
                        Section("语音模型（安装后从镜像源下载）")
                        val mStatus by com.mengting.ime.feature.voice.ModelStore.status.collectAsState()
                        Text(
                            when (val s = mStatus) {
                                is com.mengting.ime.feature.voice.ModelStore.Status.Ready -> "语音模型已就绪，长按空格即可语音输入"
                                is com.mengting.ime.feature.voice.ModelStore.Status.Downloading -> "语音模型下载中 ${s.percent}%（后台进行，可退出页面）"
                                is com.mengting.ime.feature.voice.ModelStore.Status.Failed -> s.msg
                                else -> "语音模型尚未下载（约 230MB，国内镜像直连）"
                            },
                            fontSize = 12.sp, color = Color(0xFF6B5670)
                        )
                        if (mStatus !is com.mengting.ime.feature.voice.ModelStore.Status.Ready &&
                            mStatus !is com.mengting.ime.feature.voice.ModelStore.Status.Downloading
                        ) {
                            Button(
                                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                                onClick = { com.mengting.ime.feature.voice.ModelStore.download(ctx) }
                            ) { Text("下载语音模型") }
                        }
                    }
                    3 -> {
                        // ===== 副页：单手模式 =====
                        Section("单手模式")
                        Text("键盘底部「单手」键开启；开启后键盘收窄偏向一侧，按键纵向排列便于单手握持。",
                            fontSize = 12.sp, color = Color(0xFF6B5670))
                        Spacer(Modifier.height(8.dp))
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
                        Text("提示：选择「重力感应」时，左右倾斜手机即可实时切换偏向；无倾斜时默认偏右。",
                            fontSize = 11.sp, color = Color(0xFF8A6B7A))
                    }
                    else -> {
                        // ===== 主页 =====
                        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("梦婷输入法", fontSize = 26.sp, fontWeight = FontWeight.Black, color = Color(0xFFB23A8F))
                            Text(updateMsg, fontSize = 12.sp, color = Color(0xFF8A6B7A), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        }
                        Spacer(Modifier.height(14.dp))

                        MainCard("🚀 前置准备",
                            subtitle = { Text("启用输入法并设为默认（首次使用必读）", fontSize = 12.sp, color = Color(0xFF6B5670)) },
                            onClick = { subPage = 1 })

                        MainCard("⌨ 键盘设置",
                            subtitle = { Text("键盘外观、按键颜色、音效振动、联网增强", fontSize = 12.sp, color = Color(0xFF6B5670)) },
                            onClick = { subPage = 2 })

                        MainCard("🖐 单手模式",
                            subtitle = { Text("默认偏向、重力感应与逆转设置", fontSize = 12.sp, color = Color(0xFF6B5670)) },
                            onClick = { subPage = 3 })

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
                        if (showBrowserFallback) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
                                Button(
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8D6E63)),
                                    onClick = { UpdateChecker.openInBrowser(ctx) }
                                ) { Text("浏览器打开下载页", fontSize = 12.sp) }
                                Button(
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD9B8C9)),
                                    onClick = { showBrowserFallback = false }
                                ) { Text("关闭", fontSize = 12.sp) }
                            }
                        }
                        Text("更新源：GitHub 仓库 sml1145/jianpan 最新发布（四通道自动容错）",
                            fontSize = 11.sp, color = Color(0xFF8A6B7A))

                        Spacer(Modifier.height(10.dp))
                        Section("桌面小组件")
                        Text("「梦婷打字统计」组件：今日码字数 + 情绪占比，每天凌晨 5 点刷新。", fontSize = 12.sp, color = Color(0xFF6B5670))
                        Button(
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                            onClick = {
                                pinWidget()
                                showWidgetHelp = true
                            }
                        ) { Text("一键添加到桌面") }
                        Text("没弹出确认框？点这里查看手动添加教程", fontSize = 11.sp, color = Color(0xFFB23A8F),
                            modifier = Modifier.padding(top = 6.dp).clickable { showWidgetHelp = true })

                        Spacer(Modifier.height(10.dp))
                        // 应用内组件预览：不添加桌面组件也能看到数据
                        Box(
                            Modifier.fillMaxWidth()
                                .background(Color(0xF2FFFFFF), RoundedCornerShape(16.dp))
                                .border(1.dp, Color(0x33B23A8F), RoundedCornerShape(16.dp))
                                .padding(16.dp)
                        ) {
                            Column {
                                Text("梦婷输入法", fontSize = 12.sp, color = Color(0xFFB23A8F), fontWeight = FontWeight.Bold)
                                Spacer(Modifier.height(6.dp))
                                Text("今天码了 ${widgetChars} 字", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFF3A2440))
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    "开心的内容占比 ${widgetMood.first}%，不开心的内容占比 ${widgetMood.second}%，其他内容占比 ${widgetMood.third}%",
                                    fontSize = 12.sp, color = Color(0xFF6B5670)
                                )
                                Spacer(Modifier.height(4.dp))
                                Text("每日凌晨 5 点刷新（应用内预览，与桌面组件数据一致）", fontSize = 10.sp, color = Color(0x996B5670))
                            }
                        }

                        Spacer(Modifier.height(24.dp))
                    }
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
        // 组件添加教程弹窗
        if (showWidgetHelp) {
            AlertDialog(
                onDismissRequest = { showWidgetHelp = false },
                title = { Text("添加桌面组件", fontWeight = FontWeight.Bold) },
                text = {
                    Text(
                        "部分桌面（尤其国产 ROM）会拦截「一键添加」的确认弹窗，或需要先在桌面设置里允许。如果没弹出确认框，请手动添加：\n\n" +
                        "1. 长按桌面空白处\n" +
                        "2. 点「小组件 / 窗口小部件 / 原子组件」\n" +
                        "3. 找到「梦婷输入法」分类\n" +
                        "4. 长按「梦婷打字统计」拖到桌面即可\n\n" +
                        "组件数据每天凌晨 5 点自动刷新。"
                    )
                },
                confirmButton = { TextButton(onClick = { showWidgetHelp = false }) { Text("知道了") } }
            )
        }
    }

    @Composable
    private fun MainCard(title: String, subtitle: @Composable () -> Unit, onClick: () -> Unit) {
        Box(
            Modifier.fillMaxWidth().padding(vertical = 5.dp)
                .background(Color(0xF2FFFFFF), RoundedCornerShape(14.dp))
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
