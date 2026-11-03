package com.drinkwater.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.Calendar

/**
 * Schedules reminders only inside the user's daily time window.
 * Equal start/end times represent a 24-hour window.
 */
object DailyScheduleScheduler {

    private const val REQUEST_CODE = 100
    private const val RECOVERY_GRACE_MS = 2 * 60_000L

    private fun pending(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            Intent(context, WaterReminderReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    fun cancel(context: Context) {
        val manager =
            context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        manager.cancel(pending(context))

        context.getSharedPreferences("water", Context.MODE_PRIVATE)
            .edit()
            .remove("next_reminder_at")
            .apply()
    }

    /**
     * Repairs a missing or significantly overdue schedule.
     * A 2-minute grace period avoids racing the receiver at firing time.
     */
    fun ensureNextScheduled(context: Context) {
        val prefs = context.getSharedPreferences("water", Context.MODE_PRIVATE)

        if (!prefs.getBoolean("active", false)) {
            cancel(context)
            return
        }

        val savedTime = prefs.getLong("next_reminder_at", 0L)
        val now = System.currentTimeMillis()

        if (savedTime <= 0L || savedTime < now - RECOVERY_GRACE_MS) {
            scheduleNext(context)
        }
    }

    fun scheduleNext(context: Context) {
        val prefs = context.getSharedPreferences("water", Context.MODE_PRIVATE)

        if (!prefs.getBoolean("active", false)) {
            cancel(context)
            return
        }

        val interval = prefs.getInt("interval", 30).coerceIn(1, 1440)
        val start = prefs.getInt("start_minute", 420).coerceIn(0, 1439)
        val end = prefs.getInt("end_minute", 1320).coerceIn(0, 1439)

        val now = System.currentTimeMillis()

        val today = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        val step = interval * 60_000L
        var next: Long? = null

        // Yesterday is included for overnight windows.
        // Two future days ensure the next valid window can be found.
        for (dayOffset in -1..2) {
            val day = (today.clone() as Calendar).apply {
                add(Calendar.DATE, dayOffset)
            }

            val windowStart = (day.clone() as Calendar).apply {
                add(Calendar.MINUTE, start)
            }

            val windowEnd = (day.clone() as Calendar).apply {
                add(Calendar.MINUTE, end)

                if (end <= start) {
                    add(Calendar.DATE, 1)
                }
            }

            val startMillis = windowStart.timeInMillis
            val endMillis = windowEnd.timeInMillis

            var candidate = startMillis

            // Select the first interval strictly after the current time.
            if (candidate <= now) {
                val elapsed = now - candidate
                val stepsToAdvance = elapsed / step + 1L
                candidate += stepsToAdvance * step
            }

            // Both ends of the daily window are inclusive.
            if (candidate <= endMillis &&
                (next == null || candidate < next)
            ) {
                next = candidate
            }
        }

        if (next == null || next <= now) {
            cancel(context)
            return
        }

        val manager =
            context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val alarmIntent = pending(context)

        // Remove any previous alarm before installing the new one.
        manager.cancel(alarmIntent)

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                    manager.canScheduleExactAlarms()
                ) {
                    manager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        next,
                        alarmIntent
                    )
                } else {
                    manager.setAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        next,
                        alarmIntent
                    )
                }
            } else {
                manager.setExact(
                    AlarmManager.RTC_WAKEUP,
                    next,
                    alarmIntent
                )
            }

            // Save only after AlarmManager accepts the request.
            prefs.edit()
                .putLong("next_reminder_at", next)
                .apply()

        } catch (_: SecurityException) {
            // Retry with an inexact alarm if exact scheduling is denied.
            try {
                manager.set(
                    AlarmManager.RTC_WAKEUP,
                    next,
                    alarmIntent
                )

                prefs.edit()
                    .putLong("next_reminder_at", next)
                    .apply()

            } catch (_: SecurityException) {
                manager.cancel(alarmIntent)
                prefs.edit()
                    .remove("next_reminder_at")
                    .apply()
            }
        }
    }
}
