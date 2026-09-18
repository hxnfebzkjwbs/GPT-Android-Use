package com.hxnfebzkjwbs.gptandroiduse

import android.content.Context
import android.content.Intent
import android.provider.Settings

object DeveloperSettings {
    fun openDeveloperOptions(context: Context) {
        context.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun openWirelessDebugging(context: Context): Boolean {
        val candidates = listOf(
            Intent("android.settings.WIRELESS_DEBUGGING_SETTINGS"),
            Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
        )
        val pm = context.packageManager
        val intent = candidates.firstOrNull { it.resolveActivity(pm) != null } ?: return false
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }
}
