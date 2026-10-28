package com.example.dropalert.domain

import com.example.dropalert.model.LatLngPoint
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

data class RouteSnapResult(
    val snappedDistanceAlongRouteMeters: Double,
    val remainingRouteDistanceMeters: Double,
    val distanceFromRouteMeters: Double,
    val nearestSegmentIndex: Int,
    val isOffRoute: Boolean
)

object GeoMath {

    private const val EARTH_RADIUS_METERS = 6_371_000.0
    const val OFF_ROUTE_THRESHOLD_METERS = 300.0

    /**
     * Haversine great-circle distance between two WGS84 coordinates in meters.
     */
    fun haversineMeters(
        lat1: Double,
        lon1: Double,
        lat2: Double,
        lon2: Double
    ): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val rLat1 = Math.toRadians(lat1)
        val rLat2 = Math.toRadians(lat2)

        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(rLat1) * cos(rLat2) * sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1.0 - a))
        return EARTH_RADIUS_METERS * c
    }

    /**
     * Converts raw (lat, lon) vertices into LatLngPoints annotated with cumulative distance
     * from the start of the route.
     */
    fun buildCumulativeRoutePoints(rawVertices: List<Pair<Double, Double>>): List<LatLngPoint> {
        if (rawVertices.isEmpty()) return emptyList()
        val result = ArrayList<LatLngPoint>(rawVertices.size)
        var cumulativeMeters = 0.0

        for (i in rawVertices.indices) {
            val (lat, lon) = rawVertices[i]
            if (i > 0) {
                val (prevLat, prevLon) = rawVertices[i - 1]
                cumulativeMeters += haversineMeters(prevLat, prevLon, lat, lon)
            }
            result.add(
                LatLngPoint(
                    latitude = lat,
                    longitude = lon,
                    cumulativeDistanceFromStartMeters = cumulativeMeters
                )
            )
        }
        return result
    }

    /**
     * Snaps a user's current GPS position (userLat, userLon) onto the closest segment
     * of the cached polyline using local equirectangular tangent-plane projection.
     */
    fun snapToRoute(
        userLat: Double,
        userLon: Double,
        routePoints: List<LatLngPoint>,
        totalRouteDistanceMeters: Double
    ): RouteSnapResult {
        if (routePoints.isEmpty()) {
            return RouteSnapResult(
                snappedDistanceAlongRouteMeters = 0.0,
                remainingRouteDistanceMeters = totalRouteDistanceMeters,
                distanceFromRouteMeters = 0.0,
                nearestSegmentIndex = 0,
                isOffRoute = true
            )
        }

        if (routePoints.size == 1) {
            val single = routePoints.first()
            val dist = haversineMeters(userLat, userLon, single.latitude, single.longitude)
            return RouteSnapResult(
                snappedDistanceAlongRouteMeters = 0.0,
                remainingRouteDistanceMeters = dist,
                distanceFromRouteMeters = dist,
                nearestSegmentIndex = 0,
                isOffRoute = dist > OFF_ROUTE_THRESHOLD_METERS
            )
        }

        var bestPerpendicularDistMeters = Double.MAX_VALUE
        var bestSnappedCumulativeMeters = 0.0
        var bestSegmentIndex = 0

        val cosLat = cos(Math.toRadians(userLat)).coerceAtLeast(0.01)

        for (i in 0 until routePoints.size - 1) {
            val pA = routePoints[i]
            val pB = routePoints[i + 1]

            // Convert (pA, pB, user) into local meter offsets relative to pA
            val ax = 0.0
            val ay = 0.0
            val bx = Math.toRadians(pB.longitude - pA.longitude) * EARTH_RADIUS_METERS * cosLat
            val by = Math.toRadians(pB.latitude - pA.latitude) * EARTH_RADIUS_METERS
            val px = Math.toRadians(userLon - pA.longitude) * EARTH_RADIUS_METERS * cosLat
            val py = Math.toRadians(userLat - pA.latitude) * EARTH_RADIUS_METERS

            val segDx = bx - ax
            val segDy = by - ay
            val segLenSq = segDx * segDx + segDy * segDy

            val t = if (segLenSq > 1e-6) {
                ((px * segDx + py * segDy) / segLenSq).coerceIn(0.0, 1.0)
            } else {
                0.0
            }

            val projX = t * segDx
            val projY = t * segDy
            val distToSegmentMeters = hypot(px - projX, py - projY)

            if (distToSegmentMeters < bestPerpendicularDistMeters) {
                bestPerpendicularDistMeters = distToSegmentMeters
                val segmentLengthMeters =
                    (pB.cumulativeDistanceFromStartMeters - pA.cumulativeDistanceFromStartMeters)
                        .coerceAtLeast(0.0)
                bestSnappedCumulativeMeters =
                    pA.cumulativeDistanceFromStartMeters + t * segmentLengthMeters
                bestSegmentIndex = i
            }
        }

        val effectiveTotal = routePoints.last().cumulativeDistanceFromStartMeters
            .coerceAtLeast(totalRouteDistanceMeters)
        val remainingMeters = (effectiveTotal - bestSnappedCumulativeMeters).coerceAtLeast(0.0)

        return RouteSnapResult(
            snappedDistanceAlongRouteMeters = bestSnappedCumulativeMeters,
            remainingRouteDistanceMeters = remainingMeters,
            distanceFromRouteMeters = bestPerpendicularDistMeters,
            nearestSegmentIndex = bestSegmentIndex,
            isOffRoute = bestPerpendicularDistMeters > OFF_ROUTE_THRESHOLD_METERS
        )
    }
}
