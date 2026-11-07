package com.drinkwater.app

import android.content.Context
import com.drinkwater.app.data.DailyActivityEntity
import com.drinkwater.app.data.DrinkWaterDatabase
import com.drinkwater.app.data.HealthProfileEntity
import com.drinkwater.app.data.WaterBackupSnapshot
import com.drinkwater.app.data.WaterDayEntity
import com.drinkwater.app.data.WaterHealthRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate

class DriveBackupManager(context: Context) {

    private val appContext = context.applicationContext

    private fun repository() = WaterHealthRepository(
        DrinkWaterDatabase.getInstance(appContext)
    )

    companion object {
        private const val FILE_NAME = "drinkwater-backup.json"
        private const val APP_ID = "com.hansraj251.drinkwater"
        private const val SCHEMA_VERSION = 2
        private const val API = "https://www.googleapis.com/drive/v3/files"
        private const val UPLOAD_API = "https://www.googleapis.com/upload/drive/v3/files"
    }

    data class BackupResult(
        val fileId: String,
        val modifiedTime: String?
    )

    private data class RemoteFile(
        val id: String,
        val modifiedTime: String?
    )

    private sealed class ParsedBackup {
        data class V1(val days: List<WaterDayEntity>) : ParsedBackup()
        data class V2(val snapshot: WaterBackupSnapshot) : ParsedBackup()
    }

    suspend fun backup(accessToken: String): BackupResult =
        withContext(Dispatchers.IO) {
            require(accessToken.isNotBlank()) {
                "Google Drive authorization is required."
            }

            val snapshot = repository().createBackupSnapshot()

            require(
                snapshot.days.isNotEmpty() ||
                    snapshot.healthProfile != null ||
                    snapshot.dailyActivity.isNotEmpty()
            ) {
                "There is no DrinkWater data to back up."
            }

            val payload = createBackupJson(snapshot).toString()
            val existing = findBackupFile(accessToken)

            val response = if (existing == null) {
                createRemoteFile(accessToken, payload)
            } else {
                updateRemoteFile(accessToken, existing.id, payload)
            }

            BackupResult(
                fileId = response.optString("id"),
                modifiedTime = response.optString("modifiedTime")
                    .takeIf { it.isNotBlank() }
            )
        }

    suspend fun restore(accessToken: String): Int =
        withContext(Dispatchers.IO) {
            require(accessToken.isNotBlank()) {
                "Google Drive authorization is required."
            }

            val remote = findBackupFile(accessToken)
                ?: throw IllegalStateException(
                    "No DrinkWater backup was found in Google Drive."
                )

            val json = downloadRemoteFile(accessToken, remote.id)

            // Parse and validate the complete payload before local mutation.
            val parsed = parseBackupJson(json)

            // Preserve all local Room datasets before replacing anything.
            createLocalSafetyCopy(repository().createBackupSnapshot())

            when (parsed) {
                is ParsedBackup.V1 -> {
                    repository().restoreV1(parsed.days)
                    parsed.days.size
                }

                is ParsedBackup.V2 -> {
                    repository().restoreV2(parsed.snapshot)
                    parsed.snapshot.days.size
                }
            }
        }

    private fun createLocalSafetyCopy(snapshot: WaterBackupSnapshot): File {
        val directory = File(appContext.filesDir, "database-safety")
        require(directory.exists() || directory.mkdirs()) {
            "Unable to create local safety backup directory."
        }

        val file = File(
            directory,
            "drinkwater_before_restore_${System.currentTimeMillis()}.json"
        )

        file.writeText(createBackupJson(snapshot).toString(), Charsets.UTF_8)

        require(file.exists() && file.length() > 0L) {
            "Local safety backup could not be created."
        }

        return file
    }

    private fun createBackupJson(snapshot: WaterBackupSnapshot): JSONObject {
        val days = JSONArray()
        snapshot.days.forEach { day ->
            days.put(
                JSONObject().apply {
                    put("date", day.date)
                    put("intakeMl", day.intakeMl)
                    put("goalMl", day.goalMl ?: JSONObject.NULL)
                }
            )
        }

        val activities = JSONArray()
        snapshot.dailyActivity.forEach { activity ->
            activities.put(
                JSONObject().apply {
                    put("date", activity.date)
                    put("steps", activity.steps)
                    put("walkingMinutes", activity.walkingMinutes)
                    put("updatedAt", activity.updatedAt)
                }
            )
        }

        val profile = snapshot.healthProfile?.let {
            JSONObject().apply {
                put("id", it.id)
                put("name", it.name)
                put("age", it.age)
                put("sex", it.sex)
                put("heightCm", it.heightCm)
                put("weightKg", it.weightKg)
                put("activityLevel", it.activityLevel)
                put("averageDailySteps", it.averageDailySteps)
                put("waterGoalOverrideMl", it.waterGoalOverrideMl ?: JSONObject.NULL)
                put("stepsGoalOverride", it.stepsGoalOverride ?: JSONObject.NULL)
                put("walkingMinutesOverride", it.walkingMinutesOverride ?: JSONObject.NULL)
                put("updatedAt", it.updatedAt)
            }
        } ?: JSONObject.NULL

        return JSONObject().apply {
            put("schemaVersion", SCHEMA_VERSION)
            put("appId", APP_ID)
            put("exportedAt", System.currentTimeMillis())
            put("days", days)
            put("healthProfile", profile)
            put("dailyActivity", activities)
        }
    }

    private fun parseBackupJson(json: String): ParsedBackup {
        val root = JSONObject(json)

        require(root.optString("appId") == APP_ID) {
            "This backup belongs to a different application."
        }

        return when (root.optInt("schemaVersion", -1)) {
            1 -> ParsedBackup.V1(parseDays(root))
            2 -> {
                require(root.has("healthProfile") && root.has("dailyActivity")) {
                    "Schema v2 backup is incomplete."
                }

                val profileValue = root.get("healthProfile")
                val profile = if (profileValue == JSONObject.NULL) {
                    null
                } else {
                    parseProfile(
                        profileValue as? JSONObject
                            ?: throw IllegalArgumentException("Invalid health profile.")
                    )
                }

                val activityArray = root.optJSONArray("dailyActivity")
                    ?: throw IllegalArgumentException("Daily activity data is missing.")

                require(activityArray.length() <= 10000) {
                    "Backup contains too many activity records."
                }

                val activities = ArrayList<DailyActivityEntity>(activityArray.length())
                val activityDates = HashSet<String>()

                for (index in 0 until activityArray.length()) {
                    val item = activityArray.optJSONObject(index)
                        ?: throw IllegalArgumentException("Invalid activity record.")

                    require(item.has("date") && item.has("steps") &&
                        item.has("walkingMinutes") && item.has("updatedAt")) {
                        "Incomplete activity record."
                    }

                    val date = item.getString("date")
                    validateDate(date)

                    require(activityDates.add(date)) {
                        "Duplicate activity date in backup."
                    }

                    val steps = item.getInt("steps")
                    val minutes = item.getInt("walkingMinutes")
                    val updatedAt = item.getLong("updatedAt")

                    require(steps in 0..100000) { "Invalid activity steps." }
                    require(minutes in 0..1440) { "Invalid walking minutes." }
                    require(updatedAt > 0L) { "Invalid activity timestamp." }

                    activities.add(
                        DailyActivityEntity(
                            date = date,
                            steps = steps,
                            walkingMinutes = minutes,
                            updatedAt = updatedAt
                        )
                    )
                }

                ParsedBackup.V2(
                    WaterBackupSnapshot(
                        days = parseDays(root),
                        healthProfile = profile,
                        dailyActivity = activities
                    )
                )
            }

            else -> throw IllegalArgumentException("Unsupported backup format.")
        }
    }

    private fun parseDays(root: JSONObject): List<WaterDayEntity> {
        val array = root.optJSONArray("days")
            ?: throw IllegalArgumentException("Backup history is missing.")

        require(array.length() in 1..10000) {
            "Backup history is empty or contains too many records."
        }

        val days = ArrayList<WaterDayEntity>(array.length())
        val dates = HashSet<String>()

        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index)
                ?: throw IllegalArgumentException("Invalid water history record.")

            require(item.has("date") && item.has("intakeMl") && item.has("goalMl")) {
                "Incomplete water history record."
            }

            val date = item.getString("date")
            validateDate(date)

            require(dates.add(date)) {
                "Duplicate water history date in backup."
            }

            val intake = item.getInt("intakeMl")
            require(intake in 0..20000) { "Invalid water intake value." }

            val goalValue = item.get("goalMl")
            val goal = if (goalValue == JSONObject.NULL) {
                null
            } else {
                (goalValue as? Number)?.toInt()
                    ?: throw IllegalArgumentException("Invalid water goal.")
            }

            require(goal == null || goal in 250..10000) {
                "Invalid water goal value."
            }

            days.add(
                WaterDayEntity(
                    date = date,
                    intakeMl = intake,
                    goalMl = goal
                )
            )
        }

        return days
    }

    private fun parseProfile(item: JSONObject): HealthProfileEntity {
        val required = listOf(
            "id", "name", "age", "sex", "heightCm", "weightKg",
            "activityLevel", "averageDailySteps", "waterGoalOverrideMl",
            "stepsGoalOverride", "walkingMinutesOverride", "updatedAt"
        )

        require(required.all { item.has(it) }) {
            "Health profile is incomplete."
        }

        val id = item.getInt("id")
        val name = item.getString("name")
        val age = item.getInt("age")
        val sex = item.getString("sex")
        val height = item.getInt("heightCm")
        val weight = item.getDouble("weightKg")
        val activity = item.getString("activityLevel")
        val averageSteps = item.getInt("averageDailySteps")
        val updatedAt = item.getLong("updatedAt")

        require(id == 1) { "Invalid health profile ID." }
        require(name.length <= 100) { "Health profile name is too long." }
        require(age in 13..110) { "Invalid profile age." }
        require(sex in setOf("Male", "Female", "Prefer not to say")) {
            "Invalid profile sex."
        }
        require(height in 100..250) { "Invalid profile height." }
        require(weight.isFinite() && weight in 30.0..200.0) {
            "Invalid profile weight."
        }
        require(activity in setOf(
            "Sedentary", "Lightly Active", "Moderately Active", "Very Active"
        )) { "Invalid activity level." }
        require(averageSteps in 0..50000) { "Invalid average daily steps." }
        require(updatedAt > 0L) { "Invalid profile timestamp." }

        fun nullableInt(key: String): Int? {
            val value = item.get(key)
            return if (value == JSONObject.NULL) null
            else (value as? Number)?.toInt()
                ?: throw IllegalArgumentException("Invalid $key value.")
        }

        val waterOverride = nullableInt("waterGoalOverrideMl")
        val stepsOverride = nullableInt("stepsGoalOverride")
        val minutesOverride = nullableInt("walkingMinutesOverride")

        require(waterOverride == null || waterOverride in 1500..4000) {
            "Invalid water goal override."
        }
        require(stepsOverride == null || stepsOverride in 500..30000) {
            "Invalid steps goal override."
        }
        require(minutesOverride == null || minutesOverride in 1..600) {
            "Invalid walking minutes override."
        }

        return HealthProfileEntity(
            id = id,
            name = name,
            age = age,
            sex = sex,
            heightCm = height,
            weightKg = weight,
            activityLevel = activity,
            averageDailySteps = averageSteps,
            waterGoalOverrideMl = waterOverride,
            stepsGoalOverride = stepsOverride,
            walkingMinutesOverride = minutesOverride,
            updatedAt = updatedAt
        )
    }

    private fun validateDate(date: String) {
        try {
            require(LocalDate.parse(date).toString() == date)
        } catch (_: Exception) {
            throw IllegalArgumentException("Invalid date in backup: $date")
        }
    }

    private fun findBackupFile(token: String): RemoteFile? {
        val query = "name='$FILE_NAME' and " +
            "'appDataFolder' in parents and trashed=false"

        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val url = URL(
            "$API?spaces=appDataFolder&q=$encodedQuery&pageSize=10" +
                "&orderBy=modifiedTime%20desc&fields=files(id,name,modifiedTime)"
        )

        val response = request(url, "GET", token)
        val files = response.optJSONArray("files") ?: return null
        if (files.length() == 0) return null

        val file = files.getJSONObject(0)
        return RemoteFile(
            id = file.getString("id"),
            modifiedTime = file.optString("modifiedTime").takeIf { it.isNotBlank() }
        )
    }

    private fun createRemoteFile(token: String, payload: String): JSONObject {
        val boundary = "DrinkWaterBoundary${System.currentTimeMillis()}"
        val metadata = JSONObject().apply {
            put("name", FILE_NAME)
            put("mimeType", "application/json")
            put("parents", JSONArray().put("appDataFolder"))
        }

        val body = buildString {
            append("--$boundary\r\n")
            append("Content-Type: application/json; charset=UTF-8\r\n\r\n")
            append(metadata.toString())
            append("\r\n--$boundary\r\n")
            append("Content-Type: application/json; charset=UTF-8\r\n\r\n")
            append(payload)
            append("\r\n--$boundary--\r\n")
        }

        return request(
            URL("$UPLOAD_API?uploadType=multipart&fields=id,name,modifiedTime"),
            "POST",
            token,
            body,
            "multipart/related; boundary=$boundary"
        )
    }

    private fun updateRemoteFile(
        token: String,
        fileId: String,
        payload: String
    ): JSONObject {
        val encodedId = URLEncoder.encode(fileId, "UTF-8")
        return request(
            URL("$UPLOAD_API/$encodedId?uploadType=media&fields=id,name,modifiedTime"),
            "PATCH",
            token,
            payload,
            "application/json; charset=UTF-8"
        )
    }

    private fun downloadRemoteFile(token: String, fileId: String): String {
        val encodedId = URLEncoder.encode(fileId, "UTF-8")
        return requestText(URL("$API/$encodedId?alt=media"), "GET", token)
    }

    private fun request(
        url: URL,
        method: String,
        token: String,
        body: String? = null,
        contentType: String? = null
    ): JSONObject = JSONObject(requestText(url, method, token, body, contentType))

    private fun requestText(
        url: URL,
        method: String,
        token: String,
        body: String? = null,
        contentType: String? = null
    ): String {
        val connection = url.openConnection() as HttpURLConnection

        try {
            connection.requestMethod = method
            connection.connectTimeout = 20000
            connection.readTimeout = 30000
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("Accept", "application/json")

            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty(
                    "Content-Type",
                    contentType ?: "application/json"
                )
                connection.outputStream.use {
                    it.write(body.toByteArray(Charsets.UTF_8))
                }
            }

            val status = connection.responseCode
            val stream = if (status in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }

            val response = stream?.use {
                BufferedReader(InputStreamReader(it, Charsets.UTF_8)).readText()
            }.orEmpty()

            if (status !in 200..299) {
                throw IllegalStateException(
                    "Google Drive request failed (HTTP $status): ${response.take(500)}"
                )
            }

            return response
        } finally {
            connection.disconnect()
        }
    }
}
