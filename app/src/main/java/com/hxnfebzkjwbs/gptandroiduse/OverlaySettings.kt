package com.hxnfebzkjwbs.gptandroiduse

import android.content.Context

object OverlaySettings {
    private const val PREFS = "overlay_settings"
    private const val KEY_OPACITY_PERCENT = "opacity_percent"
    private const val DEFAULT_OPACITY_PERCENT = 1
    private const val KEY_COMPATIBILITY_OVERLAY = "compatibility_overlay"

    fun getOpacityPercent(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_OPACITY_PERCENT, DEFAULT_OPACITY_PERCENT)
            .coerceIn(1, 100)

    fun getOpacity(context: Context): Float =
        getOpacityPercent(context) / 100f

    fun isCompatibilityOverlayEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_COMPATIBILITY_OVERLAY, false)

    fun setCompatibilityOverlayEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_COMPATIBILITY_OVERLAY, enabled)
            .apply()
    }

    fun setOpacityPercent(context: Context, percent: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_OPACITY_PERCENT, percent.coerceIn(1, 100))
            .apply()
    }

    fun getIconStyle(context: Context): String =
        normalizeIconStyle(
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_ICON_STYLE, STYLE_RING)
                .orEmpty()
        )

    fun setIconStyle(context: Context, style: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ICON_STYLE, normalizeIconStyle(style))
            .apply()
    }

    fun normalizeIconStyle(style: String): String =
        when (style) {
            STYLE_RING,
            STYLE_ORBIT,
            STYLE_ROBOT,
            STYLE_MINIMAL -> style
            else -> STYLE_RING
        }

    const val STYLE_RING = "ring"
    const val STYLE_ORBIT = "orbit"
    const val STYLE_ROBOT = "robot"
    const val STYLE_MINIMAL = "minimal"
    private const val KEY_ICON_STYLE = "icon_style"
}
