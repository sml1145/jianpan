package com.mengting.ime.ui.keyboard

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mengting.ime.core.AppPrefs

/**
 * 单手模式偏向解析（响应式）。
 * 加速度计 x 轴：设备左侧朝下时 gx 为正值（-g 投影），右侧朝下为负值。
 * 感应不到明显倾斜时默认偏右；可在设置中固定侧或逆转映射。
 */
class GravitySideResolver(context: Context) : SensorEventListener {
    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

    /** 当前重力 x 分量（Compose 可观察） */
    var gx by mutableFloatStateOf(0f)
        private set
    var hasReading by mutableStateOf(false)
        private set

    init {
        sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            gx = event.values[0]
            hasReading = Math.abs(gx) > 2.0f
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    /** true = 偏左（读取时实时计算，随传感器状态自动重组） */
    fun isLeft(): Boolean {
        val fixed = AppPrefs.singleHandSide
        // 左侧朝下 → gx > 0（设备坐标系 x 指向右，重力反作用力投影为正）
        val gravityLeft = gx > 2.0f
        var left = when (fixed) {
            1 -> true
            2 -> false
            else -> if (hasReading) gravityLeft else false // 感应不到默认偏右
        }
        if (AppPrefs.singleHandInvert) left = !left
        return left
    }

    fun unregister() = sm?.unregisterListener(this)
}
