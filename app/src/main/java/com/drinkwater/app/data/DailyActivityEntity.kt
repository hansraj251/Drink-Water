package com.drinkwater.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "daily_activity")
data class DailyActivityEntity(
    @PrimaryKey
    val date: String,

    val steps: Int = 0,
    val walkingMinutes: Int = 0,

    val updatedAt: Long = System.currentTimeMillis()
)
