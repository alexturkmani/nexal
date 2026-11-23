package com.nexal.app.domain.model

import kotlinx.serialization.Serializable

/** One food the AI found in a meal photo. */
@Serializable
data class PhotoFood(
    val name: String = "",
    val servingSize: String = "",
    val macros: MacroNutrients = MacroNutrients()
)

@Serializable
data class PhotoMealResult(
    val mealName: String = "",
    val foods: List<PhotoFood> = emptyList(),
    val totalMacros: MacroNutrients = MacroNutrients(),
    val confidence: String = "medium",
    val notes: String = ""
)

@Serializable
data class LiftTarget(val exercise: String = "", val target: String = "")

@Serializable
data class WeeklyCheckIn(
    val headline: String = "",
    val score: Int = 0,
    val wins: List<String> = emptyList(),
    val focus: List<String> = emptyList(),
    val calorieAdjustment: Int = 0,
    val liftTargets: List<LiftTarget> = emptyList(),
    val summary: String = "",
    /** Set by the app when the check-in is saved, epoch millis. */
    val createdAt: Long = 0
)

@Serializable
data class WeeklyNutrition(
    val targetCalories: Int = 0,
    val targetProtein: Int = 0,
    val avgCalories: Int = 0,
    val avgProtein: Int = 0,
    val daysLogged: Int = 0
)

@Serializable
data class WeeklyCheckInInput(
    val goal: String,
    val workoutsPlanned: Int,
    val workouts: List<WorkoutLog>,
    val nutrition: WeeklyNutrition,
    val weights: List<WeightEntry>
)
