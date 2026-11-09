package com.drinkwater.app.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "daily_water")
data class WaterDayEntity(
    @PrimaryKey
    val date: String,

    @ColumnInfo(name = "intake_ml", defaultValue = "0")
    val intakeMl: Int = 0,

    @ColumnInfo(name = "goal_ml")
    val goalMl: Int? = null
)
