package com.example.dropalert.data.repository

import com.example.dropalert.data.local.ActiveJourneyEntity
import com.example.dropalert.data.local.CachedRoutePointEntity
import com.example.dropalert.data.local.JourneyDao
import com.example.dropalert.data.remote.CalculatedRoute
import com.example.dropalert.data.remote.OsmRoutingService
import com.example.dropalert.domain.GeoMath
import com.example.dropalert.domain.RouteProgressCalculator
import com.example.dropalert.model.ConnectionEtaMode
import com.example.dropalert.model.DestinationPoint
import com.example.dropalert.model.JourneyStatus
import com.example.dropalert.model.LatLngPoint
import kotlinx.coroutines.flow.Flow

class JourneyRepository(
    private val dao: JourneyDao,
    private val routingService: OsmRoutingService
) {

    val activeJourneyFlow: Flow<ActiveJourneyEntity?> = dao.observeActiveJourney()

    suspend fun searchDestinations(query: String): Result<List<DestinationPoint>> {
        return routingService.searchDestinations(query)
    }

    suspend fun getActiveJourneyOnce(): ActiveJourneyEntity? {
        return dao.getActiveJourneyOnce()
    }

    suspend fun getCachedRoutePoints(): List<LatLngPoint> {
        return dao.getCachedRoutePoints().map {
            LatLngPoint(
                latitude = it.latitude,
                longitude = it.longitude,
                cumulativeDistanceFromStartMeters = it.cumulativeDistanceFromStartMeters
            )
        }
    }

    /**
     * Calculates an online route (or creates a straight-line fallback route if internet fails
     * right as the user starts) and caches the entire route geometry in Room.
     */
    suspend fun initializeAndCacheJourney(
        startLat: Double,
        startLon: Double,
        destination: DestinationPoint,
        nowMs: Long = System.currentTimeMillis()
    ): ActiveJourneyEntity {
        val routeResult = routingService.fetchRoute(
            startLat = startLat,
            startLon = startLon,
            destLat = destination.latitude,
            destLon = destination.longitude
        )

        val (calculatedRoute, initialMode) = routeResult.fold(
            onSuccess = { it to ConnectionEtaMode.ONLINE },
            onFailure = {
                // Offline fallback if internet drops immediately when starting journey
                val straightLineMeters = GeoMath.haversineMeters(
                    startLat,
                    startLon,
                    destination.latitude,
                    destination.longitude
                )
                val estimatedRoadMeters = straightLineMeters * 1.25
                val assumedTransitSpeedMps = 9.0 // ~32 km/h urban bus/metro average
                val fallbackDurationSeconds = (estimatedRoadMeters / assumedTransitSpeedMps)
                    .coerceAtLeast(60.0)
                val points = GeoMath.buildCumulativeRoutePoints(
                    listOf(
                        startLat to startLon,
                        destination.latitude to destination.longitude
                    )
                )
                CalculatedRoute(
                    points = points,
                    totalDistanceMeters = estimatedRoadMeters,
                    durationSeconds = fallbackDurationSeconds
                ) to ConnectionEtaMode.OFFLINE_ESTIMATE
            }
        )

        val triggerImmediately =
            calculatedRoute.durationSeconds <= RouteProgressCalculator.ALARM_THRESHOLD_SECONDS

        val entity = ActiveJourneyEntity(
            id = 1,
            destinationName = destination.name,
            destinationLat = destination.latitude,
            destinationLon = destination.longitude,
            status = if (triggerImmediately) {
                JourneyStatus.ALARM_RINGING.name
            } else {
                JourneyStatus.TRACKING.name
            },
            totalRouteDistanceMeters = calculatedRoute.totalDistanceMeters,
            initialDurationSeconds = calculatedRoute.durationSeconds,
            latestEtaSeconds = calculatedRoute.durationSeconds,
            lastOnlineEtaSeconds = calculatedRoute.durationSeconds,
            lastOnlineEtaTimestampMs = nowMs,
            remainingDistanceMeters = calculatedRoute.totalDistanceMeters,
            smoothedSpeedMps = 0.0,
            lastKnownLat = startLat,
            lastKnownLon = startLon,
            lastLocationTimestampMs = nowMs,
            connectionMode = initialMode.name,
            isOffRoute = false,
            isAlarmTriggered = triggerImmediately,
            isAlarmDismissed = false,
            consecutiveUnderThresholdCount = if (triggerImmediately) 3 else 0
        )

        val pointEntities = calculatedRoute.points.mapIndexed { index, pt ->
            CachedRoutePointEntity(
                journeyId = 1,
                sequenceIndex = index,
                latitude = pt.latitude,
                longitude = pt.longitude,
                cumulativeDistanceFromStartMeters = pt.cumulativeDistanceFromStartMeters
            )
        }

        dao.saveNewJourneyWithRoute(entity, pointEntities)
        return entity
    }

    /**
     * Attempts a periodic online route recalculation when internet is available.
     * Replaces the cached route geometry and updates lastOnlineEtaSeconds on success.
     */
    suspend fun tryRefreshOnlineRoute(
        currentLat: Double,
        currentLon: Double,
        currentEntity: ActiveJourneyEntity,
        nowMs: Long = System.currentTimeMillis()
    ): Pair<ActiveJourneyEntity, List<LatLngPoint>>? {
        val result = routingService.fetchRoute(
            startLat = currentLat,
            startLon = currentLon,
            destLat = currentEntity.destinationLat,
            destLon = currentEntity.destinationLon
        )
        val refreshed = result.getOrNull() ?: return null

        val updatedEntity = currentEntity.copy(
            totalRouteDistanceMeters = refreshed.totalDistanceMeters,
            initialDurationSeconds = refreshed.durationSeconds,
            latestEtaSeconds = refreshed.durationSeconds,
            lastOnlineEtaSeconds = refreshed.durationSeconds,
            lastOnlineEtaTimestampMs = nowMs,
            remainingDistanceMeters = refreshed.totalDistanceMeters,
            connectionMode = ConnectionEtaMode.ONLINE.name,
            isOffRoute = false
        )

        val pointEntities = refreshed.points.mapIndexed { index, pt ->
            CachedRoutePointEntity(
                journeyId = 1,
                sequenceIndex = index,
                latitude = pt.latitude,
                longitude = pt.longitude,
                cumulativeDistanceFromStartMeters = pt.cumulativeDistanceFromStartMeters
            )
        }

        dao.saveNewJourneyWithRoute(updatedEntity, pointEntities)
        return updatedEntity to refreshed.points
    }

    suspend fun updateActiveJourney(entity: ActiveJourneyEntity) {
        dao.upsertActiveJourney(entity)
    }

    suspend fun markAlarmDismissed() {
        val current = dao.getActiveJourneyOnce() ?: return
        dao.upsertActiveJourney(
            current.copy(
                status = JourneyStatus.TRACKING.name,
                isAlarmTriggered = true,
                isAlarmDismissed = true
            )
        )
    }

    suspend fun cancelAndClearJourney() {
        dao.deleteEntireJourney()
    }
}
