package com.drinkwater.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "health_profile")
data class HealthProfileEntity(
    @PrimaryKey
    val id: Int = 1,

    val name: String = "",
    val age: Int,
    val sex: String,
    val heightCm: Int,
    val weightKg: Double,
    val activityLevel: String,
    val averageDailySteps: Int,

    val waterGoalOverrideMl: Int? = null,
    val stepsGoalOverride: Int? = null,
    val walkingMinutesOverride: Int? = null,

    val updatedAt: Long = System.currentTimeMillis()
)
