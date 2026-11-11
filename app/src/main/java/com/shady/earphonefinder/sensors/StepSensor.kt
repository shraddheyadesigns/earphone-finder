package com.shady.earphonefinder.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

/** Fires once per step. Needs ACTIVITY_RECOGNITION on Android 10+. */
class StepSensor(context: Context, private val onStep: () -> Unit) : SensorEventListener {
    private val sm = context.getSystemService(SensorManager::class.java)
    private val sensor: Sensor? = sm?.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)

    val available: Boolean get() = sensor != null

    private var running = false

    fun start() {
        if (running) return
        sensor?.let { running = sm?.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST) == true }
    }

    fun stop() {
        sm?.unregisterListener(this)
        running = false
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_STEP_DETECTOR) onStep()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
