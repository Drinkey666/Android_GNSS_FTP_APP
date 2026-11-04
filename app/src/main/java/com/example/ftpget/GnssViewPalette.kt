package com.example.ftpget

import android.graphics.Color

/** Theme colors passed from Compose to the retained GNSS Views. */
data class GnssViewPalette(
    val dark: Boolean,
    val background: Int,
    val foreground: Int,
    val muted: Int,
    val grid: Int,
    val header: Int,
    val alternate: Int,
    val success: Int,
    val satelliteText: Int,
) {
    companion object {
        fun initial() = GnssViewPalette(
            dark = false,
            background = Color.WHITE,
            foreground = Color.rgb(35, 38, 48),
            muted = Color.rgb(94, 99, 112),
            grid = Color.rgb(220, 222, 230),
            header = Color.rgb(240, 240, 245),
            alternate = Color.rgb(249, 249, 252),
            success = Color.rgb(0, 110, 91),
            satelliteText = Color.WHITE,
        )
    }
}
