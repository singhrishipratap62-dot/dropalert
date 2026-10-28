package com.example.dropalert.domain

import com.example.dropalert.model.ConnectionEtaMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteProgressCalculatorTest {

    // Straight northbound route ~10 km from (28.4000, 77.0000) to (28.4900, 77.0000)
    private val sampleRoute = GeoMath.buildCumulativeRoutePoints(
        listOf(
            28.4000 to 77.0000,
            28.4300 to 77.0000,
            28.4600 to 77.0000,
            28.4900 to 77.0000
        )
    )
    private val totalRouteMeters = sampleRoute.last().cumulativeDistanceFromStartMeters // ~10,007m

    @Test
    fun `offline moving vehicle uses cached route and EMA speed`() {
        val out = RouteProgressCalculator.evaluate(
            ProgressEvaluationInput(
                currentLat = 28.4300, // 1/3 along the route (~6.67 km remaining)
                currentLon = 77.0000,
                currentTimestampMs = 120_000L,
                gpsReportedSpeedMps = 10.0f, // 36 km/h -> ~667s (~11 min) remaining
                gpsAccuracyMeters = 10f,
                previousLat = 28.4250,
                previousLon = 77.0000,
                previousTimestampMs = 60_000L,
                previousSmoothedSpeedMps = 10.0,
                cachedRoutePoints = sampleRoute,
                totalRouteDistanceMeters = totalRouteMeters,
                initialRouteDurationSeconds = 1000.0,
                lastOnlineEtaSeconds = 1000.0,
                lastOnlineEtaTimestampMs = 0L,
                isOnlineRouteFresh = false,
                consecutiveUnderThresholdCount = 0,
                isAlarmAlreadyTriggered = false,
                isAlarmDismissed = false
            )
        )

        assertEquals(ConnectionEtaMode.OFFLINE_ESTIMATE, out.connectionMode)
        assertFalse(out.isOffRoute)
        assertEquals(11, out.estimatedMinutesRemaining)
        assertFalse(out.shouldTriggerAlarmNow)
    }

    @Test
    fun `vehicle stopped for several minutes offline falls back to last known ETA`() {
        val out = RouteProgressCalculator.evaluate(
            ProgressEvaluationInput(
                currentLat = 28.4300,
                currentLon = 77.0000,
                currentTimestampMs = 300_000L,
                gpsReportedSpeedMps = 0.0f,
                gpsAccuracyMeters = 8f,
                previousLat = 28.4300,
                previousLon = 77.0000,
                previousTimestampMs = 240_000L,
                previousSmoothedSpeedMps = 0.5, // Decayed below moving threshold
                cachedRoutePoints = sampleRoute,
                totalRouteDistanceMeters = totalRouteMeters,
                initialRouteDurationSeconds = 1000.0,
                lastOnlineEtaSeconds = 900.0,
                lastOnlineEtaTimestampMs = 60_000L,
                isOnlineRouteFresh = false,
                consecutiveUnderThresholdCount = 0,
                isAlarmAlreadyTriggered = false,
                isAlarmDismissed = false
            )
        )

        assertEquals(ConnectionEtaMode.OFFLINE_FALLBACK_ETA, out.connectionMode)
        assertTrue(out.estimatedMinutesRemaining >= 9)
    }

    @Test
    fun `ten minute alarm requires 3 consecutive fixes for normal journeys`() {
        // Fix #3 under 600s -> triggers alarm
        val out = RouteProgressCalculator.evaluate(
            ProgressEvaluationInput(
                currentLat = 28.4450, // Halfway (~5 km remaining)
                currentLon = 77.0000,
                currentTimestampMs = 200_000L,
                gpsReportedSpeedMps = 10.0f, // ~500s (8.3 min) remaining
                gpsAccuracyMeters = 8f,
                previousLat = 28.4400,
                previousLon = 77.0000,
                previousTimestampMs = 195_000L,
                previousSmoothedSpeedMps = 10.0,
                cachedRoutePoints = sampleRoute,
                totalRouteDistanceMeters = totalRouteMeters,
                initialRouteDurationSeconds = 1000.0,
                lastOnlineEtaSeconds = 1000.0,
                lastOnlineEtaTimestampMs = 0L,
                isOnlineRouteFresh = false,
                consecutiveUnderThresholdCount = 2, // Already had 2 fixes <= 10 min
                isAlarmAlreadyTriggered = false,
                isAlarmDismissed = false
            )
        )

        assertEquals(3, out.consecutiveUnderThresholdCount)
        assertTrue(out.shouldTriggerAlarmNow)
    }

    @Test
    fun `destination already less than 10 minutes away triggers immediately`() {
        val out = RouteProgressCalculator.evaluate(
            ProgressEvaluationInput(
                currentLat = 28.4600,
                currentLon = 77.0000,
                currentTimestampMs = 10_000L,
                gpsReportedSpeedMps = 10.0f,
                gpsAccuracyMeters = 8f,
                previousLat = null,
                previousLon = null,
                previousTimestampMs = null,
                previousSmoothedSpeedMps = 10.0,
                cachedRoutePoints = sampleRoute,
                totalRouteDistanceMeters = 3300.0,
                initialRouteDurationSeconds = 420.0, // 7 minutes initial journey
                lastOnlineEtaSeconds = 420.0,
                lastOnlineEtaTimestampMs = 10_000L,
                isOnlineRouteFresh = true,
                consecutiveUnderThresholdCount = 0,
                isAlarmAlreadyTriggered = false,
                isAlarmDismissed = false
            )
        )

        assertTrue(out.shouldTriggerAlarmNow)
    }
}
