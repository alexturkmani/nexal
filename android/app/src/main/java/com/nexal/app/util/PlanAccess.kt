package com.nexal.app.util

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * What a non-Premium user can still use of a saved AI plan. Free plans are
 * previews, and plans made during a lapsed subscription stop at the same point,
 * so cancelling never leaves someone with the full paid plan.
 */
object PlanAccess {
    /** Weeks of an AI workout plan open without Premium. */
    const val FREE_WORKOUT_WEEKS = 1

    /** Meals of an AI meal plan open without Premium (breakfast). */
    const val FREE_MEALS = 1

    /** 1-based week of the plan on [today], or null when the start date can't be read. */
    fun workoutWeek(startDate: String, today: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Int? {
        val start = parseDate(startDate, zone) ?: return null
        val days = ChronoUnit.DAYS.between(start, today)
        return if (days < 0) 1 else (days / 7).toInt() + 1
    }

    /** True when the plan's current week needs Premium. Unknown start dates count as locked. */
    fun isWorkoutLocked(isPremium: Boolean, startDate: String, today: LocalDate = LocalDate.now()): Boolean {
        if (isPremium) return false
        val week = workoutWeek(startDate, today) ?: return true
        return week > FREE_WORKOUT_WEEKS
    }

    /** How many of a plan's meals a user can see. */
    fun visibleMealCount(isPremium: Boolean, total: Int): Int =
        if (isPremium) total else minOf(total, FREE_MEALS)

    private fun parseDate(value: String, zone: ZoneId): LocalDate? {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return null
        runCatching { return Instant.parse(trimmed).atZone(zone).toLocalDate() }
        return runCatching { LocalDate.parse(trimmed.take(10)) }.getOrNull()
    }
}
