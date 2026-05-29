package com.example.localmovielibrary.playback.vr

enum class VrControlMode(val label: String) {
    Touch("手指拖动"),
    Sensor("陀螺仪"),
    TouchAndSensor("手指 + 陀螺仪");

    val useSensor: Boolean
        get() = this == Sensor || this == TouchAndSensor

    companion object {
        fun fromName(value: String?): VrControlMode =
            entries.firstOrNull { it.name == value } ?: TouchAndSensor
    }
}
