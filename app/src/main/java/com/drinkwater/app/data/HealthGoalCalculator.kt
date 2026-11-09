package com.drinkwater.app.data

import kotlin.math.ceil
import kotlin.math.roundToInt

data class DailyGoals(
    val waterMl: Int,
    val steps: Int,
    val walkingMinutes: Int
)

object HealthGoalCalculator {

    fun calculate(profile: HealthProfileEntity): DailyGoals {
        val water = profile.waterGoalOverrideMl
            ?: calculateWaterGoal(profile)

        val steps = profile.stepsGoalOverride
            ?: calculateStepsGoal(profile)

        val minutes = profile.walkingMinutesOverride
            ?: calculateWalkingMinutes(steps)

        return DailyGoals(
            waterMl = water,
            steps = steps,
            walkingMinutes = minutes
        )
    }

    private fun calculateWaterGoal(profile: HealthProfileEntity): Int {
        val weight = profile.weightKg.coerceIn(30.0, 200.0)

        val activityAdjustment = when (profile.activityLevel) {
            "Lightly Active" -> 250
            "Moderately Active" -> 500
            "Very Active" -> 750
            else -> 0
        }

        val estimate = (weight * 30.0).roundToInt() + activityAdjustment
        val bounded = estimate.coerceIn(1500, 4000)

        return ((bounded / 50.0).roundToInt() * 50)
            .coerceIn(1500, 4000)
    }

    private fun calculateStepsGoal(profile: HealthProfileEntity): Int {
        val initialTarget = when (profile.activityLevel) {
            "Lightly Active" -> 2000
            "Moderately Active" -> 2500
            "Very Active" -> 3000
            else -> 1500
        }

        val average = profile.averageDailySteps.coerceIn(0, 50000)

        // Start with a modest target when no step history is available.
        // Otherwise increase the existing average gradually.
        val target = if (average == 0) {
            initialTarget
        } else {
            average + maxOf(500, (average * 0.10).roundToInt())
        }

        val ageLimit = if (profile.age >= 65) 10000 else 12000
        return target.coerceAtMost(ageLimit)
    }

    private fun calculateWalkingMinutes(steps: Int): Int {
        // Approximation: 100 walking steps per minute.
        return ceil(steps / 100.0).toInt()
    }
}
