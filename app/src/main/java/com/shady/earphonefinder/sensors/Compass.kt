package com.shady.earphonefinder.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.atan2

/**
 * Which way the user is facing, in compass degrees (0 = north, 90 = east).
 *
 * Works whether the phone is flat, tilted or upright: it adds the horizontal
 * direction of the phone's top edge and of the phone's back. In a normal "holding
 * it in front of me" grip both point away from your chest, so the sum does too.
 */
class Compass(context: Context, private val onHeading: (Double) -> Unit) : SensorEventListener {
    private val sm = context.getSystemService(SensorManager::class.java)
    private val sensor: Sensor? = sm?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val r = FloatArray(9)

    val available: Boolean get() = sensor != null

    private var running = false

    fun start() {
        if (running) return
        sensor?.let { running = sm?.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) == true }
    }

    fun stop() {
        sm?.unregisterListener(this)
        running = false
    }

    override fun onSensorChanged(event: SensorEvent) {
        SensorManager.getRotationMatrixFromVector(r, event.values)
        // Columns of r are the phone's axes in world coords (x = east, y = north, z = up).
        // Phone top edge = column 1, phone back = minus column 2.
        val east = r[1] - r[2]
        val north = r[4] - r[5]
        val deg = Math.toDegrees(atan2(east.toDouble(), north.toDouble()))
        onHeading((deg + 360.0) % 360.0)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
