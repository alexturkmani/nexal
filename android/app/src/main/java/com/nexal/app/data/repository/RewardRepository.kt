package com.nexal.app.data.repository

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

enum class RewardType(val xp: Int, val title: String, val message: String) {
    WORKOUT(50, "Workout complete", "That's another session in the bank."),
    FAST(40, "Fast complete", "You reached your fasting goal."),
    CARDIO(25, "Cardio logged", "Every session adds up."),
    MEAL(10, "Meal logged", "Tracking is what makes the plan work."),
    WATER(15, "Water goal hit", "Fully hydrated for today."),
}

data class RewardProgress(
    val xp: Int = 0,
    val streakDays: Int = 0,
    /** Premium: a streak freeze is ready to cover one missed day this week. */
    val freezeReady: Boolean = false
) {
    val level: Int get() = RewardMath.levelFor(xp)
    val levelProgress: Float get() = RewardMath.levelProgress(xp)
}

data class RewardEvent(
    val type: RewardType,
    val xpGained: Int,
    val progress: RewardProgress,
    val leveledUp: Boolean,
)

/** Pure rules, kept apart from storage so they can be unit tested. */
object RewardMath {
    private const val XP_PER_LEVEL_STEP = 100

    /** Total XP needed to reach [level]; each level costs 100 XP more than the last. */
    fun xpForLevel(level: Int): Int = XP_PER_LEVEL_STEP * (level - 1) * level / 2

    fun levelFor(xp: Int): Int {
        var level = 1
        while (xp >= xpForLevel(level + 1)) level++
        return level
    }

    fun levelProgress(xp: Int): Float {
        val level = levelFor(xp)
        val start = xpForLevel(level)
        return (xp - start).toFloat() / (xpForLevel(level + 1) - start)
    }

    /**
     * Like [nextStreak], but a single missed day is forgiven when a streak freeze
     * is available. Returns the new streak and whether the freeze was spent.
     */
    fun nextStreakWithFreeze(
        current: Int,
        lastActive: LocalDate?,
        today: LocalDate,
        freezeAvailable: Boolean
    ): Pair<Int, Boolean> =
        if (freezeAvailable && current > 0 && lastActive == today.minusDays(2)) current + 1 to true
        else nextStreak(current, lastActive, today) to false

    /** Monday of [date]'s week: freezes refill once a week. */
    fun weekKey(date: LocalDate): String = date.minusDays((date.dayOfWeek.value - 1).toLong()).toString()

    /** A streak grows on consecutive days, holds within a day and restarts after a gap. */
    fun nextStreak(current: Int, lastActive: LocalDate?, today: LocalDate): Int = when {
        lastActive == null -> 1
        lastActive == today -> current.coerceAtLeast(1)
        lastActive == today.minusDays(1) -> current + 1
        else -> 1
    }
}

/**
 * XP and streaks, stored on this device. Each reward type pays out once per
 * [dedupeKey], so repeating an action cannot farm XP or re-trigger the pop-up.
 */
@Singleton
class RewardRepository @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs = context.getSharedPreferences("nexal_rewards", Context.MODE_PRIVATE)

    private val _progress = MutableStateFlow(
        RewardProgress(prefs.getInt(KEY_XP, 0), prefs.getInt(KEY_STREAK, 0))
    )
    val progress: StateFlow<RewardProgress> = _progress.asStateFlow()

    /** Premium members get one streak freeze a week. Set from the signed-in account. */
    @Volatile
    var premium: Boolean = false
        set(value) {
            field = value
            _progress.value = _progress.value.copy(freezeReady = freezeAvailable(LocalDate.now()))
        }

    private fun freezeAvailable(today: LocalDate): Boolean =
        premium && prefs.getString(KEY_FREEZE_WEEK, null) != RewardMath.weekKey(today)

    private val _pending = MutableStateFlow<RewardEvent?>(null)
    val pending: StateFlow<RewardEvent?> = _pending.asStateFlow()

    @Synchronized
    fun award(type: RewardType, dedupeKey: String = LocalDate.now().toString()) {
        val claimKey = "claimed_${type.name}"
        if (prefs.getString(claimKey, null) == dedupeKey) return

        val today = LocalDate.now()
        val before = _progress.value
        val lastActive = prefs.getString(KEY_LAST_ACTIVE, null)?.let(LocalDate::parse)
        val (streak, usedFreeze) = RewardMath.nextStreakWithFreeze(
            before.streakDays, lastActive, today, freezeAvailable(today)
        )
        val editor = prefs.edit()
        if (usedFreeze) editor.putString(KEY_FREEZE_WEEK, RewardMath.weekKey(today))
        editor
            .putInt(KEY_XP, before.xp + type.xp)
            .putInt(KEY_STREAK, streak)
            .putString(KEY_LAST_ACTIVE, today.toString())
            .putString(claimKey, dedupeKey)
            .apply()
        val after = RewardProgress(
            xp = before.xp + type.xp,
            streakDays = streak,
            freezeReady = freezeAvailable(today)
        )

        _progress.value = after
        _pending.value = RewardEvent(type, type.xp, after, after.level > before.level)
    }

    fun dismiss() {
        _pending.value = null
    }

    private companion object {
        const val KEY_XP = "xp"
        const val KEY_STREAK = "streak"
        const val KEY_LAST_ACTIVE = "last_active"
        const val KEY_FREEZE_WEEK = "freeze_used_week"
    }
}
