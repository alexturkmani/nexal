package com.nexal.app.data.repository

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.units.Mass
import com.nexal.app.domain.model.WeightEntry
import com.nexal.app.domain.model.WorkoutLog
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton

/** What a Health Connect sync did, for the confirmation message. */
data class HealthSyncResult(val weightsImported: Int, val workoutsExported: Int, val stepsToday: Long)

/**
 * Premium sync with Android Health Connect: steps and weight come in, Nexal
 * workouts and weigh-ins go out. Steps stay on the device; imported weigh-ins
 * join the user's weight log, which syncs to their Nexal account like any other.
 */
@Singleton
class HealthConnectRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val nutritionRepo: NutritionRepository,
    private val workoutRepo: WorkoutRepository
) {
    val permissions: Set<String> = setOf(
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(WeightRecord::class),
        HealthPermission.getWritePermission(WeightRecord::class),
        HealthPermission.getWritePermission(ExerciseSessionRecord::class),
    )

    private val prefs = context.getSharedPreferences("health_connect", Context.MODE_PRIVATE)

    /** Health Connect is installed (or built in) and usable on this phone. */
    fun isAvailable(): Boolean =
        HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE

    private fun client(): HealthConnectClient? =
        if (isAvailable()) HealthConnectClient.getOrCreate(context) else null

    suspend fun hasAllPermissions(): Boolean {
        val client = client() ?: return false
        return client.permissionController.getGrantedPermissions().containsAll(permissions)
    }

    /** Two-way sync. Safe to repeat: workouts are exported once, weights are matched by date. */
    suspend fun sync(): HealthSyncResult {
        val client = client() ?: throw IllegalStateException("Health Connect isn't available on this phone.")
        if (!hasAllPermissions()) throw IllegalStateException("Allow Nexal in Health Connect first.")

        val zone = ZoneId.systemDefault()
        val now = Instant.now()

        // Weight in: the latest reading per day from the last 30 days that Nexal doesn't have yet.
        val since = now.minus(30, ChronoUnit.DAYS)
        val weights = client.readRecords(
            ReadRecordsRequest(WeightRecord::class, TimeRangeFilter.between(since, now))
        ).records
        val existing = nutritionRepo.observeWeightEntries().first().map { it.date }.toSet()
        val newest = weights
            .groupBy { it.time.atZone(zone).toLocalDate().toString() }
            .mapValues { (_, day) -> day.maxBy { it.time } }
            .filterKeys { it !in existing }
        newest.forEach { (date, record) ->
            nutritionRepo.logWeight(WeightEntry(date = date, weight = record.weight.inKilograms))
        }

        // Weight out: Nexal weigh-ins Health Connect doesn't have.
        val healthDates = weights.map { it.time.atZone(zone).toLocalDate().toString() }.toSet()
        val toExport = nutritionRepo.observeWeightEntries().first().filter { it.date !in healthDates && it.date >= since.atZone(zone).toLocalDate().toString() }
        if (toExport.isNotEmpty()) {
            client.insertRecords(toExport.mapNotNull { entry ->
                val day = runCatching { LocalDate.parse(entry.date.take(10)) }.getOrNull() ?: return@mapNotNull null
                val at = day.atTime(8, 0).atZone(zone)
                WeightRecord(
                    time = at.toInstant(),
                    zoneOffset = at.offset,
                    weight = Mass.kilograms(entry.weight),
                    metadata = Metadata.manualEntry()
                )
            })
        }

        // Workouts out: each logged session once.
        val exported = prefs.getStringSet(KEY_EXPORTED, emptySet()).orEmpty()
        val sessions = workoutRepo.observeLogs().first().filter { it.id.isNotBlank() && it.id !in exported }
        if (sessions.isNotEmpty()) {
            client.insertRecords(sessions.mapNotNull { it.toSession(zone) })
            prefs.edit().putStringSet(KEY_EXPORTED, exported + sessions.map { it.id }).apply()
        }

        val startOfDay = LocalDate.now().atStartOfDay(zone).toInstant()
        val steps = client.aggregate(
            AggregateRequest(setOf(StepsRecord.COUNT_TOTAL), TimeRangeFilter.between(startOfDay, now))
        )[StepsRecord.COUNT_TOTAL] ?: 0L

        prefs.edit().putLong(KEY_LAST_SYNC, now.toEpochMilli()).apply()
        return HealthSyncResult(newest.size, sessions.size, steps)
    }

    /** Last successful sync, epoch millis, or 0. */
    fun lastSync(): Long = prefs.getLong(KEY_LAST_SYNC, 0)

    private fun WorkoutLog.toSession(zone: ZoneId): ExerciseSessionRecord? {
        val day = runCatching { LocalDate.parse(date.take(10)) }.getOrNull() ?: return null
        val minutes = (duration ?: 45).coerceIn(5, 240).toLong()
        val start = day.atTime(18, 0).atZone(zone)
        val end = start.plusMinutes(minutes)
        return ExerciseSessionRecord(
            startTime = start.toInstant(),
            startZoneOffset = start.offset ?: ZoneOffset.UTC,
            endTime = end.toInstant(),
            endZoneOffset = end.offset ?: ZoneOffset.UTC,
            exerciseType = ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING,
            title = dayLabel.ifBlank { "Nexal workout" },
            metadata = Metadata.manualEntry()
        )
    }

    private companion object {
        const val KEY_EXPORTED = "exported_workouts"
        const val KEY_LAST_SYNC = "last_sync"
    }
}
