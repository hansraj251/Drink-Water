package com.drinkwater.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.drinkwater.app.data.DailyActivityEntity
import com.drinkwater.app.data.DrinkWaterDatabase
import com.drinkwater.app.data.WaterHealthRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class StepTrackingService : Service(), SensorEventListener {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val sensorEvents = Channel<Long>(Channel.CONFLATED)

    private lateinit var sensorManager: SensorManager
    private lateinit var repository: WaterHealthRepository
    private var stepSensor: Sensor? = null

    private val prefs by lazy {
        getSharedPreferences("step_tracking", Context.MODE_PRIVATE)
    }

    override fun onCreate() {
        super.onCreate()

        createTrackingChannel()
        startTrackingForeground()

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        repository = WaterHealthRepository(
            DrinkWaterDatabase.getInstance(applicationContext)
        )

        stepSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)

        if (stepSensor == null) {
            stopSelf()
            return
        }

        serviceScope.launch {
            for (counter in sensorEvents) {
                processSensorCounter(counter)
            }
        }

        sensorManager.registerListener(
            this,
            stepSensor,
            SensorManager.SENSOR_DELAY_NORMAL
        )
    }

    private fun startTrackingForeground() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle("Step tracking is active")
            .setContentText("Your daily steps are being recorded.")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createTrackingChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Step Tracking",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps daily step tracking active"
            }

            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type != Sensor.TYPE_STEP_COUNTER) return

        val totalSinceBoot = event.values.firstOrNull()?.toLong() ?: return
        if (totalSinceBoot >= 0L) {
            sensorEvents.trySend(totalSinceBoot)
        }
    }

    private suspend fun processSensorCounter(totalSinceBoot: Long) {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

        val savedDate = prefs.getString(KEY_DATE, null)
        val previousCounter = prefs.getLong(KEY_COUNTER, -1L)
        val savedSteps = prefs.getInt(KEY_STEPS, 0)

        val existingActivity = repository.getDailyActivity(today)

        val currentSteps = when {
            savedDate != today -> existingActivity?.steps ?: 0

            previousCounter < 0L -> savedSteps

            totalSinceBoot >= previousCounter -> {
                val difference = totalSinceBoot - previousCounter
                (savedSteps.toLong() + difference)
                    .coerceAtMost(Int.MAX_VALUE.toLong())
                    .toInt()
            }

            // Sensor counter reset, usually after a device reboot.
            // Keep the steps already recorded for this date.
            else -> savedSteps
        }.coerceAtLeast(0)

        // Persist the sensor baseline before writing Room.
        // The next sensor event will repair Room if the process stops
        // between these two operations.
        prefs.edit()
            .putString(KEY_DATE, today)
            .putLong(KEY_COUNTER, totalSinceBoot)
            .putInt(KEY_STEPS, currentSteps)
            .commit()

        val profile = repository.getHealthProfile()
        val heightCm = (profile?.heightCm ?: 170).coerceIn(100, 230)

        // Approximate walking stride: 41.5% of height.
        val strideMetres = heightCm * 0.415 / 100.0
        val distanceKm = currentSteps * strideMetres / 1000.0

        // Approximation using an average cadence of 100 steps/minute.
        val walkingMinutes = currentSteps / 100

        repository.saveDailyActivity(
            DailyActivityEntity(
                date = today,
                steps = currentSteps,
                walkingMinutes = walkingMinutes
            )
        )

        prefs.edit()
            .putFloat(KEY_DISTANCE, distanceKm.toFloat())
            .apply()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onDestroy() {
        if (::sensorManager.isInitialized) {
            sensorManager.unregisterListener(this)
        }
        sensorEvents.close()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val CHANNEL_ID = "step_tracking"
        const val NOTIFICATION_ID = 2301

        const val KEY_DATE = "tracked_date"
        const val KEY_COUNTER = "last_sensor_counter"
        const val KEY_STEPS = "today_steps"
        const val KEY_DISTANCE = "today_distance_km"

        fun start(context: Context) {
            val intent = Intent(context, StepTrackingService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, StepTrackingService::class.java))
        }
    }
}
