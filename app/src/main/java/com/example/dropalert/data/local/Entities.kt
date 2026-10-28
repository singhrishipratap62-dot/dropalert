package com.example.dropalert.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "active_journey")
data class ActiveJourneyEntity(
    @PrimaryKey val id: Int = 1, // Singleton row for the currently active journey
    val destinationName: String,
    val destinationLat: Double,
    val destinationLon: Double,
    val status: String,
    val totalRouteDistanceMeters: Double,
    val initialDurationSeconds: Double,
    val latestEtaSeconds: Double,
    val lastOnlineEtaSeconds: Double,
    val lastOnlineEtaTimestampMs: Long,
    val remainingDistanceMeters: Double,
    val smoothedSpeedMps: Double,
    val lastKnownLat: Double?,
    val lastKnownLon: Double?,
    val lastLocationTimestampMs: Long?,
    val connectionMode: String,
    val isOffRoute: Boolean,
    val isAlarmTriggered: Boolean,
    val isAlarmDismissed: Boolean,
    val consecutiveUnderThresholdCount: Int
)

@Entity(tableName = "cached_route_points", primaryKeys = ["journeyId", "sequenceIndex"])
data class CachedRoutePointEntity(
    val journeyId: Int = 1,
    val sequenceIndex: Int,
    val latitude: Double,
    val longitude: Double,
    val cumulativeDistanceFromStartMeters: Double
)
