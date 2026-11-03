package com.drinkwater.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class DriveBackupManager(
    context: Context,
    private val store: WaterHistoryStore
) {
    private val appContext = context.applicationContext

    companion object {
        private const val FILE_NAME = "drinkwater-backup.json"
        private const val APP_ID = "com.hansraj251.drinkwater"
        private const val SCHEMA_VERSION = 1

        private const val API =
            "https://www.googleapis.com/drive/v3/files"

        private const val UPLOAD_API =
            "https://www.googleapis.com/upload/drive/v3/files"
    }

    data class BackupResult(
        val fileId: String,
        val modifiedTime: String?
    )

    private data class RemoteFile(
        val id: String,
        val modifiedTime: String?
    )

    suspend fun backup(accessToken: String): BackupResult =
        withContext(Dispatchers.IO) {
            require(accessToken.isNotBlank()) {
                "Google Drive authorization is required."
            }

            val days = store.getHistory()
            require(days.isNotEmpty()) {
                "There is no water history to back up."
            }

            val payload = createBackupJson(days).toString()
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
            val days = parseBackupJson(json)

            // Never replace local history unless the safety copy succeeds.
            store.createLocalSafetyCopy(appContext)

            store.replaceHistoryFromBackup(days)
            days.size
        }

    private fun createBackupJson(
        days: List<WaterDay>
    ): JSONObject {
        val array = JSONArray()

        days.forEach { day ->
            val item = JSONObject().apply {
                put("date", day.date)
                put("intakeMl", day.intakeMl)
                put(
                    "goalMl",
                    day.goalMl ?: JSONObject.NULL
                )
            }
            array.put(item)
        }

        return JSONObject().apply {
            put("schemaVersion", SCHEMA_VERSION)
            put("appId", APP_ID)
            put("exportedAt", System.currentTimeMillis())
            put("days", array)
        }
    }

    private fun parseBackupJson(json: String): List<WaterDay> {
        val root = JSONObject(json)

        require(root.optInt("schemaVersion", -1) == SCHEMA_VERSION) {
            "Unsupported backup format."
        }

        require(root.optString("appId") == APP_ID) {
            "This backup belongs to a different application."
        }

        val array = root.optJSONArray("days")
            ?: throw IllegalArgumentException("Backup history is missing.")

        require(array.length() in 1..10000) {
            "Backup history is empty or contains too many records."
        }

        val days = ArrayList<WaterDay>(array.length())

        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index)
                ?: throw IllegalArgumentException(
                    "Invalid history record at position ${index + 1}."
                )

            require(item.has("date") && item.has("intakeMl") &&
                item.has("goalMl")) {
                "Incomplete history record."
            }

            val date = item.getString("date")
            val intake = item.getInt("intakeMl")

            val goalValue = item.get("goalMl")
            val goal = if (goalValue == JSONObject.NULL) {
                null
            } else {
                (goalValue as? Number)?.toInt()
                    ?: throw IllegalArgumentException("Invalid goal value.")
            }

            days.add(
                WaterDay(
                    date = date,
                    intakeMl = intake,
                    goalMl = goal
                )
            )
        }

        return days
    }

    private fun findBackupFile(token: String): RemoteFile? {
        val query = "name='$FILE_NAME' and " +
            "'appDataFolder' in parents and trashed=false"

        val encodedQuery = URLEncoder.encode(query, "UTF-8")

        val url = URL(
            "$API?spaces=appDataFolder" +
                "&q=$encodedQuery" +
                "&pageSize=10" +
                "&orderBy=modifiedTime%20desc" +
                "&fields=files(id,name,modifiedTime)"
        )

        val response = request(
            url = url,
            method = "GET",
            token = token
        )

        val files = response.optJSONArray("files")
            ?: return null

        if (files.length() == 0) return null

        val file = files.getJSONObject(0)

        return RemoteFile(
            id = file.getString("id"),
            modifiedTime = file.optString("modifiedTime")
                .takeIf { it.isNotBlank() }
        )
    }

    private fun createRemoteFile(
        token: String,
        payload: String
    ): JSONObject {
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
            url = URL("$UPLOAD_API?uploadType=multipart&fields=id,name,modifiedTime"),
            method = "POST",
            token = token,
            body = body,
            contentType = "multipart/related; boundary=$boundary"
        )
    }

    private fun updateRemoteFile(
        token: String,
        fileId: String,
        payload: String
    ): JSONObject {
        val encodedId = URLEncoder.encode(fileId, "UTF-8")

        return request(
            url = URL(
                "$UPLOAD_API/$encodedId?uploadType=media&fields=id,name,modifiedTime"
            ),
            method = "PATCH",
            token = token,
            body = payload,
            contentType = "application/json; charset=UTF-8"
        )
    }

    private fun downloadRemoteFile(
        token: String,
        fileId: String
    ): String {
        val encodedId = URLEncoder.encode(fileId, "UTF-8")

        return requestText(
            url = URL("$API/$encodedId?alt=media"),
            method = "GET",
            token = token
        )
    }

    private fun request(
        url: URL,
        method: String,
        token: String,
        body: String? = null,
        contentType: String? = null
    ): JSONObject {
        return JSONObject(
            requestText(url, method, token, body, contentType)
        )
    }

    private fun requestText(
        url: URL,
        method: String,
        token: String,
        body: String? = null,
        contentType: String? = null
    ): String {
        val connection = (url.openConnection() as HttpURLConnection)

        try {
            connection.requestMethod = method
            connection.connectTimeout = 20000
            connection.readTimeout = 30000
            connection.setRequestProperty(
                "Authorization",
                "Bearer $token"
            )
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
                BufferedReader(InputStreamReader(it, Charsets.UTF_8))
                    .readText()
            }.orEmpty()

            if (status !in 200..299) {
                throw IllegalStateException(
                    "Google Drive request failed (HTTP $status): " +
                        response.take(500)
                )
            }

            return response
        } finally {
            connection.disconnect()
        }
    }
}
