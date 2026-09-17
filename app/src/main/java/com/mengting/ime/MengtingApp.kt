package com.mengting.ime

import android.app.Application
import com.mengting.ime.core.AppPrefs
import com.mengting.ime.core.EnglishEngine
import com.mengting.ime.core.PinyinEngine
import com.mengting.ime.core.TypingStats
import com.mengting.ime.feature.sms.SmsCodeHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
    }
}
