package com.drinkwater.app

import android.Manifest
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

class WaterReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val prefs = context.getSharedPreferences("water", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("active", false)) return

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) return

        createNotificationChannel(context)

        val notification = NotificationCompat.Builder(
            context, "water_reminders"
        )
            .setSmallIcon(R.drawable.ic_notification_hidden)
            .setContentTitle("Time to drink water!")
            .setContentText("Take a short break and stay hydrated.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        val manager = context.getSystemService(
            Context.NOTIFICATION_SERVICE
        ) as NotificationManager

        manager.notify(200, notification)
        scheduleReminder(context, prefs.getInt("interval", 30))
    }
}
