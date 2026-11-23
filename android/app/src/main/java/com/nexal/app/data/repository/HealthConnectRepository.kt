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
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton

/** What a Health Connect sync did, for the confirmation message. */
data class HealthSyncResult(
    val weightsImported: Int,
    val weightsExported: Int,
    val workoutsExported: Int,
    /** Null when Nexal isn't allowed to read steps. */
    val stepsToday: Long?
)

enum class HealthStatus { AVAILABLE, NEEDS_INSTALL, UNSUPPORTED }

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

    private val readSteps = HealthPermission.getReadPermission(StepsRecord::class)
    private val readWeight = HealthPermission.getReadPermission(WeightRecord::class)
    private val writeWeight = HealthPermission.getWritePermission(WeightRecord::class)
    private val writeExercise = HealthPermission.getWritePermission(ExerciseSessionRecord::class)

    private val prefs = context.getSharedPreferences("health_connect", Context.MODE_PRIVATE)

    /** Where this phone stands with Health Connect. */
    fun status(): HealthStatus = when (HealthConnectClient.getSdkStatus(context, PROVIDER)) {
        HealthConnectClient.SDK_AVAILABLE -> HealthStatus.AVAILABLE
        HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> HealthStatus.NEEDS_INSTALL
        else -> HealthStatus.UNSUPPORTED
    }

    /** Health Connect is installed (or built in) and usable on this phone. */
    fun isAvailable(): Boolean = status() == HealthStatus.AVAILABLE

    private fun client(): HealthConnectClient? =
        if (isAvailable()) HealthConnectClient.getOrCreate(context) else null

    /** The Health Connect permissions the user has given Nexal. */
    suspend fun grantedPermissions(): Set<String> {
        val client = client() ?: return emptySet()
        return runCatching { client.permissionController.getGrantedPermissions() }
            .getOrDefault(emptySet())
            .intersect(permissions)
    }

    suspend fun hasAllPermissions(): Boolean = grantedPermissions().containsAll(permissions)

    /** Connected means at least one permission, so there's something to sync. */
    suspend fun isConnected(): Boolean = grantedPermissions().isNotEmpty()

    /**
     * Two-way sync of whatever the user allowed. Safe to repeat: workouts are
     * exported once, weights are matched by date.
     */
    suspend fun sync(): HealthSyncResult {
        val client = client() ?: throw IllegalStateException("Health Connect isn't available on this phone.")
        val granted = grantedPermissions()
        if (granted.isEmpty()) throw IllegalStateException("Allow Nexal in Health Connect first.")

        val zone = ZoneId.systemDefault()
        val now = Instant.now()
        val since = now.minus(30, ChronoUnit.DAYS)
        val sinceDate = since.atZone(zone).toLocalDate().toString()

        // Weight in: the latest reading per day from the last 30 days that Nexal doesn't have yet.
        val weights = if (readWeight in granted) {
            client.readRecords(ReadRecordsRequest(WeightRecord::class, TimeRangeFilter.between(since, now))).records
        } else emptyList()
        val existing = nutritionRepo.observeWeightEntries().first().map { it.date.take(10) }.toSet()
        val newest = weights
            .groupBy { it.time.atZone(zone).toLocalDate().toString() }
            .mapValues { (_, day) -> day.maxBy { it.time } }
            .filterKeys { it !in existing }
        newest.forEach { (date, record) ->
            nutritionRepo.logWeight(WeightEntry(date = date, weight = record.weight.inKilograms))
        }

        // Weight out: Nexal weigh-ins Health Connect doesn't have. Without read
        // access we can't see what's there, so each day is only ever sent once.
        var weightsExported = 0
        if (writeWeight in granted) {
            val healthDates = weights.map { it.time.atZone(zone).toLocalDate().toString() }.toSet()
            val sentBefore = prefs.getStringSet(KEY_EXPORTED_WEIGHTS, emptySet()).orEmpty()
            val toExport = nutritionRepo.observeWeightEntries().first().filter {
                val day = it.date.take(10)
                day >= sinceDate && day !in healthDates && day !in newest.keys && day !in sentBefore
            }
            val records = toExport.mapNotNull { entry ->
                val day = runCatching { LocalDate.parse(entry.date.take(10)) }.getOrNull() ?: return@mapNotNull null
                if (entry.weight <= 0.0) return@mapNotNull null
                // Morning weigh-in, but not in the future (today before 8 am).
                val at = minOf(day.atTime(8, 0).atZone(zone), ZonedDateTime.now(zone).minusMinutes(1))
                WeightRecord(
                    time = at.toInstant(),
                    zoneOffset = at.offset,
                    weight = Mass.kilograms(entry.weight.coerceIn(1.0, 999.0)),
                    metadata = Metadata.manualEntry()
                )
            }
            if (records.isNotEmpty()) {
                client.insertRecords(records)
                weightsExported = records.size
                prefs.edit().putStringSet(KEY_EXPORTED_WEIGHTS, sentBefore + toExport.map { it.date.take(10) }).apply()
            }
        }

        // Workouts out: each logged session once.
        var workoutsExported = 0
        if (writeExercise in granted) {
            val exported = prefs.getStringSet(KEY_EXPORTED, emptySet()).orEmpty()
            val sessions = workoutRepo.observeLogs().first().filter { it.id.isNotBlank() && it.id !in exported }
            val records = sessions.mapNotNull { it.toSession(zone) }
            if (records.isNotEmpty()) client.insertRecords(records)
            if (sessions.isNotEmpty()) {
                prefs.edit().putStringSet(KEY_EXPORTED, exported + sessions.map { it.id }).apply()
            }
            workoutsExported = records.size
        }

        val steps = if (readSteps in granted) {
            val startOfDay = LocalDate.now().atStartOfDay(zone).toInstant()
            client.aggregate(
                AggregateRequest(setOf(StepsRecord.COUNT_TOTAL), TimeRangeFilter.between(startOfDay, now))
            )[StepsRecord.COUNT_TOTAL] ?: 0L
        } else null

        prefs.edit().putLong(KEY_LAST_SYNC, now.toEpochMilli()).apply()
        return HealthSyncResult(newest.size, weightsExported, workoutsExported, steps)
    }

    /** Last successful sync, epoch millis, or 0. */
    fun lastSync(): Long = prefs.getLong(KEY_LAST_SYNC, 0)

    private fun WorkoutLog.toSession(zone: ZoneId): ExerciseSessionRecord? {
        val day = runCatching { LocalDate.parse(date.take(10)) }.getOrNull() ?: return null
        val minutes = (duration ?: 45).coerceIn(5, 240).toLong()
        // Logs only carry a date. Place the session in the evening, but never in
        // the future: Health Connect rejects future records and the whole insert fails.
        val latestEnd = ZonedDateTime.now(zone).minusMinutes(1)
        val end = minOf(day.atTime(18, 0).atZone(zone).plusMinutes(minutes), latestEnd)
        val start = end.minusMinutes(minutes)
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

    companion object {
        /** The Health Connect app on Android 13 and below; built in from Android 14. */
        const val PROVIDER = "com.google.android.apps.healthdata"
        private const val KEY_EXPORTED = "exported_workouts"
        private const val KEY_EXPORTED_WEIGHTS = "exported_weights"
        private const val KEY_LAST_SYNC = "last_sync"
    }
}
