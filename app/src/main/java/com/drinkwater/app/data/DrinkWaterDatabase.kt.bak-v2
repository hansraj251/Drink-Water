package com.drinkwater.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        WaterDayEntity::class,
        HealthProfileEntity::class,
        DailyActivityEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class DrinkWaterDatabase : RoomDatabase() {

    abstract fun dao(): DrinkWaterDao

    companion object {
        private const val DATABASE_NAME = "water_history.db"

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {

                // Existing daily_water table is intentionally preserved.
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS health_profile (
                        id INTEGER NOT NULL,
                        name TEXT NOT NULL,
                        age INTEGER NOT NULL,
                        sex TEXT NOT NULL,
                        heightCm INTEGER NOT NULL,
                        weightKg REAL NOT NULL,
                        activityLevel TEXT NOT NULL,
                        averageDailySteps INTEGER NOT NULL,
                        waterGoalOverrideMl INTEGER,
                        stepsGoalOverride INTEGER,
                        walkingMinutesOverride INTEGER,
                        updatedAt INTEGER NOT NULL,
                        PRIMARY KEY(id)
                    )
                """)

                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS daily_activity (
                        date TEXT NOT NULL,
                        steps INTEGER NOT NULL,
                        walkingMinutes INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        PRIMARY KEY(date)
                    )
                """)
            }
        }

        @Volatile
        private var INSTANCE: DrinkWaterDatabase? = null

        fun getInstance(context: Context): DrinkWaterDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    DrinkWaterDatabase::class.java,
                    DATABASE_NAME
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
