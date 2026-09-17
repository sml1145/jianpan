package com.mengting.ime.feature.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import com.mengting.ime.R

/**
 * 按键音效：走通知音通道（USAGE_NOTIFICATION），
 * 因此用户开启免打扰时系统会自动屏蔽，符合需求。
 */
class KeySoundManager(private val ctx: Context) {
    companion object {
        const val KIND_TAP = 0
        const val KIND_SPACE = 1
        const val KIND_DELETE = 2
    }

    private var pool: SoundPool? = null
    private val ids = IntArray(3)

    fun load() {
        if (pool != null) return
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val p = SoundPool.Builder().setMaxStreams(4).setAudioAttributes(attrs).build()
        ids[KIND_TAP] = p.load(ctx, R.raw.key_tap, 1)
        ids[KIND_SPACE] = p.load(ctx, R.raw.key_space, 1)
        ids[KIND_DELETE] = p.load(ctx, R.raw.key_delete, 1)
        pool = p
    }

    fun play(kind: Int) {
        val p = pool ?: return
        if (kind in ids.indices && ids[kind] != 0) p.play(ids[kind], 0.6f, 0.6f, 1, 0, 1f)
    }

    fun release() {
        pool?.release()
        pool = null
    }
}
