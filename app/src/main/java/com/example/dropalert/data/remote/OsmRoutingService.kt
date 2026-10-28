package com.example.dropalert.data.remote

import com.example.dropalert.domain.GeoMath
import com.example.dropalert.model.DestinationPoint
import com.example.dropalert.model.LatLngPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class CalculatedRoute(
    val points: List<LatLngPoint>,
    val totalDistanceMeters: Double,
    val durationSeconds: Double
)

class OsmRoutingService {

    companion object {
        private const val USER_AGENT = "DropAlert-PersonalUtility/1.0 (Android)"
        private const val NOMINATIM_SEARCH_URL = "https://nominatim.openstreetmap.org/search"
        private const val OSRM_ROUTE_BASE_URL = "https://router.project-osrm.org/route/v1/driving"
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 10_000
    }

    /**
     * Searches OpenStreetMap Nominatim for matching destination places.
     * Also parses direct "lat, lon" coordinate strings offline if entered by the user.
     */
    suspend fun searchDestinations(query: String): Result<List<DestinationPoint>> =
        withContext(Dispatchers.IO) {
            val trimmed = query.trim()
            if (trimmed.isEmpty()) return@withContext Result.success(emptyList())

            // Support direct coordinate input "lat, lon" even without geocoding
            parseCoordinateInput(trimmed)?.let { coordPoint ->
                return@withContext Result.success(listOf(coordPoint))
            }

            runCatching {
                val encodedQuery = URLEncoder.encode(trimmed, "UTF-8")
                val url = URL("$NOMINATIM_SEARCH_URL?q=$encodedQuery&format=json&limit=5")
                val response = executeGet(url)
                val jsonArray = JSONArray(response)
                val results = mutableListOf<DestinationPoint>()

                for (i in 0 until jsonArray.length()) {
                    val item = jsonArray.getJSONObject(i)
                    val displayName = item.optString("display_name", trimmed)
                    val lat = item.getString("lat").toDouble()
                    val lon = item.getString("lon").toDouble()
                    results.add(
                        DestinationPoint(
                            name = displayName,
                            latitude = lat,
                            longitude = lon
                        )
                    )
                }
                results
            }
        }

    /**
     * Calculates a driving/transit route from (startLat, startLon) to (destLat, destLon)
     * using OSRM with full GeoJSON geometry so we can cache every vertex locally.
     */
    suspend fun fetchRoute(
        startLat: Double,
        startLon: Double,
        destLat: Double,
        destLon: Double
    ): Result<CalculatedRoute> = withContext(Dispatchers.IO) {
        runCatching {
            val coordinatesPath = "$startLon,$startLat;$destLon,$destLat"
            val url = URL("$OSRM_ROUTE_BASE_URL/$coordinatesPath?overview=full&geometries=geojson")
            val response = executeGet(url)
            val root = JSONObject(response)

            val routes = root.getJSONArray("routes")
            if (routes.length() == 0) {
                throw IllegalStateException("No route found between current location and destination.")
            }

            val primaryRoute = routes.getJSONObject(0)
            val totalDistanceMeters = primaryRoute.getDouble("distance")
            val durationSeconds = primaryRoute.getDouble("duration")

            val geometry = primaryRoute.getJSONObject("geometry")
            val coordsArray = geometry.getJSONArray("coordinates")

            val rawLatLngs = mutableListOf<Pair<Double, Double>>()
            for (i in 0 until coordsArray.length()) {
                val pair = coordsArray.getJSONArray(i)
                val lon = pair.getDouble(0)
                val lat = pair.getDouble(1)
                rawLatLngs.add(lat to lon)
            }

            val cumulativePoints = GeoMath.buildCumulativeRoutePoints(rawLatLngs)
            val effectiveTotalDistance = if (cumulativePoints.isNotEmpty()) {
                cumulativePoints.last().cumulativeDistanceFromStartMeters.coerceAtLeast(totalDistanceMeters)
            } else {
                totalDistanceMeters
            }

            CalculatedRoute(
                points = cumulativePoints,
                totalDistanceMeters = effectiveTotalDistance,
                durationSeconds = durationSeconds
            )
        }
    }

    private fun parseCoordinateInput(input: String): DestinationPoint? {
        val parts = input.split(",")
        if (parts.size != 2) return null
        val lat = parts[0].trim().toDoubleOrNull() ?: return null
        val lon = parts[1].trim().toDoubleOrNull() ?: return null
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        return DestinationPoint(
            name = String.format("Pinned Point (%.5f, %.5f)", lat, lon),
            latitude = lat,
            longitude = lon
        )
    }

    private fun executeGet(url: URL): String {
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/json")
        }
        try {
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = BufferedReader(InputStreamReader(stream)).use { it.readText() }
            if (status !in 200..299) {
                throw IllegalStateException("HTTP $status from ${url.host}: $text")
            }
            return text
        } finally {
            connection.disconnect()
        }
    }
}
