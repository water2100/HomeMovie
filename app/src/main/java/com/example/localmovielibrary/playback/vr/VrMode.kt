package com.example.localmovielibrary.playback.vr

import androidx.media3.common.C

enum class VrMode(
    val label: String,
    val stereoMode: Int
) {
    Normal2D("普通 2D", C.STEREO_MODE_MONO),
    Vr360("360°", C.STEREO_MODE_MONO),
    Vr180("180°", C.STEREO_MODE_MONO),
    Vr360Sbs("360° SBS 左右", C.STEREO_MODE_LEFT_RIGHT),
    Vr180Sbs("180° SBS 左右", C.STEREO_MODE_LEFT_RIGHT),
    Vr360Ou("360° OU 上下", C.STEREO_MODE_TOP_BOTTOM),
    Vr180Ou("180° OU 上下", C.STEREO_MODE_TOP_BOTTOM);

    val isVr: Boolean
        get() = this != Normal2D

    companion object {
        fun fromName(value: String?): VrMode =
            entries.firstOrNull { it.name == value } ?: Normal2D
    }
}
