package com.drinkwater.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return

        val prefs = context.getSharedPreferences("water", Context.MODE_PRIVATE)
        if (prefs.getBoolean("active", false)) {
            scheduleReminder(context, prefs.getInt("interval", 30))
        }
    }
}
