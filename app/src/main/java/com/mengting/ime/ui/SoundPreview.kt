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

    /**
     * 试听用户自定义音效文件（绝对路径）。
     * 与内置包试听分开，因为音源是文件而非资源；加载完成后立即播放一声，
     * 让用户在上传当场就能听到效果，避免「上传了却没反应」的空壳体验。
     */
    fun playFile(ctx: Context, path: String) {
        release()
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val sp = SoundPool.Builder().setMaxStreams(2).setAudioAttributes(attrs).build()
        val id = sp.load(path, 1)
        sp.setOnLoadCompleteListener { _, sampleId, status ->
            if (status == 0 && sampleId == id) {
                sp.play(id, 1f, 1f, 1, 0, 1f)
            } else if (status != 0) {
                android.widget.Toast.makeText(
                    ctx, "该音频无法播放（格式可能不受支持），已保留设置但打字时会自动用内置音效", 
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
        }
        pool = sp
        ids = intArrayOf(id)
        loadedPack = -2 // 标记为文件源，避免与内置包 id 冲突
    }

    fun release() {
        pool?.release()
        pool = null
        ids = null
        loadedPack = -1
    }
}
