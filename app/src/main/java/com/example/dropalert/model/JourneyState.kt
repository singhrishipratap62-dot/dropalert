package com.example.dropalert.model

enum class JourneyStatus {
    IDLE,
    SEARCHING_ROUTE,
    TRACKING,
    ALARM_RINGING,
    COMPLETED_OR_CANCELLED
}

enum class ConnectionEtaMode(val label: String) {
    ONLINE("Online (Live Route & ETA)"),
    OFFLINE_ESTIMATE("Offline Estimate (Cached Route + GPS Speed)"),
    OFFLINE_FALLBACK_ETA("Offline Fallback (Last Known ETA)"),
    GPS_UNAVAILABLE("GPS Unavailable (Waiting for Signal)")
}

data class LatLngPoint(
    val latitude: Double,
    val longitude: Double,
    val cumulativeDistanceFromStartMeters: Double = 0.0
)

data class DestinationPoint(
    val name: String,
    val latitude: Double,
    val longitude: Double
)

data class JourneyUiState(
    val searchQuery: String = "",
    val searchResults: List<DestinationPoint> = emptyList(),
    val isSearchingDestination: Boolean = false,
    val showMapPicker: Boolean = false,
    val selectedDestination: DestinationPoint? = null,
    val status: JourneyStatus = JourneyStatus.IDLE,
    val estimatedMinutesRemaining: Int? = null,
    val estimatedSecondsRemaining: Int? = null,
    val remainingDistanceMeters: Int? = null,
    val currentSpeedMps: Double? = null,
    val isOffRoute: Boolean = false,
    val connectionMode: ConnectionEtaMode = ConnectionEtaMode.ONLINE,
    val isAlarmTriggered: Boolean = false,
    val isAlarmDismissed: Boolean = false,
    val alarmStreamMutedWarning: Boolean = false,
    val batteryOptimizationWarning: Boolean = false,
    val errorMessage: String? = null
)
