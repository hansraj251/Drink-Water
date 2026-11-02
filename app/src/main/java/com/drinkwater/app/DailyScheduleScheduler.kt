package com.drinkwater.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.Calendar

/** Daily time window is inclusive at both ends. Equal times mean a 24-hour window. */
object DailyScheduleScheduler {
    private const val REQUEST_CODE = 100
    private fun pending(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, REQUEST_CODE, Intent(context, WaterReminderReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    fun cancel(context: Context) {
        (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pending(context))
        context.getSharedPreferences("water", Context.MODE_PRIVATE)
            .edit().remove("next_reminder_at").apply()
    }

    fun scheduleNext(context: Context, afterFiring: Boolean = false) {
        val prefs = context.getSharedPreferences("water", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("active", false)) { cancel(context); return }
        val interval = prefs.getInt("interval", 30).coerceIn(1, 1440)
        val start = prefs.getInt("start_minute", 420).coerceIn(0, 1439)
        val end = prefs.getInt("end_minute", 1320).coerceIn(0, 1439)
        val now = System.currentTimeMillis()
        val today = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        // Enumerate local-calendar days to respect daylight-saving changes.
        var next: Long? = null
        for (dayOffset in -1..2) {
            val day = (today.clone() as Calendar).apply { add(Calendar.DATE, dayOffset) }
            val startCal = (day.clone() as Calendar).apply { add(Calendar.MINUTE, start) }
            val endCal = (day.clone() as Calendar).apply {
                add(Calendar.MINUTE, end)
                if (end < start) add(Calendar.DATE, 1)
                if (end == start) add(Calendar.DATE, 1)
            }
            val endMillis = endCal.timeInMillis
            val step = interval * 60_000L
            var candidate = startCal.timeInMillis
            if (candidate <= now) {
                val elapsed = now - candidate
                candidate += (elapsed / step + 1) * step
            }
            if (candidate <= endMillis && (next == null || candidate < next)) next = candidate
        }
        if (next == null) {
            prefs.edit().remove("next_reminder_at").apply()
            return
        }
        prefs.edit().putLong("next_reminder_at", next).apply()
        val manager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = pending(context)
        manager.cancel(intent)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager.canScheduleExactAlarms()) {
                    manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, intent)
                } else {
                    manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, intent)
                }
            } else {
                manager.setExact(AlarmManager.RTC_WAKEUP, next, intent)
            }
        } catch (_: SecurityException) {
            manager.set(AlarmManager.RTC_WAKEUP, next, intent)
        }
    }
}
