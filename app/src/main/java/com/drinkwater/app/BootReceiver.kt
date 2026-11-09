package com.drinkwater.app

import android.Manifest
import android.app.ForegroundServiceStartNotAllowedException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED -> {
                // Preserve existing water reminder scheduling.
                DailyScheduleScheduler.scheduleNext(context)

                // Restart step tracking only if the user enabled it earlier.
                if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
                    restartStepTrackingIfAllowed(context)
                }
            }
        }
    }

    private fun restartStepTrackingIfAllowed(context: Context) {
        val prefs = context.getSharedPreferences(
            "step_tracking",
            Context.MODE_PRIVATE
        )

        if (!prefs.getBoolean("enabled", false)) return

        val permissionGranted =
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACTIVITY_RECOGNITION
            ) == PackageManager.PERMISSION_GRANTED

        if (!permissionGranted) {
            prefs.edit().putBoolean("enabled", false).apply()
            return
        }

        try {
            StepTrackingService.start(context)
        } catch (e: SecurityException) {
            // Android may reject a foreground service start.
            // The app will retry when the user opens it.
        } catch (e: ForegroundServiceStartNotAllowedException) {
            // Background-start restriction imposed by Android.
            // The app will retry when the user opens it.
        }
    }
}
