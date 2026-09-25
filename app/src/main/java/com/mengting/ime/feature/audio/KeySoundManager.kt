package com.mengting.ime.feature.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import com.mengting.ime.R
import com.mengting.ime.core.AppPrefs
import java.io.File

/**
 * 按键音效：走通知音通道（USAGE_NOTIFICATION），免打扰时系统自动屏蔽。
 *
 * 支持两类音源（互斥）：
 *  - 内置音效包（`AppPrefs.soundPack`：0=清脆 1=机械 2=泡泡），点按音随包切换；
 *  - 用户自定义音效（`AppPrefs.customSoundPath`，已由 MediaImporter 复制进私有目录），
 *    仅替换点按音，空格/删除仍用内置 —— 用户只上传一个文件，不该被要求配三种音效。
 *
 * **为什么必须等 OnLoadCompleteListener（按 sampleId 精确跟踪）**：
 * `SoundPool.load()` 是异步的，返回的 sampleId 在加载完成回调前**不可播放**，
 * 此时 `play()` 静默返回 0（不报错、也没声音）。原实现没有监听器就赋值 pool 返回，
 * 冷启动或换音效包后的首批按键会无声。
 *
 * 更隐蔽的坑：自定义音频若格式不受支持，`load()` 仍返回非 0 的 id，失败是**异步**
 * 通过监听器 status!=0 报出的。若只看「有没有 sample 就绪」，点按音坏了也不会被发现，
 * 用户就会遇到「明明选了自定义音效却完全没声音」。因此这里按 id 记录就绪状态，
 * 点按音加载失败时自动改用内置音效包，保证任何情况下按键都有声音反馈。
 */
class KeySoundManager(private val ctx: Context) {
    companion object {
        const val KIND_TAP = 0
        const val KIND_SPACE = 1
        const val KIND_DELETE = 2
    }

    private var pool: SoundPool? = null
    private val ids = IntArray(3)
    /** 已完成加载、可以播放的 sampleId 集合 */
    private val readyIds = HashSet<Int>()
    private var loadedPack = -1
    private var loadedCustom: String? = null
    /** 点按音当前用的是自定义音效还是内置包（用于失败回退判断） */
    private var tapIsCustom = false

    private fun tapResForPack(pack: Int) = when (pack) {
        1 -> R.raw.key_tap_mech
        2 -> R.raw.key_tap_bubble
        else -> R.raw.key_tap
    }

    /** 当前应使用的自定义音效文件；不存在或为空则返回 null（走内置包） */
    private fun validCustomPath(): String? {
        val p = AppPrefs.customSoundPath.value
        if (p.isEmpty()) return null
        val f = File(p)
        return if (f.exists() && f.length() > 0) p else null
    }

    /**
     * 加载音效。幂等：音效包与自定义音效都没变时直接返回；变更后自动重载。
     */
    fun load() {
        val pack = AppPrefs.soundPack.value
        val custom = validCustomPath()
        if (pool != null && loadedPack == pack && loadedCustom == custom) return
        release()

        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val p = SoundPool.Builder().setMaxStreams(4).setAudioAttributes(attrs).build()

        p.setOnLoadCompleteListener { _, sampleId, status ->
            synchronized(readyIds) {
                if (status == 0) {
                    readyIds.add(sampleId)
                } else {
                    // 自定义点按音异步加载失败 → 回退内置音效包，保证按键始终有声
                    if (tapIsCustom && sampleId == ids[KIND_TAP]) {
                        android.util.Log.w("MTSound", "custom tap sound failed(status=$status), fallback to builtin")
                        tapIsCustom = false
                        val fallback = p.load(ctx, tapResForPack(loadedPack.coerceAtLeast(0)), 1)
                        ids[KIND_TAP] = fallback
                    } else {
                        android.util.Log.w("MTSound", "sample $sampleId load failed, status=$status")
                    }
                }
            }
        }

        val customTapId = if (custom != null) p.load(custom, 1) else 0
        tapIsCustom = customTapId != 0
        ids[KIND_TAP] = if (tapIsCustom) customTapId else p.load(ctx, tapResForPack(pack), 1)
        ids[KIND_SPACE] = p.load(ctx, R.raw.key_space, 1)
        ids[KIND_DELETE] = p.load(ctx, R.raw.key_delete, 1)

        pool = p
        loadedPack = pack
        loadedCustom = custom
    }

    fun play(kind: Int) {
        val p = pool ?: return
        if (kind !in ids.indices) return
        val id = ids[kind]
        if (id == 0) return
        // 该 sample 还没加载完成时 play() 会静默失败，直接跳过；
        // 加载通常在数十毫秒内完成，用户几乎察觉不到首批按键被跳过。
        val ready = synchronized(readyIds) { readyIds.contains(id) }
        if (!ready) return
        p.play(id, 0.6f, 0.6f, 1, 0, 1f)
    }

    /**
     * 诊断/测试用：点按音 sample 是否已加载就绪、可立即播放。
     * 用于验证「自定义音效确实加载成功」，避免又出现上传了却没声音的空壳体验。
     */
    fun isTapSampleReady(): Boolean {
        val id = ids[KIND_TAP]
        return id != 0 && synchronized(readyIds) { readyIds.contains(id) }
    }

    fun release() {
        try { pool?.setOnLoadCompleteListener(null) } catch (_: Exception) {}
        pool?.release()
        pool = null
        loadedPack = -1
        loadedCustom = null
        tapIsCustom = false
        synchronized(readyIds) { readyIds.clear() }
        for (i in ids.indices) ids[i] = 0
    }
}
