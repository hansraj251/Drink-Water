package com.drinkwater.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class WaterDay(
    val date: String,
    val intakeMl: Int,
    val goalMl: Int?
) {
    val percentage: Int
        get() = if (goalMl == null || goalMl <= 0) {
            0
        } else {
            ((intakeMl.toFloat() / goalMl) * 100).toInt()
        }

    val completed: Boolean
        get() = goalMl != null && intakeMl >= goalMl
}

class WaterHistoryStore private constructor(context: Context) :
    SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val DATABASE_NAME = "water_history.db"
        private const val DATABASE_VERSION = 1

        private const val TABLE = "daily_water"
        private const val COL_DATE = "date"
        private const val COL_INTAKE = "intake_ml"
        private const val COL_GOAL = "goal_ml"

        @Volatile
        private var INSTANCE: WaterHistoryStore? = null

        fun getInstance(context: Context): WaterHistoryStore {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: WaterHistoryStore(
                    context.applicationContext
                ).also { INSTANCE = it }
            }
        }

        fun todayKey(): String =
            SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE (
                $COL_DATE TEXT PRIMARY KEY NOT NULL,
                $COL_INTAKE INTEGER NOT NULL DEFAULT 0,
                $COL_GOAL INTEGER
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(
        db: SQLiteDatabase,
        oldVersion: Int,
        newVersion: Int
    ) {
        // Future database migrations will be added here.
    }

    @Synchronized
    fun migrateLegacyData(context: Context) {
        val prefs = context.getSharedPreferences(
            "water_tracker",
            Context.MODE_PRIVATE
        )

        if (prefs.getBoolean("history_migration_done", false)) {
            return
        }

        val legacyDate = prefs.getString("intake_date", "") ?: ""
        val legacyIntake = prefs.getInt("intake_ml", 0).coerceAtLeast(0)
        val currentGoal = prefs.getInt("daily_goal_ml", 2000)
            .coerceIn(250, 10000)

        val today = todayKey()

        writableDatabase.beginTransaction()
        try {
            // Preserve the exact legacy date and intake.
            // Historical goal is unknown unless the record is from today.
            if (
                legacyDate.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) &&
                legacyIntake > 0
            ) {
                val legacyGoal =
                    if (legacyDate == today) currentGoal else null

                insertDay(
                    legacyDate,
                    legacyIntake,
                    legacyGoal
                )
            }

            // Ensure today's record exists with today's current goal.
            insertDay(
                today,
                0,
                currentGoal
            )

            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }

        prefs.edit()
            .putBoolean("history_migration_done", true)
            .apply()
    }

    @Synchronized
    fun getOrCreateToday(goalMl: Int): WaterDay {
        val today = todayKey()
        insertDay(today, 0, goalMl.coerceIn(250, 10000))
        return getDay(today)!!
    }

    @Synchronized
    fun addIntake(date: String, amountMl: Int, goalMl: Int): WaterDay {
        require(amountMl >= 0)

        insertDay(date, 0, goalMl.coerceIn(250, 10000))

        writableDatabase.execSQL(
            """
            UPDATE $TABLE
            SET $COL_INTAKE = $COL_INTAKE + ?
            WHERE $COL_DATE = ?
            """.trimIndent(),
            arrayOf(amountMl, date)
        )

        return getDay(date)!!
    }

    @Synchronized
    fun setIntake(date: String, intakeMl: Int, goalMl: Int): WaterDay {
        require(intakeMl >= 0)

        insertDay(date, 0, goalMl.coerceIn(250, 10000))

        writableDatabase.execSQL(
            """
            UPDATE $TABLE
            SET $COL_INTAKE = ?
            WHERE $COL_DATE = ?
            """.trimIndent(),
            arrayOf(intakeMl, date)
        )

        return getDay(date)!!
    }

    @Synchronized
    fun setGoal(date: String, goalMl: Int): WaterDay {
        require(goalMl in 250..10000)

        insertDay(date, 0, goalMl)

        writableDatabase.execSQL(
            """
            UPDATE $TABLE
            SET $COL_GOAL = ?
            WHERE $COL_DATE = ?
            """.trimIndent(),
            arrayOf(goalMl, date)
        )

        return getDay(date)!!
    }

    @Synchronized
    fun getDay(date: String): WaterDay? {
        readableDatabase.query(
            TABLE,
            arrayOf(COL_DATE, COL_INTAKE, COL_GOAL),
            "$COL_DATE = ?",
            arrayOf(date),
            null,
            null,
            null
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null

            val goalIndex = cursor.getColumnIndexOrThrow(COL_GOAL)

            return WaterDay(
                date = cursor.getString(
                    cursor.getColumnIndexOrThrow(COL_DATE)
                ),
                intakeMl = cursor.getInt(
                    cursor.getColumnIndexOrThrow(COL_INTAKE)
                ),
                goalMl = if (cursor.isNull(goalIndex)) {
                    null
                } else {
                    cursor.getInt(goalIndex)
                }
            )
        }
    }

    @Synchronized
    fun getHistory(): List<WaterDay> {
        val result = mutableListOf<WaterDay>()

        readableDatabase.query(
            TABLE,
            arrayOf(COL_DATE, COL_INTAKE, COL_GOAL),
            null,
            null,
            null,
            null,
            "$COL_DATE DESC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val goalIndex = cursor.getColumnIndexOrThrow(COL_GOAL)

                result.add(
                    WaterDay(
                        date = cursor.getString(
                            cursor.getColumnIndexOrThrow(COL_DATE)
                        ),
                        intakeMl = cursor.getInt(
                            cursor.getColumnIndexOrThrow(COL_INTAKE)
                        ),
                        goalMl = if (cursor.isNull(goalIndex)) {
                            null
                        } else {
                            cursor.getInt(goalIndex)
                        }
                    )
                )
            }
        }

        return result
    }

    private fun insertDay(
        date: String,
        intakeMl: Int,
        goalMl: Int?
    ) {
        writableDatabase.execSQL(
            """
            INSERT OR IGNORE INTO $TABLE
                ($COL_DATE, $COL_INTAKE, $COL_GOAL)
            VALUES (?, ?, ?)
            """.trimIndent(),
            arrayOf(date, intakeMl, goalMl)
        )
    }


    /**
     * Replaces history atomically after validating the complete backup.
     * Caller must create a local safety copy before invoking this method.
     */
    @Synchronized
    fun replaceHistoryFromBackup(days: List<WaterDay>) {
        require(days.size <= 10000) { "Backup contains too many records." }

        val datePattern = Regex("""\d{4}-\d{2}-\d{2}""")
        val formatter = java.time.format.DateTimeFormatter
            .ofPattern("uuuu-MM-dd", java.util.Locale.US)
            .withResolverStyle(java.time.format.ResolverStyle.STRICT)

        val seenDates = HashSet<String>()

        days.forEach { day ->
            require(datePattern.matches(day.date)) {
                "Invalid date format in backup."
            }
            try {
                java.time.LocalDate.parse(day.date, formatter)
            } catch (e: Exception) {
                throw IllegalArgumentException("Invalid date in backup.", e)
            }
            require(seenDates.add(day.date)) {
                "Backup contains duplicate dates."
            }
            require(day.intakeMl in 0..20000) {
                "Invalid intake amount in backup."
            }
            require(day.goalMl == null || day.goalMl in 250..10000) {
                "Invalid daily goal in backup."
            }
        }

        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete(TABLE, null, null)
            days.forEach { day ->
                insertDay(day.date, day.intakeMl, day.goalMl)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }


    /**
     * Closes SQLite before copying so pending database writes are flushed.
     * Restore must stop if this method fails.
     */
    @Synchronized
    fun createLocalSafetyCopy(context: Context): java.io.File {
        val appContext = context.applicationContext
        val databaseFile = appContext.getDatabasePath(DATABASE_NAME)

        require(databaseFile.exists()) {
            "Water history database does not exist."
        }

        // Close the helper connection before copying the database file.
        close()

        val backupDirectory = java.io.File(
            appContext.filesDir,
            "backup-safety"
        )

        check(backupDirectory.exists() || backupDirectory.mkdirs()) {
            "Could not create local safety backup directory."
        }

        val timestamp = SimpleDateFormat(
            "yyyyMMdd-HHmmss",
            Locale.US
        ).format(Date())

        val backupFile = java.io.File(
            backupDirectory,
            "water_history-$timestamp.db"
        )

        databaseFile.copyTo(backupFile, overwrite = false)

        check(backupFile.exists() && backupFile.length() > 0L) {
            "Local safety backup could not be verified."
        }

        return backupFile
    }

}
