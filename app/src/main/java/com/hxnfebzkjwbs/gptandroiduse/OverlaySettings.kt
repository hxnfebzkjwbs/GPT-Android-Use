package com.hxnfebzkjwbs.gptandroiduse

import android.content.Context

object OverlaySettings {
    private const val PREFS = "overlay_settings"
    private const val KEY_OPACITY_PERCENT = "opacity_percent"
    private const val DEFAULT_OPACITY_PERCENT = 1

    fun getOpacityPercent(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_OPACITY_PERCENT, DEFAULT_OPACITY_PERCENT)
            .coerceIn(1, 100)

    fun getOpacity(context: Context): Float =
        getOpacityPercent(context) / 100f

    fun setOpacityPercent(context: Context, percent: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_OPACITY_PERCENT, percent.coerceIn(1, 100))
            .apply()
    }
}
