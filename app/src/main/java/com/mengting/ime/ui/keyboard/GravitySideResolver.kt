package com.mengting.ime.ui.keyboard

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.mengting.ime.core.AppPrefs

/**
 * 单手模式偏向解析：
 *  - 重力感应：左边朝下偏左，右边朝下偏右，感应不到默认偏右
 *  - 设置中可固定默认侧或逆转映射
 */
class GravitySideResolver(context: Context) : SensorEventListener {
    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private var gx = 0f
    private var hasReading = false

    init {
        sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sm.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            gx = event.values[0]
            hasReading = Math.abs(gx) > 1.2f
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    /** true = 偏左 */
    fun isLeft(): Boolean {
        val fixed = AppPrefs.singleHandSide
        val gravityLeft = if (hasReading) gx < -1.2f else false // x 负 = 左侧朝下
        val neutralRight = !hasReading
        var left = when {
            fixed == 1 -> true
            fixed == 2 -> false
            neutralRight -> false
            else -> gravityLeft
        }
        if (AppPrefs.singleHandInvert) left = !left
        return left
    }

    fun unregister() = sm?.unregisterListener(this)
}
