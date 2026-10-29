package com.drinkwater.app

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType

private val Blue = Color(0xFF2878F0)
private val DarkBlue = Color(0xFF1455BC)
private val PaleBlue = Color(0xFFEAF4FF)
private val Ink = Color(0xFF172D4F)
private val Muted = Color(0xFF71839B)

class MainActivity : ComponentActivity() {

    private val permissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            if (granted) startSelectedReminder()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createNotificationChannel(this)

        setContent {
            MaterialTheme {
                WaterHome(
                    onStart = { minutes ->
                        getSharedPreferences("water", MODE_PRIVATE)
                            .edit()
                            .putInt("interval", minutes)
                            .apply()

                        if (
                            Build.VERSION.SDK_INT >= 33 &&
                            ContextCompat.checkSelfPermission(
                                this,
                                Manifest.permission.POST_NOTIFICATIONS
                            ) != PackageManager.PERMISSION_GRANTED
                        ) {
                            permissionLauncher.launch(
                                Manifest.permission.POST_NOTIFICATIONS
                            )
                        } else {
                            startSelectedReminder()
                        }
                    },
                    onStop = { stopReminder() }
                )
            }
        }
    }

    private fun startSelectedReminder() {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            !(getSystemService(ALARM_SERVICE) as AlarmManager)
                .canScheduleExactAlarms()
        ) {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    Uri.parse("package:$packageName")
                )
            )
            return
        }

        val minutes = getSharedPreferences("water", MODE_PRIVATE)
            .getInt("interval", 30)

        getSharedPreferences("water", MODE_PRIVATE)
            .edit()
            .putBoolean("active", true)
            .apply()

        scheduleReminder(this, minutes)
    }

    private fun stopReminder() {
        getSharedPreferences("water", MODE_PRIVATE)
            .edit()
            .putBoolean("active", false)
            .apply()

        val intent = Intent(this, WaterReminderReceiver::class.java)
        val pending = PendingIntent.getBroadcast(
            this, 100, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        (getSystemService(ALARM_SERVICE) as AlarmManager)
            .cancel(pending)
    }
}

@Composable
private fun WaterHome(
    onStart: (Int) -> Unit,
    onStop: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember {
        context.getSharedPreferences("water", Context.MODE_PRIVATE)
    }

    var selected by remember { mutableIntStateOf(prefs.getInt("interval", 30)) }
    var active by remember { mutableStateOf(prefs.getBoolean("active", false)) }
    var customMinutes by remember { mutableStateOf("") }
    var showCustomDialog by remember { mutableStateOf(false) }
    var customError by remember { mutableStateOf(false) }

    val intervals = listOf(15, 30, 45, 60)

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color(0xFFF5FAFF)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp)
                .statusBarsPadding()
                .navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(20.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .background(PaleBlue, RoundedCornerShape(15.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Rounded.WaterDrop,
                        contentDescription = null,
                        tint = Blue,
                        modifier = Modifier.size(27.dp)
                    )
                }

                Spacer(Modifier.width(12.dp))

                Column {
                    Text(
                        "Drink Water",
                        fontSize = 21.sp,
                        fontWeight = FontWeight.Bold,
                        color = Ink
                    )
                    Text(
                        "YOUR DAILY HYDRATION",
                        fontSize = 10.sp,
                        letterSpacing = 1.5.sp,
                        color = Muted
                    )
                }

                Spacer(Modifier.weight(1f))

                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .background(Color.White, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Rounded.NotificationsActive,
                        contentDescription = "Reminder",
                        tint = Blue
                    )
                }
            }

            Spacer(Modifier.height(32.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .heightIn(min = 235.dp, max = 280.dp)
                    .background(
                        Brush.verticalGradient(
                            listOf(Color(0xFFE1F1FF), Color(0xFFF0F8FF))
                        ),
                        RoundedCornerShape(32.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier
                            .size(116.dp)
                            .background(Color.White.copy(alpha = 0.8f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Rounded.WaterDrop,
                            contentDescription = "Water drop",
                            tint = Blue,
                            modifier = Modifier.size(75.dp)
                        )
                    }

                    Spacer(Modifier.height(19.dp))

                    Text(
                        "A little water,",
                        fontSize = 25.sp,
                        fontWeight = FontWeight.Bold,
                        color = Ink
                    )
                    Text(
                        "a healthier you.",
                        fontSize = 25.sp,
                        fontWeight = FontWeight.Bold,
                        color = Blue
                    )

                    Spacer(Modifier.height(8.dp))

                    Text(
                        "Build a healthy hydration habit.",
                        fontSize = 13.sp,
                        color = Muted
                    )
                }
            }

            Spacer(Modifier.height(27.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Remind Me",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = Ink
                )
                Spacer(Modifier.weight(1f))
                Text("REPEAT EVERY", fontSize = 10.sp, color = Muted,
                    letterSpacing = 1.sp)
            }

            Spacer(Modifier.height(16.dp))

            intervals.chunked(2).forEach { rowIntervals ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    rowIntervals.forEach { minutes ->
                        val isSelected = selected == minutes
                        Surface(
                            onClick = {
                                selected = minutes
                                prefs.edit().putInt("interval", minutes).apply()
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(68.dp),
                            shape = RoundedCornerShape(19.dp),
                            color = if (isSelected) Blue else Color.White,
                            border = if (isSelected) null else
                                androidx.compose.foundation.BorderStroke(
                                    1.dp, Color(0xFFE1EAF5)
                                ),
                            shadowElevation = if (isSelected) 4.dp else 0.dp
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 12.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (isSelected) {
                                    Icon(
                                        Icons.Rounded.CheckCircle,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(19.dp)
                                    )
                                    Spacer(Modifier.width(7.dp))
                                }

                                Column {
                                    Text(
                                        "$minutes",
                                        fontSize = 20.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isSelected) Color.White else Ink
                                    )
                                    Text(
                                        "Minutes",
                                        fontSize = 11.sp,
                                        color = if (isSelected)
                                            Color.White.copy(alpha = 0.85f)
                                        else Muted
                                    )
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            Surface(
                onClick = {
                    customMinutes = ""
                    customError = false
                    showCustomDialog = true
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(17.dp),
                color = Color.White,
                border = androidx.compose.foundation.BorderStroke(
                    1.dp, Color(0xFFD8E7F8)
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 18.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Rounded.NotificationsActive,
                        contentDescription = null,
                        tint = Blue
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Custom Time",
                            fontWeight = FontWeight.SemiBold,
                            color = Ink,
                            fontSize = 15.sp
                        )
                        Text(
                            "Set your own interval in minutes",
                            color = Muted,
                            fontSize = 11.sp
                        )
                    }
                    Text("›", fontSize = 25.sp, color = Blue)
                }
            }

            Spacer(Modifier.height(10.dp))

            if (showCustomDialog) {
                androidx.compose.ui.window.Dialog(
                    onDismissRequest = { showCustomDialog = false }
                ) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(28.dp),
                        color = Color.White,
                        shadowElevation = 12.dp
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp)
                        ) {
                            Text(
                                "Custom reminder",
                                fontSize = 21.sp,
                                fontWeight = FontWeight.Bold,
                                color = Ink
                            )

                            Spacer(Modifier.height(10.dp))

                            Text(
                                "Enter the interval between reminders (1–1440 minutes).",
                                color = Muted,
                                fontSize = 14.sp
                            )

                            Spacer(Modifier.height(18.dp))

                            OutlinedTextField(
                                value = customMinutes,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                onValueChange = {
                                    customMinutes = it.filter(Char::isDigit).take(4)
                                    customError = false
                                },
                                label = { Text("Minutes") },
                                singleLine = true,
                                isError = customError,
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier.fillMaxWidth(),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = Blue,
                                    unfocusedBorderColor = Color(0xFFD5E2F0),
                                    focusedContainerColor = Color.White,
                                    unfocusedContainerColor = Color.White
                                )
                            )

                            if (customError) {
                                Spacer(Modifier.height(5.dp))
                                Text(
                                    "Enter a value from 1 to 1440.",
                                    color = MaterialTheme.colorScheme.error,
                                    fontSize = 12.sp
                                )
                            }

                            Spacer(Modifier.height(22.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                OutlinedButton(
                                    onClick = { showCustomDialog = false },
                                    modifier = Modifier.weight(1f).height(48.dp),
                                    shape = RoundedCornerShape(14.dp),
                                    border = androidx.compose.foundation.BorderStroke(
                                        1.dp, Color(0xFFD5E2F0)
                                    ),
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        contentColor = Ink
                                    )
                                ) {
                                    Text("Cancel", fontWeight = FontWeight.SemiBold)
                                }

                                OutlinedButton(
                                    onClick = {
                                        val value = customMinutes.toIntOrNull()
                                        if (value == null || value !in 1..1440) {
                                            customError = true
                                        } else {
                                            selected = value
                                            prefs.edit().putInt("interval", value).apply()
                                            showCustomDialog = false
                                        }
                                    },
                                    modifier = Modifier.weight(1f).height(48.dp),
                                    shape = RoundedCornerShape(14.dp),
                                    border = androidx.compose.foundation.BorderStroke(
                                        1.dp, Blue
                                    ),
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        containerColor = PaleBlue,
                                        contentColor = DarkBlue
                                    )
                                ) {
                                    Text("Apply", fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(5.dp))

            Button(
                onClick = {
                    if (active) {
                        onStop()
                        active = false
                    } else {
                        onStart(selected)
                        active = true
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(58.dp),
                shape = RoundedCornerShape(19.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (active) Color(0xFFE6EDF6) else Blue,
                    contentColor = if (active) Ink else Color.White
                ),
                elevation = ButtonDefaults.buttonElevation(
                    defaultElevation = if (active) 0.dp else 5.dp
                )
            ) {
                Icon(
                    if (active) Icons.Rounded.CheckCircle
                    else Icons.Rounded.NotificationsActive,
                    contentDescription = null
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    if (active) "Stop Reminder" else "Start Reminder",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(Modifier.height(13.dp))

            Text(
                if (active) "Reminder enabled • Every $selected minutes"
                else "Small sips. Better habits.",
                fontSize = 12.sp,
                color = if (active) Blue else Muted,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(18.dp))
        }
    }
}

fun scheduleReminder(context: Context, minutes: Int) {
    val intent = Intent(context, WaterReminderReceiver::class.java)
    val pending = PendingIntent.getBroadcast(
        context, 100, intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    val delayMillis = if (minutes == 15) 5_000L else minutes * 60_000L
    val trigger = System.currentTimeMillis() + delayMillis

    if (
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
        alarmManager.canScheduleExactAlarms()
    ) {
        alarmManager.setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP, trigger, pending
        )
    } else {
        alarmManager.setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP, trigger, pending
        )
    }
}

fun createNotificationChannel(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val channel = NotificationChannel(
            "water_reminders",
            "Water Reminders",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Gentle reminders to drink water"
        }

        val manager = context.getSystemService(
            Context.NOTIFICATION_SERVICE
        ) as NotificationManager

        manager.createNotificationChannel(channel)
    }
}
