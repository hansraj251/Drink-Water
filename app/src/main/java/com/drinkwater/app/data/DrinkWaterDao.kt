package com.drinkwater.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface DrinkWaterDao {

    @Query("SELECT * FROM daily_water ORDER BY date DESC")
    suspend fun getWaterHistory(): List<WaterDayEntity>

    @Query("SELECT * FROM daily_water WHERE date = :date LIMIT 1")
    suspend fun getWaterDay(date: String): WaterDayEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertWaterDay(day: WaterDayEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun replaceWaterDay(day: WaterDayEntity)

    @Query("""
        UPDATE daily_water
        SET intake_ml = :intakeMl
        WHERE date = :date
    """)
    suspend fun updateWaterIntake(date: String, intakeMl: Int)

    @Query("""
        UPDATE daily_water
        SET goal_ml = :goalMl
        WHERE date = :date
    """)
    suspend fun updateWaterGoal(date: String, goalMl: Int)

    @Query("DELETE FROM daily_water")
    suspend fun deleteAllWaterHistory()

    @Query("SELECT * FROM health_profile WHERE id = 1 LIMIT 1")
    suspend fun getHealthProfile(): HealthProfileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveHealthProfile(profile: HealthProfileEntity)

    @Query("DELETE FROM health_profile")
    suspend fun deleteHealthProfile()

    @Query("SELECT * FROM daily_activity ORDER BY date DESC")
    suspend fun getAllDailyActivity(): List<DailyActivityEntity>

    @Query("SELECT * FROM daily_activity WHERE date = :date LIMIT 1")
    suspend fun getDailyActivity(date: String): DailyActivityEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveDailyActivity(activity: DailyActivityEntity)

    @Query("DELETE FROM daily_activity")
    suspend fun deleteAllDailyActivity()
}
