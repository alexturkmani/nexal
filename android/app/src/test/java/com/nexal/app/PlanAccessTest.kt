package com.nexal.app

import com.nexal.app.util.PlanAccess
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class PlanAccessTest {
    private val start = "2026-11-02T09:30:00.000Z"

    @Test
    fun `week counts from the plan start`() {
        assertEquals(1, PlanAccess.workoutWeek(start, LocalDate.of(2026, 11, 2), ZoneOffset.UTC))
        assertEquals(1, PlanAccess.workoutWeek(start, LocalDate.of(2026, 11, 8), ZoneOffset.UTC))
        assertEquals(2, PlanAccess.workoutWeek(start, LocalDate.of(2026, 11, 9), ZoneOffset.UTC))
        assertEquals(3, PlanAccess.workoutWeek(start, LocalDate.of(2026, 11, 20), ZoneOffset.UTC))
    }

    @Test
    fun `plain dates and bad dates`() {
        assertEquals(2, PlanAccess.workoutWeek("2026-11-02", LocalDate.of(2026, 11, 10), ZoneOffset.UTC))
        assertNull(PlanAccess.workoutWeek("", LocalDate.of(2026, 11, 10)))
        assertNull(PlanAccess.workoutWeek("soon", LocalDate.of(2026, 11, 10)))
    }

    @Test
    fun `free users keep week 1 only`() {
        assertFalse(PlanAccess.isWorkoutLocked(false, "2026-11-02", LocalDate.of(2026, 11, 8)))
        assertTrue(PlanAccess.isWorkoutLocked(false, "2026-11-02", LocalDate.of(2026, 11, 9)))
        assertTrue(PlanAccess.isWorkoutLocked(false, "", LocalDate.of(2026, 11, 9)))
    }

    @Test
    fun `premium is never locked`() {
        assertFalse(PlanAccess.isWorkoutLocked(true, "2026-01-01", LocalDate.of(2026, 11, 9)))
        assertFalse(PlanAccess.isWorkoutLocked(true, "", LocalDate.of(2026, 11, 9)))
    }

    @Test
    fun `free users see breakfast only`() {
        assertEquals(1, PlanAccess.visibleMealCount(false, 5))
        assertEquals(0, PlanAccess.visibleMealCount(false, 0))
        assertEquals(5, PlanAccess.visibleMealCount(true, 5))
    }
}
