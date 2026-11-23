package com.nexal.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class StreakFreezeTest {
    private val today = LocalDate.of(2026, 11, 22)

    @Test
    fun `one missed day is covered when a freeze is ready`() {
        assertEquals(6 to true, RewardMath.nextStreakWithFreeze(5, today.minusDays(2), today, freezeAvailable = true))
    }

    @Test
    fun `without a freeze a missed day restarts the streak`() {
        assertEquals(1 to false, RewardMath.nextStreakWithFreeze(5, today.minusDays(2), today, freezeAvailable = false))
    }

    @Test
    fun `a freeze can't save a two-day gap`() {
        assertEquals(1 to false, RewardMath.nextStreakWithFreeze(5, today.minusDays(3), today, freezeAvailable = true))
    }

    @Test
    fun `consecutive days never spend the freeze`() {
        assertEquals(6 to false, RewardMath.nextStreakWithFreeze(5, today.minusDays(1), today, freezeAvailable = true))
    }

    @Test
    fun `freezes refill each Monday`() {
        assertEquals("2026-11-16", RewardMath.weekKey(today))
        assertEquals("2026-11-23", RewardMath.weekKey(today.plusDays(1)))
    }
}
