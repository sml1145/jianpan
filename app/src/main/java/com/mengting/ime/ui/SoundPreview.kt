package com.mengting.ime.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import com.mengting.ime.R

/** 音效包试听：切换音效包时立即播放一声对应音效 */
object SoundPreview {
    private var pool: SoundPool? = null
    private var ids: IntArray? = null
    private var loadedPack = -1

    fun play(ctx: Context, pack: Int) {
        val res = when (pack) {
            1 -> R.raw.key_tap_mech
            2 -> R.raw.key_tap_bubble
            else -> R.raw.key_tap
        }
        val p = pool
        if (p != null && loadedPack == pack && ids != null) {
            p.play(ids!![0], 1f, 1f, 1, 0, 1f)
            return
        }
        release()
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val sp = SoundPool.Builder().setMaxStreams(2).setAudioAttributes(attrs).build()
        val id = sp.load(ctx, res, 1)
        sp.setOnLoadCompleteListener { _, sampleId, status ->
            if (status == 0 && sampleId == id) sp.play(id, 1f, 1f, 1, 0, 1f)
        }
        pool = sp
        ids = intArrayOf(id)
        loadedPack = pack
    }

    fun release() {
        pool?.release()
        pool = null
        ids = null
        loadedPack = -1
    }
}
