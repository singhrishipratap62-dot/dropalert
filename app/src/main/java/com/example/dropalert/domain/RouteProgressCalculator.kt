package com.example.dropalert.domain

import com.example.dropalert.model.ConnectionEtaMode
import com.example.dropalert.model.LatLngPoint
import kotlin.math.roundToInt

data class ProgressEvaluationInput(
    val currentLat: Double,
    val currentLon: Double,
    val currentTimestampMs: Long,
    val gpsReportedSpeedMps: Float?,
    val gpsAccuracyMeters: Float?,
    val previousLat: Double?,
    val previousLon: Double?,
    val previousTimestampMs: Long?,
    val previousSmoothedSpeedMps: Double,
    val cachedRoutePoints: List<LatLngPoint>,
    val totalRouteDistanceMeters: Double,
    val initialRouteDurationSeconds: Double,
    val lastOnlineEtaSeconds: Double,
    val lastOnlineEtaTimestampMs: Long,
    val isOnlineRouteFresh: Boolean,
    val consecutiveUnderThresholdCount: Int,
    val isAlarmAlreadyTriggered: Boolean,
    val isAlarmDismissed: Boolean
)

data class ProgressEvaluationOutput(
    val remainingDistanceMeters: Double,
    val estimatedSecondsRemaining: Double,
    val estimatedMinutesRemaining: Int,
    val smoothedSpeedMps: Double,
    val isOffRoute: Boolean,
    val connectionMode: ConnectionEtaMode,
    val consecutiveUnderThresholdCount: Int,
    val shouldTriggerAlarmNow: Boolean
)

object RouteProgressCalculator {

    const val ALARM_THRESHOLD_SECONDS = 600.0 // 10 minutes
    private const val MIN_MOVING_SPEED_MPS = 2.0 // ~7.2 km/h minimum valid transit speed
    private const val MAX_REALISTIC_SPEED_MPS = 38.0 // ~137 km/h cap against GPS spikes
    private const val EMA_ALPHA = 0.35
    private const val REQUIRED_CONSECUTIVE_FIXES_FOR_ALARM = 3

    /**
     * Evaluates the user's current progress along the cached route, updates the smoothed speed,
     * selects the appropriate ETA estimation strategy (Online -> Offline Route + Speed ->
     * Offline Last-Known ETA Fallback), and applies hysteresis before firing the 10-minute alarm.
     */
    fun evaluate(input: ProgressEvaluationInput): ProgressEvaluationOutput {
        // 1. Snap current GPS location to cached route polyline
        val snap = GeoMath.snapToRoute(
            userLat = input.currentLat,
            userLon = input.currentLon,
            routePoints = input.cachedRoutePoints,
            totalRouteDistanceMeters = input.totalRouteDistanceMeters
        )

        // 2. Compute observed instantaneous speed with GPS noise filtering
        val instantaneousSpeedMps = computeInstantaneousSpeedMps(
            currentLat = input.currentLat,
            currentLon = input.currentLon,
            currentTimestampMs = input.currentTimestampMs,
            gpsReportedSpeedMps = input.gpsReportedSpeedMps,
            gpsAccuracyMeters = input.gpsAccuracyMeters,
            previousLat = input.previousLat,
            previousLon = input.previousLon,
            previousTimestampMs = input.previousTimestampMs
        )

        val updatedSmoothedSpeedMps = when {
            instantaneousSpeedMps >= MIN_MOVING_SPEED_MPS -> {
                if (input.previousSmoothedSpeedMps >= MIN_MOVING_SPEED_MPS) {
                    (EMA_ALPHA * instantaneousSpeedMps) +
                        ((1.0 - EMA_ALPHA) * input.previousSmoothedSpeedMps)
                } else {
                    instantaneousSpeedMps
                }
            }
            // Decay gently during brief traffic lights/bus stops so a 60s stop doesn't spike ETA to infinity
            else -> (input.previousSmoothedSpeedMps * 0.92)
        }.coerceIn(0.0, MAX_REALISTIC_SPEED_MPS)

        // 3. Determine remaining distance (use straight-line distance to destination if off-route)
        val destPoint = input.cachedRoutePoints.lastOrNull()
        val effectiveRemainingDistanceMeters = if (snap.isOffRoute && destPoint != null) {
            GeoMath.haversineMeters(
                input.currentLat,
                input.currentLon,
                destPoint.latitude,
                destPoint.longitude
            )
        } else {
            snap.remainingRouteDistanceMeters
        }

        // 4. Select best ETA estimate and ConnectionEtaMode
        val elapsedSinceLastOnlineSeconds =
            ((input.currentTimestampMs - input.lastOnlineEtaTimestampMs).coerceAtLeast(0L)) / 1000.0
        val countdownFallbackEtaSeconds =
            (input.lastOnlineEtaSeconds - elapsedSinceLastOnlineSeconds).coerceAtLeast(60.0)

        val routeBaselineAverageSpeedMps = if (input.initialRouteDurationSeconds > 0) {
            (input.totalRouteDistanceMeters / input.initialRouteDurationSeconds)
                .coerceIn(4.0, 25.0)
        } else {
            10.0
        }

        val (estimatedSecondsRemaining, mode) = when {
            input.isOnlineRouteFresh -> {
                // Live online route was just refreshed or is within the online window;
                // adjust proportionally by remaining route fraction
                val progressEta = if (input.totalRouteDistanceMeters > 10.0) {
                    val ratio = (effectiveRemainingDistanceMeters / input.totalRouteDistanceMeters)
                        .coerceIn(0.0, 1.0)
                    input.initialRouteDurationSeconds * ratio
                } else {
                    input.lastOnlineEtaSeconds
                }
                val blendedOnlineSeconds = if (elapsedSinceLastOnlineSeconds < 45.0) {
                    input.lastOnlineEtaSeconds
                } else {
                    progressEta
                }
                blendedOnlineSeconds to ConnectionEtaMode.ONLINE
            }

            !snap.isOffRoute && updatedSmoothedSpeedMps >= MIN_MOVING_SPEED_MPS -> {
                // Offline Estimate: on cached route with reliable moving speed.
                // Blend 70% observed EMA speed + 30% baseline route average speed for stability.
                val blendedSpeed =
                    (0.70 * updatedSmoothedSpeedMps) + (0.30 * routeBaselineAverageSpeedMps)
                val speedBasedSeconds = effectiveRemainingDistanceMeters / blendedSpeed
                speedBasedSeconds to ConnectionEtaMode.OFFLINE_ESTIMATE
            }

            else -> {
                // Vehicle is stopped at a traffic light/station for several minutes, GPS speed is noisy,
                // or vehicle deviated off the cached route while offline -> Fall back to last known ETA
                // combined with baseline distance estimate so we never divide by zero.
                val distanceFallbackSeconds =
                    effectiveRemainingDistanceMeters / routeBaselineAverageSpeedMps
                val conservativeFallbackSeconds =
                    maxOf(countdownFallbackEtaSeconds, distanceFallbackSeconds * 0.85)
                conservativeFallbackSeconds to ConnectionEtaMode.OFFLINE_FALLBACK_ETA
            }
        }

        val estimatedMinutes = (estimatedSecondsRemaining / 60.0).roundToInt().coerceAtLeast(0)

        // 5. Ten-Minute Warning Hysteresis & Short-Journey Handling
        val isUnderThreshold = estimatedSecondsRemaining <= ALARM_THRESHOLD_SECONDS
        val isInitialShortJourney =
            input.initialRouteDurationSeconds <= ALARM_THRESHOLD_SECONDS &&
                input.consecutiveUnderThresholdCount == 0

        val newConsecutiveCount = if (isUnderThreshold) {
            input.consecutiveUnderThresholdCount + 1
        } else {
            0
        }

        val shouldTrigger = !input.isAlarmAlreadyTriggered &&
            !input.isAlarmDismissed &&
            (isInitialShortJourney || newConsecutiveCount >= REQUIRED_CONSECUTIVE_FIXES_FOR_ALARM)

        return ProgressEvaluationOutput(
            remainingDistanceMeters = effectiveRemainingDistanceMeters,
            estimatedSecondsRemaining = estimatedSecondsRemaining,
            estimatedMinutesRemaining = estimatedMinutes,
            smoothedSpeedMps = updatedSmoothedSpeedMps,
            isOffRoute = snap.isOffRoute,
            connectionMode = mode,
            consecutiveUnderThresholdCount = newConsecutiveCount,
            shouldTriggerAlarmNow = shouldTrigger
        )
    }

    private fun computeInstantaneousSpeedMps(
        currentLat: Double,
        currentLon: Double,
        currentTimestampMs: Long,
        gpsReportedSpeedMps: Float?,
        gpsAccuracyMeters: Float?,
        previousLat: Double?,
        previousLon: Double?,
        previousTimestampMs: Long?
    ): Double {
        // Ignore noisy GPS fixes with poor horizontal accuracy (> 65 meters)
        if (gpsAccuracyMeters != null && gpsAccuracyMeters > 65f) {
            return 0.0
        }

        if (gpsReportedSpeedMps != null && gpsReportedSpeedMps > 0.5f) {
            return gpsReportedSpeedMps.toDouble().coerceIn(0.0, MAX_REALISTIC_SPEED_MPS)
        }

        if (previousLat != null && previousLon != null && previousTimestampMs != null) {
            val dtSeconds = (currentTimestampMs - previousTimestampMs) / 1000.0
            if (dtSeconds >= 2.0) {
                val distMeters = GeoMath.haversineMeters(
                    previousLat,
                    previousLon,
                    currentLat,
                    currentLon
                )
                // Ignore stationary GPS drift smaller than accuracy radius or 8 meters
                val minMovementFloor = (gpsAccuracyMeters?.toDouble() ?: 8.0).coerceAtLeast(8.0)
                if (distMeters >= minMovementFloor) {
                    return (distMeters / dtSeconds).coerceIn(0.0, MAX_REALISTIC_SPEED_MPS)
                }
            }
        }
        return 0.0
    }
}
