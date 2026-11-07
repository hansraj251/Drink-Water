package com.drinkwater.app

import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState

import android.Manifest
import android.app.AlarmManager
import android.app.TimePickerDialog
import java.util.Locale
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
import androidx.lifecycle.lifecycleScope
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.IntentSenderRequest
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.launch
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

    private var driveStatus by mutableStateOf("Google Drive not connected")
    private var driveMessage by mutableStateOf("")
    private var driveBusy by mutableStateOf(false)
    private var driveRefreshSignal by mutableIntStateOf(0)
    private var pendingDriveAction: String? = null

    private val driveAuthorizationLauncher =
        registerForActivityResult(
            ActivityResultContracts.StartIntentSenderForResult()
        ) { result ->
            if (result.resultCode == RESULT_OK && result.data != null) {
                try {
                    val authResult = Identity.getAuthorizationClient(this)
                        .getAuthorizationResultFromIntent(result.data)
                    handleDriveAccessToken(authResult.accessToken)
                } catch (e: Exception) {
                    driveBusy = false
                    driveMessage = "Google Drive authorization failed: ${e.message ?: "Unknown error"}"
                }
            } else {
                driveBusy = false
                driveMessage = "Google Drive authorization was cancelled."
            }
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
                    onStop = { stopReminder() },
                    onDriveConnect = { authorizeDrive("connect") },
                    onDriveBackup = { authorizeDrive("backup") },
                    onDriveRestore = { authorizeDrive("restore") },
                    driveStatus = driveStatus,
                    driveMessage = driveMessage,
                    driveBusy = driveBusy,
                    driveRefreshSignal = driveRefreshSignal
                )
            }
        }
    }

    private fun authorizeDrive(action: String) {
        if (driveBusy) return

        pendingDriveAction = action
        driveBusy = true
        driveMessage = ""

        val request = AuthorizationRequest.builder()
            .setRequestedScopes(
                listOf(Scope("https://www.googleapis.com/auth/drive.appdata"))
            )
            .build()

        Identity.getAuthorizationClient(this)
            .authorize(request)
            .addOnSuccessListener { result ->
                if (result.hasResolution()) {
                    val pendingIntent = result.pendingIntent
                    if (pendingIntent == null) {
                        driveBusy = false
                        driveMessage = "Google authorization did not return a consent screen."
                    } else {
                        try {
                            driveAuthorizationLauncher.launch(
                                IntentSenderRequest.Builder(
                                    pendingIntent.intentSender
                                ).build()
                            )
                        } catch (e: Exception) {
                            driveBusy = false
                            driveMessage = "Could not open Google authorization: ${e.message}"
                        }
                    }
                } else {
                    handleDriveAccessToken(result.accessToken)
                }
            }
            .addOnFailureListener { error ->
                driveBusy = false
                driveMessage = "Google Drive authorization failed: ${error.message ?: "Unknown error"}"
            }
    }

    private fun handleDriveAccessToken(accessToken: String?) {
        if (accessToken.isNullOrBlank()) {
            driveBusy = false
            driveMessage = "Google did not provide a Drive access token."
            return
        }

        val action = pendingDriveAction
        if (action == null) {
            driveBusy = false
            driveMessage = "No Drive operation was selected."
            return
        }

        lifecycleScope.launch {
            try {
                val manager = DriveBackupManager(applicationContext)

                when (action) {
                    "connect" -> {
                        driveStatus = "Google Drive authorization granted"
                        driveMessage = "Drive access is ready. You can now back up your history."
                    }

                    "backup" -> {
                        val result = manager.backup(accessToken)
                        driveStatus = "Last backup: ${result.modifiedTime ?: "just now"}"
                        driveMessage = "Backup completed successfully."
                    }

                    "restore" -> {
                        val count = manager.restore(accessToken)
                        driveRefreshSignal++
                        driveStatus = "History restored from Google Drive"
                        driveMessage = "$count days of water history restored successfully."
                    }

                    else -> {
                        driveMessage = "Unknown Drive operation."
                    }
                }
            } catch (e: Exception) {
                driveMessage = "Drive operation failed: ${e.message ?: "Unknown error"}"
            } finally {
                driveBusy = false
                pendingDriveAction = null
            }
        }
    }

    private fun startSelectedReminder() {
        val prefs = getSharedPreferences("water", MODE_PRIVATE)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED) {
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        // Inexact fallback is supported when exact alarm access is unavailable.
        prefs.edit().putBoolean("active", true).apply()
        DailyScheduleScheduler.scheduleNext(this)
    }

    private fun stopReminder() {
        getSharedPreferences("water", MODE_PRIVATE).edit()
            .putBoolean("active", false).apply()
        DailyScheduleScheduler.cancel(this)
    }

}

@Composable
private fun WaterHome(
    onStart: (Int) -> Unit,
    onStop: () -> Unit,
    onDriveConnect: () -> Unit,
    onDriveBackup: () -> Unit,
    onDriveRestore: () -> Unit,
    driveStatus: String,
    driveMessage: String,
    driveBusy: Boolean,
    driveRefreshSignal: Int
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember {
        context.getSharedPreferences("water", Context.MODE_PRIVATE)
    }

    var selected by remember { mutableIntStateOf(prefs.getInt("interval", 30)) }
    var active by remember { mutableStateOf(prefs.getBoolean("active", false)) }
    var selectedTab by remember { mutableStateOf("Reminder") }
    var showProfile by remember { mutableStateOf(false) }
    var showRestoreConfirmation by remember { mutableStateOf(false) }
    androidx.activity.compose.BackHandler(enabled = showProfile) {
        showProfile = false
    }
    var nextReminderAt by remember { mutableStateOf(prefs.getLong("next_reminder_at", 0L)) }
    var nowMillis by remember { mutableStateOf(System.currentTimeMillis()) }

    LaunchedEffect(active) {
        while (active) {
            nowMillis = System.currentTimeMillis()

            val savedNext = prefs.getLong("next_reminder_at", 0L)
            if (savedNext <= 0L || savedNext < nowMillis - 120_000L) {
                DailyScheduleScheduler.ensureNextScheduled(context)
            }

            nextReminderAt = prefs.getLong("next_reminder_at", 0L)
            nowMillis = System.currentTimeMillis()

            kotlinx.coroutines.delay(30_000L)
        }
    }

    val lifecycleOwner = androidx.lifecycle.LifecycleOwner::class.java.cast(context)!!
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                active = prefs.getBoolean("active", false)
                if (active) {
                    DailyScheduleScheduler.ensureNextScheduled(context)
                    nextReminderAt = prefs.getLong("next_reminder_at", 0L)
                    nowMillis = System.currentTimeMillis()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    var customMinutes by remember { mutableStateOf("") }
    var showCustomDialog by remember { mutableStateOf(false) }
    var customError by remember { mutableStateOf(false) }

    var startMinute by remember { mutableIntStateOf(prefs.getInt("start_minute", 7 * 60)) }
    var endMinute by remember { mutableIntStateOf(prefs.getInt("end_minute", 22 * 60)) }
    fun clockLabel(value: Int): String {
        val hour = value / 60
        val minute = value % 60
        return String.format(Locale.US, "%d:%02d %s", if (hour % 12 == 0) 12 else hour % 12,
            minute, if (hour < 12) "AM" else "PM")
    }
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
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (selectedTab == "Reminder") {
            Spacer(Modifier.height(32.dp))

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
                                    if (active) DailyScheduleScheduler.scheduleNext(context)
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
                                            if (active) DailyScheduleScheduler.scheduleNext(context)
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

            Spacer(Modifier.height(12.dp))
            Text("Daily Schedule", fontSize = 18.sp, fontWeight = FontWeight.Bold,
                color = Ink, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(10.dp))
            Row(modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = {
                        TimePickerDialog(context, { _, hour, minute ->
                            startMinute = hour * 60 + minute
                            prefs.edit().putInt("start_minute", startMinute).apply()
                            if (active) DailyScheduleScheduler.scheduleNext(context)
                        }, startMinute / 60, startMinute % 60, false).show()
                    }, modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(16.dp)
                ) { Text("From: ${clockLabel(startMinute)}", fontSize = 12.sp) }
                OutlinedButton(
                    onClick = {
                        TimePickerDialog(context, { _, hour, minute ->
                            endMinute = hour * 60 + minute
                            prefs.edit().putInt("end_minute", endMinute).apply()
                            if (active) DailyScheduleScheduler.scheduleNext(context)
                        }, endMinute / 60, endMinute % 60, false).show()
                    }, modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(16.dp)
                ) { Text("To: ${clockLabel(endMinute)}", fontSize = 12.sp) }
            }
            Text("Reminders repeat every day during these hours.",
                color = Muted, fontSize = 11.sp)

            Spacer(Modifier.height(5.dp))

            Button(
                onClick = {
                    if (active) {
                        onStop()
                        active = false
                    } else {
                        onStart(selected)
                        active = prefs.getBoolean("active", false)
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

            if (active && nextReminderAt > nowMillis) {
                val target = java.util.Calendar.getInstance().apply {
                    timeInMillis = nextReminderAt
                }
                val today = java.util.Calendar.getInstance().apply {
                    timeInMillis = nowMillis
                }
                val isToday =
                    target.get(java.util.Calendar.YEAR) == today.get(java.util.Calendar.YEAR) &&
                    target.get(java.util.Calendar.DAY_OF_YEAR) == today.get(java.util.Calendar.DAY_OF_YEAR)
                val targetMinutes =
                    target.get(java.util.Calendar.HOUR_OF_DAY) * 60 +
                    target.get(java.util.Calendar.MINUTE)
                val minutesLeft =
                    ((nextReminderAt - nowMillis + 59_999L) / 60_000L).toInt().coerceAtLeast(1)

                Spacer(Modifier.height(5.dp))
                Text(
                    text = "Next reminder: " +
                        (if (isToday) "" else "Tomorrow, ") +
                        clockLabel(targetMinutes),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = DarkBlue,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = if (minutesLeft == 1) "In 1 minute" else "In $minutesLeft minutes",
                    fontSize = 12.sp,
                    color = Muted,
                    textAlign = TextAlign.Center
                )
            } else if (active) {
                Spacer(Modifier.height(5.dp))
                Text(
                    "Calculating next reminder…",
                    fontSize = 12.sp,
                    color = Muted,
                    textAlign = TextAlign.Center
                )
            }

            Spacer(Modifier.height(18.dp))

                } else if (showProfile) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = Color(0xFFF5FAFF)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 16.dp, vertical = 12.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                TextButton(onClick = { showProfile = false }) {
                                    Text("←", fontSize = 28.sp, color = Ink)
                                }
                                Text(
                                    "Profile",
                                    fontSize = 22.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Ink
                                )
                            }

            HealthProfileSection()

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                color = Color.White
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        "Google Drive Backup",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = Ink
                    )

                    Spacer(Modifier.height(5.dp))

                    Text(
                        driveStatus,
                        fontSize = 12.sp,
                        color = Muted
                    )

                    if (driveMessage.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            driveMessage,
                            fontSize = 12.sp,
                            color = Blue
                        )
                    }

                    Spacer(Modifier.height(12.dp))

                    OutlinedButton(
                        onClick = onDriveConnect,
                        enabled = !driveBusy,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Connect Google Drive")
                    }

                    OutlinedButton(
                        onClick = onDriveBackup,
                        enabled = !driveBusy,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Backup Now")
                    }

                    OutlinedButton(
                        onClick = { showRestoreConfirmation = true },
                        enabled = !driveBusy,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Restore Backup")
                    }

                    if (driveBusy) {
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

                        }
                    }
                } else {
                    DailyGoalScreen(
                        onOpenProfile = { showProfile = true },
                        onDriveConnect = onDriveConnect,
                        onDriveBackup = onDriveBackup,
                        onDriveRestore = onDriveRestore,
                        driveStatus = driveStatus,
                        driveMessage = driveMessage,
                        driveBusy = driveBusy,
                        driveRefreshSignal = driveRefreshSignal
                    )
                }
            }


        if (showRestoreConfirmation) {
            AlertDialog(
                onDismissRequest = { showRestoreConfirmation = false },
                title = { Text("Restore water history?", color = Ink) },
                text = {
                    Text(
                        "This will replace the current water history with the backup from Google Drive. A local safety copy will be created before replacement.",
                        color = Muted
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showRestoreConfirmation = false
                            onDriveRestore()
                        },
                        enabled = !driveBusy
                    ) {
                        Text("Restore")
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { showRestoreConfirmation = false }
                    ) {
                        Text("Cancel")
                    }
                },
                containerColor = Color.White
            )
        }


            NavigationBar(
                containerColor = Color(0xFFF5FAFF),
                tonalElevation = 0.dp
            ) {
                NavigationBarItem(
                    selected = selectedTab == "Reminder",
                    onClick = { selectedTab = "Reminder" },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Color(0xFF2196F3),
                        selectedTextColor = Color(0xFF2196F3),
                        indicatorColor = Color(0xFFDCEEFF)
                    ),
                    icon = {
                        Icon(
                            Icons.Rounded.NotificationsActive,
                            contentDescription = "Reminder"
                        )
                    },
                    label = { Text("Reminder") }
                )
                NavigationBarItem(
                    selected = selectedTab == "Daily Goal",
                    onClick = { selectedTab = "Daily Goal" },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Color(0xFF2196F3),
                        selectedTextColor = Color(0xFF2196F3),
                        indicatorColor = Color(0xFFDCEEFF)
                    ),
                    icon = {
                        Icon(
                            Icons.Rounded.WaterDrop,
                            contentDescription = "Daily Goal"
                        )
                    },
                    label = { Text("Daily Goal") }
                )
            }

        }
    }
}


@Composable
private fun DailyGoalScreen(
    onOpenProfile: () -> Unit,
    onDriveConnect: () -> Unit,
    onDriveBackup: () -> Unit,
    onDriveRestore: () -> Unit,
    driveStatus: String,
    driveMessage: String,
    driveBusy: Boolean,
    driveRefreshSignal: Int
) {
    val context = androidx.compose.ui.platform.LocalContext.current

    val prefs = remember {
        context.getSharedPreferences("water_tracker", Context.MODE_PRIVATE)
    }

    val store = remember {
        com.drinkwater.app.data.WaterHealthRepository(
            com.drinkwater.app.data.DrinkWaterDatabase.getInstance(context)
        )
    }

    val scope = rememberCoroutineScope()
    val todayKey = remember { WaterHistoryStore.todayKey() }

    val initialGoal = remember {
        prefs.getInt("daily_goal_ml", 2000).coerceIn(250, 10000)
    }

    var intake by remember { mutableIntStateOf(0) }
    var goal by remember { mutableIntStateOf(initialGoal) }
    var history by remember { mutableStateOf<List<WaterDay>>(emptyList()) }

    fun convertDay(day: com.drinkwater.app.data.WaterDayEntity): WaterDay {
        return WaterDay(
            date = day.date,
            intakeMl = day.intakeMl,
            goalMl = day.goalMl
        )
    }

    suspend fun loadTodayAndHistory() {
        val day = store.getOrCreateToday(todayKey, goal)
        intake = day.intakeMl
        goal = day.goalMl ?: goal
        history = store.getWaterHistory().map(::convertDay)
    }

    LaunchedEffect(Unit) {
        loadTodayAndHistory()
    }

    var lastAdded by remember { mutableIntStateOf(0) }
    var goalInput by remember { mutableStateOf(goal.toString()) }
    var customInput by remember { mutableStateOf("") }
    var showGoalDialog by remember { mutableStateOf(false) }
    var showCustomDialog by remember { mutableStateOf(false) }

    fun refreshHistory() {
        scope.launch {
            history = store.getWaterHistory().map(::convertDay)
        }
    }

    LaunchedEffect(driveRefreshSignal) {
        if (driveRefreshSignal > 0) {
            loadTodayAndHistory()
        }
    }

    fun saveIntake(value: Int) {
        val safeValue = value.coerceAtLeast(0)
        scope.launch {
            val updated = store.setIntake(todayKey, safeValue, goal)
            intake = updated.intakeMl
            refreshHistory()
        }
    }

    val progress =
        (intake.toFloat() / goal.coerceAtLeast(1)).coerceIn(0f, 1f)
    val remaining = (goal - intake).coerceAtLeast(0)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(top = 20.dp, bottom = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Daily Goal",
                modifier = Modifier.weight(1f),
                fontSize = 25.sp,
                fontWeight = FontWeight.Bold,
                color = Ink
            )
            TextButton(onClick = onOpenProfile) {
                Text("👤", fontSize = 24.sp)
            }
        }

        Spacer(Modifier.height(5.dp))

        Text(
            "Your daily hydration progress",
            fontSize = 13.sp,
            color = Muted
        )

        Spacer(Modifier.height(16.dp))

        Spacer(Modifier.height(22.dp))

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(26.dp),
            color = Color.White
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier.size(165.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxSize(),
                        strokeWidth = 12.dp,
                        color = Blue,
                        trackColor = PaleBlue
                    )

                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Rounded.WaterDrop,
                            contentDescription = null,
                            tint = Blue,
                            modifier = Modifier.size(30.dp)
                        )

                        Text(
                            "$intake ml",
                            fontSize = 25.sp,
                            fontWeight = FontWeight.Bold,
                            color = Ink
                        )

                        Text("consumed", fontSize = 12.sp, color = Muted)
                    }
                }

                Spacer(Modifier.height(16.dp))

                Text(
                    if (intake >= goal) {
                        "Daily goal completed!"
                    } else {
                        "$remaining ml remaining"
                    },
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Ink
                )

                Text(
                    "${(progress * 100).toInt()}% of daily goal",
                    fontSize = 13.sp,
                    color = Muted
                )

                Spacer(Modifier.height(8.dp))

                OutlinedButton(
                    onClick = {
                        goalInput = goal.toString()
                        showGoalDialog = true
                    },
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text("Daily target: $goal ml  ·  Edit")
                }
            }
        }

        Spacer(Modifier.height(22.dp))

        Text(
            "Add Water",
            modifier = Modifier.fillMaxWidth(),
            fontSize = 19.sp,
            fontWeight = FontWeight.Bold,
            color = Ink
        )

        Spacer(Modifier.height(10.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            listOf(150, 250, 500).forEach { amount ->
                OutlinedButton(
                    onClick = {
                        lastAdded = amount
                        saveIntake(intake + amount)
                    },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(
                        horizontal = 3.dp,
                        vertical = 10.dp
                    ),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text("+$amount ml", fontSize = 12.sp)
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            OutlinedButton(
                onClick = {
                    customInput = ""
                    showCustomDialog = true
                },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text("Custom amount")
            }

            OutlinedButton(
                onClick = {
                    if (lastAdded > 0 && intake > 0) {
                        saveIntake(intake - lastAdded)
                        lastAdded = 0
                    }
                },
                enabled = lastAdded > 0 && intake > 0,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text("Undo last")
            }
        }

        Spacer(Modifier.height(24.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "History",
                fontSize = 19.sp,
                fontWeight = FontWeight.Bold,
                color = Ink
            )
            Spacer(Modifier.weight(1f))
            Text(
                "${history.size} days",
                fontSize = 12.sp,
                color = Muted
            )
        }

        Spacer(Modifier.height(10.dp))

        if (history.isEmpty()) {
            Text(
                "No intake history available yet.",
                modifier = Modifier.fillMaxWidth(),
                color = Muted,
                fontSize = 13.sp,
                textAlign = TextAlign.Center
            )
        } else {
            history.forEach { day ->
                val displayDate = runCatching {
                    val parsed = java.text.SimpleDateFormat(
                        "yyyy-MM-dd",
                        Locale.US
                    ).parse(day.date)

                    if (parsed != null) {
                        java.text.SimpleDateFormat(
                            "dd MMM yyyy",
                            Locale.getDefault()
                        ).format(parsed)
                    } else {
                        day.date
                    }
                }.getOrDefault(day.date)

                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    shape = RoundedCornerShape(15.dp),
                    color = Color.White
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                displayDate,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Ink
                            )

                            Spacer(Modifier.height(4.dp))

                            Text(
                                if (day.goalMl != null) {
                                    "${day.intakeMl} / ${day.goalMl} ml  ·  ${day.percentage}%"
                                } else {
                                    "${day.intakeMl} ml  ·  Goal not recorded"
                                },
                                fontSize = 12.sp,
                                color = Muted
                            )
                        }

                        if (day.goalMl != null) {
                            Text(
                                if (day.completed) "Completed" else "In progress",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (day.completed) {
                                    Color(0xFF238653)
                                } else {
                                    Blue
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    if (showGoalDialog) {
        AlertDialog(
            onDismissRequest = { showGoalDialog = false },
            title = { Text("Set Daily Goal", color = Ink) },
            text = {
                OutlinedTextField(
                    value = goalInput,
                    onValueChange = {
                        goalInput = it.filter(Char::isDigit).take(5)
                    },
                    label = { Text("Goal in ml (250–10000)") },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number
                    ),
                    singleLine = true
                )
            },
            confirmButton = {
                OutlinedButton(
                    onClick = {
                        val value = goalInput.toIntOrNull()
                        if (value != null && value in 250..10000) {
                            scope.launch {
                                val updated = store.setGoal(todayKey, value)
                                goal = updated.goalMl ?: value
                                prefs.edit()
                                    .putInt("daily_goal_ml", goal)
                                    .apply()
                                refreshHistory()
                                showGoalDialog = false
                            }
                        }
                    },
                    shape = RoundedCornerShape(12.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Ink),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = Ink
                    )
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { showGoalDialog = false },
                    shape = RoundedCornerShape(12.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Ink),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = Ink
                    )
                ) {
                    Text("Cancel")
                }
            },
            containerColor = Color.White
        )
    }

    if (showCustomDialog) {
        AlertDialog(
            onDismissRequest = { showCustomDialog = false },
            title = { Text("Add Water", color = Ink) },
            text = {
                OutlinedTextField(
                    value = customInput,
                    onValueChange = {
                        customInput = it.filter(Char::isDigit).take(5)
                    },
                    label = { Text("Amount in ml") },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number
                    ),
                    singleLine = true
                )
            },
            confirmButton = {
                OutlinedButton(
                    onClick = {
                        val amount = customInput.toIntOrNull()
                        if (amount != null && amount in 1..5000) {
                            lastAdded = amount
                            saveIntake(intake + amount)
                            showCustomDialog = false
                        }
                    },
                    shape = RoundedCornerShape(12.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Ink),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = Ink
                    )
                ) {
                    Text("Add")
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { showCustomDialog = false },
                    shape = RoundedCornerShape(12.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Ink),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = Ink
                    )
                ) {
                    Text("Cancel")
                }
            },
            containerColor = Color.White
        )
    }
}


@Composable
private fun HealthProfileSection() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val repository = remember(context) {
        com.drinkwater.app.data.WaterHealthRepository(
            com.drinkwater.app.data.DrinkWaterDatabase.getInstance(context.applicationContext)
        )
    }
    val scope = rememberCoroutineScope()

    var name by remember { mutableStateOf("") }
    var age by remember { mutableStateOf("") }
    var sex by remember { mutableStateOf("Prefer not to say") }
    var height by remember { mutableStateOf("") }
    var weight by remember { mutableStateOf("") }
    var activity by remember { mutableStateOf("Sedentary") }
    var averageSteps by remember { mutableStateOf("0") }

    var waterOverride by remember { mutableStateOf("") }
    var stepsOverride by remember { mutableStateOf("") }
    var minutesOverride by remember { mutableStateOf("") }

    var expandedSex by remember { mutableStateOf(false) }
    var expandedActivity by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }

    val activityOptions = listOf(
        "Sedentary",
        "Lightly Active",
        "Moderately Active",
        "Very Active"
    )
    val sexOptions = listOf(
        "Male",
        "Female",
        "Prefer not to say"
    )

    LaunchedEffect(Unit) {
        val profile = repository.getHealthProfile()
        if (profile != null) {
            name = profile.name
            age = profile.age.toString()
            sex = profile.sex
            height = profile.heightCm.toString()
            weight = profile.weightKg.toString()
            activity = profile.activityLevel
            averageSteps = profile.averageDailySteps.toString()
            waterOverride = profile.waterGoalOverrideMl?.toString() ?: ""
            stepsOverride = profile.stepsGoalOverride?.toString() ?: ""
            minutesOverride = profile.walkingMinutesOverride?.toString() ?: ""
        }
        loaded = true
    }

    val ageValue = age.toIntOrNull()
    val heightValue = height.toIntOrNull()
    val weightValue = weight.toDoubleOrNull()
    val stepsValue = averageSteps.toIntOrNull()

    val previewProfile = if (
        ageValue != null && heightValue != null &&
        weightValue != null && stepsValue != null
    ) {
        runCatching {
            com.drinkwater.app.data.HealthProfileEntity(
                name = name.trim(),
                age = ageValue,
                sex = sex,
                heightCm = heightValue,
                weightKg = weightValue,
                activityLevel = activity,
                averageDailySteps = stepsValue,
                waterGoalOverrideMl = waterOverride.toIntOrNull(),
                stepsGoalOverride = stepsOverride.toIntOrNull(),
                walkingMinutesOverride = minutesOverride.toIntOrNull()
            )
        }.getOrNull()
    } else null

    val previewGoals = previewProfile?.let {
        com.drinkwater.app.data.HealthGoalCalculator.calculate(it)
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = Color.White
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                "Health Profile",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = Ink
            )

            Text("Personal Information", fontWeight = FontWeight.SemiBold, color = Ink)

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name (optional)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            OutlinedTextField(
                value = age,
                onValueChange = { age = it.filter(Char::isDigit).take(3) },
                label = { Text("Age (13–110 years)") },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true
            )

            Box {
                OutlinedButton(
                    onClick = { expandedSex = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Sex: $sex")
                }
                DropdownMenu(
                    expanded = expandedSex,
                    onDismissRequest = { expandedSex = false }
                ) {
                    sexOptions.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option) },
                            onClick = {
                                sex = option
                                expandedSex = false
                            }
                        )
                    }
                }
            }

            HorizontalDivider()

            Text("Body Details", fontWeight = FontWeight.SemiBold, color = Ink)

            OutlinedTextField(
                value = height,
                onValueChange = { height = it.filter(Char::isDigit).take(3) },
                label = { Text("Height (cm)") },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true
            )

            OutlinedTextField(
                value = weight,
                onValueChange = { weight = it.filter { c -> c.isDigit() || c == '.' }.take(6) },
                label = { Text("Weight (kg)") },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true
            )

            HorizontalDivider()

            Text("Activity Information", fontWeight = FontWeight.SemiBold, color = Ink)

            Box {
                OutlinedButton(
                    onClick = { expandedActivity = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Activity: $activity")
                }
                DropdownMenu(
                    expanded = expandedActivity,
                    onDismissRequest = { expandedActivity = false }
                ) {
                    activityOptions.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option) },
                            onClick = {
                                activity = option
                                expandedActivity = false
                            }
                        )
                    }
                }
            }

            OutlinedTextField(
                value = averageSteps,
                onValueChange = { averageSteps = it.filter(Char::isDigit).take(6) },
                label = { Text("Average daily steps") },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true
            )

            HorizontalDivider()

            Text("Your Daily Goals", fontWeight = FontWeight.SemiBold, color = Ink)

            if (previewGoals != null) {
                Text("Suggested water goal: ${previewGoals.waterMl} ml", color = Blue)
                Text("Suggested steps: ${previewGoals.steps}", color = Blue)
                Text("Suggested walking: ${previewGoals.walkingMinutes} minutes", color = Blue)
            } else {
                Text("Enter valid profile details to calculate your goals.", color = Muted)
            }

            OutlinedTextField(
                value = waterOverride,
                onValueChange = { waterOverride = it.filter(Char::isDigit).take(5) },
                label = { Text("Water goal override (ml, optional)") },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true
            )

            OutlinedTextField(
                value = stepsOverride,
                onValueChange = { stepsOverride = it.filter(Char::isDigit).take(6) },
                label = { Text("Steps goal override (optional)") },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true
            )

            OutlinedTextField(
                value = minutesOverride,
                onValueChange = { minutesOverride = it.filter(Char::isDigit).take(4) },
                label = { Text("Walking minutes override (optional)") },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true
            )

            Text(
                "These are general wellness estimates, not medical advice. Follow your doctor's instructions if you have a prescribed fluid restriction.",
                fontSize = 12.sp,
                color = Muted
            )

            Button(
                onClick = {
                    val a = age.toIntOrNull()
                    val h = height.toIntOrNull()
                    val w = weight.toDoubleOrNull()
                    val steps = averageSteps.toIntOrNull()
                    val water = waterOverride.toIntOrNull()
                    val stepGoal = stepsOverride.toIntOrNull()
                    val minutes = minutesOverride.toIntOrNull()

                    val valid =
                        a != null && a in 13..110 &&
                        h != null && h in 100..250 &&
                        w != null && w in 30.0..200.0 &&
                        steps != null && steps in 0..50000 &&
                        (waterOverride.isBlank() || water != null && water in 1500..4000) &&
                        (stepsOverride.isBlank() || stepGoal != null && stepGoal in 500..30000) &&
                        (minutesOverride.isBlank() || minutes != null && minutes in 1..600)

                    if (!valid) {
                        message = "Please check the entered values and goal overrides."
                    } else {
                        saving = true
                        message = ""
                        scope.launch {
                            try {
                                val profile = com.drinkwater.app.data.HealthProfileEntity(
                                    name = name.trim(),
                                    age = a!!,
                                    sex = sex,
                                    heightCm = h!!,
                                    weightKg = w!!,
                                    activityLevel = activity,
                                    averageDailySteps = steps!!,
                                    waterGoalOverrideMl = water,
                                    stepsGoalOverride = stepGoal,
                                    walkingMinutesOverride = minutes
                                )
                                repository.saveHealthProfile(profile)

                                val goals = com.drinkwater.app.data.HealthGoalCalculator.calculate(profile)
                                val today = WaterHistoryStore.todayKey()
                                repository.setGoal(today, goals.waterMl)
                                context.getSharedPreferences("water_tracker", Context.MODE_PRIVATE).edit().putInt("daily_goal_ml", goals.waterMl).apply()

                                message = "Profile and daily goals saved."
                            } catch (e: Exception) {
                                message = "Unable to save profile: ${e.message ?: "Unknown error"}"
                            } finally {
                                saving = false
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = loaded && !saving
            ) {
                Text(if (saving) "Saving…" else "Save / Update Profile")
            }

            if (message.isNotBlank()) {
                Text(message, color = if (message.startsWith("Unable")) Color(0xFFB3261E) else Blue)
            }
        }
    }
}

fun scheduleReminder(context: Context, minutes: Int) {
    context.getSharedPreferences("water", Context.MODE_PRIVATE).edit()
        .putInt("interval", minutes.coerceIn(1, 1440)).apply()
    DailyScheduleScheduler.scheduleNext(context)
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
