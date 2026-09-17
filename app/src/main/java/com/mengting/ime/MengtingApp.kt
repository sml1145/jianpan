package com.mengting.ime

import android.app.Application
import com.mengting.ime.core.AppPrefs
import com.mengting.ime.core.EnglishEngine
import com.mengting.ime.core.HotWordStore
import com.mengting.ime.core.PinyinEngine
import com.mengting.ime.core.TypingStats
import com.mengting.ime.feature.sms.SmsCodeHolder
import com.mengting.ime.feature.voice.ModelStore
import com.mengting.ime.feature.voice.VoiceInputController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MengtingApp : Application() {
    companion object {
        lateinit var instance: MengtingApp
            private set
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        AppPrefs.init(this)
        TypingStats.init(this)
        SmsCodeHolder.register(this)
        scope.launch { PinyinEngine.ensureLoaded(this@MengtingApp) }
        scope.launch { EnglishEngine.ensureLoaded(this@MengtingApp) }
        // 联网增强：开启时从仓库 CDN 拉取热词合并进候选（失败静默，用本地缓存）
        if (AppPrefs.netBoost) {
            scope.launch {
                // 等词典首批就绪再合并热词
                var wait = 0
                while (!PinyinEngine.ready && wait < 100) { delay(100); wait++ }
                HotWordStore.sync(this@MengtingApp)
            }
        }
        // 语音模型外置：启动即后台从镜像源拉取（幂等，已就绪则跳过）
        ModelStore.refresh(this)
        ModelStore.download(this)
    }
}
