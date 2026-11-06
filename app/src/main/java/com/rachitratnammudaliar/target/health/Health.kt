package com.rachitratnammudaliar.target.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.rachitratnammudaliar.target.domain.Amounts
import com.rachitratnammudaliar.target.domain.Goals
import com.rachitratnammudaliar.target.domain.HealthSource
import java.time.LocalDate
import java.time.ZoneId

/**
 * Reads totals from Health Connect, Android's on-device health store. Samsung Health shares steps,
 * workouts, distance and calories with it, so targets like "Run 10 km" can tick themselves.
 * Everything stays on the phone: Health Connect is part of Android and needs no internet.
 */
class Health(private val context: Context) {

    enum class Status { NOT_SUPPORTED, NEEDS_UPDATE, AVAILABLE }

    fun status(): Status = when (HealthConnectClient.getSdkStatus(context)) {
        HealthConnectClient.SDK_AVAILABLE -> Status.AVAILABLE
        HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> Status.NEEDS_UPDATE
        else -> Status.NOT_SUPPORTED
    }

    private val client: HealthConnectClient? by lazy {
        if (status() == Status.AVAILABLE) runCatching { HealthConnectClient.getOrCreate(context) }.getOrNull() else null
    }

    suspend fun granted(): Set<String> =
        runCatching { client?.permissionController?.getGrantedPermissions() }.getOrNull().orEmpty()

    suspend fun hasAll(): Boolean = granted().containsAll(PERMISSIONS)

    /** The day's total for [source], or null if it can't be read (no permission, no Health Connect). */
    suspend fun total(source: HealthSource, day: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Double? {
        val c = client ?: return null
        val range = TimeRangeFilter.between(day.atStartOfDay(zone).toInstant(), day.plusDays(1).atStartOfDay(zone).toInstant())
        return runCatching {
            when (source) {
                HealthSource.NONE -> null
                HealthSource.STEPS ->
                    c.aggregate(AggregateRequest(setOf(StepsRecord.COUNT_TOTAL), range))[StepsRecord.COUNT_TOTAL]?.toDouble() ?: 0.0
                HealthSource.DISTANCE ->
                    c.aggregate(AggregateRequest(setOf(DistanceRecord.DISTANCE_TOTAL), range))[DistanceRecord.DISTANCE_TOTAL]?.inKilometers ?: 0.0
                HealthSource.MOVEMENT -> {
                    // Walking, running and cycling distance, or the steps, whichever you're further along on.
                    val km = c.aggregate(AggregateRequest(setOf(DistanceRecord.DISTANCE_TOTAL), range))[DistanceRecord.DISTANCE_TOTAL]?.inKilometers ?: 0.0
                    val steps = c.aggregate(AggregateRequest(setOf(StepsRecord.COUNT_TOTAL), range))[StepsRecord.COUNT_TOTAL]?.toDouble() ?: 0.0
                    Goals.movementKm(km, steps)
                }
                HealthSource.RUN_DISTANCE -> {
                    // Distance recorded during running workouts only, so walking doesn't count.
                    val runs = c.readRecords(ReadRecordsRequest(ExerciseSessionRecord::class, range)).records
                        .filter { it.exerciseType in RUNNING }
                    runs.sumOf { run ->
                        c.aggregate(AggregateRequest(setOf(DistanceRecord.DISTANCE_TOTAL), TimeRangeFilter.between(run.startTime, run.endTime)))[DistanceRecord.DISTANCE_TOTAL]?.inKilometers ?: 0.0
                    }
                }
                HealthSource.EXERCISE_MINUTES ->
                    (c.aggregate(AggregateRequest(setOf(ExerciseSessionRecord.EXERCISE_DURATION_TOTAL), range))[ExerciseSessionRecord.EXERCISE_DURATION_TOTAL]?.toMinutes() ?: 0L).toDouble()
                HealthSource.ACTIVE_CALORIES ->
                    c.aggregate(AggregateRequest(setOf(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL), range))[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]?.inKilocalories ?: 0.0
            }?.let { Amounts.round(it) }
        }.getOrNull()
    }

    companion object {
        val PERMISSIONS = setOf(
            HealthPermission.getReadPermission(StepsRecord::class),
            HealthPermission.getReadPermission(DistanceRecord::class),
            HealthPermission.getReadPermission(ExerciseSessionRecord::class),
            HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class),
        )

        private val RUNNING = setOf(ExerciseSessionRecord.EXERCISE_TYPE_RUNNING, ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL)

        /** Samsung Health and the Health Connect app, for the "open" buttons. */
        const val SAMSUNG_HEALTH = "com.sec.android.app.shealth"
        const val HEALTH_CONNECT = "com.google.android.apps.healthdata"
    }
}
