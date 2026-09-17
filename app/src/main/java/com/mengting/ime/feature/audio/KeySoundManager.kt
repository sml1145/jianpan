package com.mengting.ime.feature.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import com.mengting.ime.R
import com.mengting.ime.core.AppPrefs

/**
 * 按键音效：走通知音通道（USAGE_NOTIFICATION），免打扰时系统自动屏蔽。
 * 支持三种音效包：0=清脆 1=机械 2=泡泡。
 */
class KeySoundManager(private val ctx: Context) {
    companion object {
        const val KIND_TAP = 0
        const val KIND_SPACE = 1
        const val KIND_DELETE = 2
    }

    private var pool: SoundPool? = null
    private val ids = IntArray(3)
    private var loadedPack = -1

    private fun tapResForPack(pack: Int) = when (pack) {
        1 -> R.raw.key_tap_mech
        2 -> R.raw.key_tap_bubble
        else -> R.raw.key_tap
    }

    fun load() {
        val pack = AppPrefs.soundPack
        if (pool != null && loadedPack == pack) return
        release()
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val p = SoundPool.Builder().setMaxStreams(4).setAudioAttributes(attrs).build()
        ids[KIND_TAP] = p.load(ctx, tapResForPack(pack), 1)
        ids[KIND_SPACE] = p.load(ctx, R.raw.key_space, 1)
        ids[KIND_DELETE] = p.load(ctx, R.raw.key_delete, 1)
        pool = p
        loadedPack = pack
    }

    fun play(kind: Int) {
        val p = pool ?: return
        if (kind in ids.indices && ids[kind] != 0) p.play(ids[kind], 0.6f, 0.6f, 1, 0, 1f)
    }

    fun release() {
        pool?.release()
        pool = null
        loadedPack = -1
    }
}
