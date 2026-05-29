package com.example.localmovielibrary.playback.vr

import android.content.Context
import java.security.MessageDigest

class VrModeSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getMode(mediaKey: String): VrMode =
        VrMode.fromName(prefs.getString(modeKey(mediaKey), null))

    fun saveMode(mediaKey: String, mode: VrMode) {
        prefs.edit().putString(modeKey(mediaKey), mode.name).apply()
    }

    fun getControlMode(mediaKey: String): VrControlMode =
        VrControlMode.fromName(prefs.getString(controlKey(mediaKey), null))

    fun saveControlMode(mediaKey: String, mode: VrControlMode) {
        prefs.edit().putString(controlKey(mediaKey), mode.name).apply()
    }

    private fun modeKey(mediaKey: String): String = "vr_mode_${mediaKey.stableHash()}"

    private fun controlKey(mediaKey: String): String = "vr_control_${mediaKey.stableHash()}"

    private fun String.stableHash(): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val PREFS_NAME = "vr_mode_settings"
    }
}
