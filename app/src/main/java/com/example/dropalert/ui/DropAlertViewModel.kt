package com.example.dropalert.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.dropalert.DropAlertApp
import com.example.dropalert.data.local.ActiveJourneyEntity
import com.example.dropalert.model.ConnectionEtaMode
import com.example.dropalert.model.DestinationPoint
import com.example.dropalert.model.JourneyStatus
import com.example.dropalert.model.JourneyUiState
import com.example.dropalert.service.JourneyTrackingService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class DropAlertViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as DropAlertApp
    private val repository = app.repository
    private val alarmController = app.alarmController

    private val _uiState = MutableStateFlow(JourneyUiState())
    val uiState: StateFlow<JourneyUiState> = _uiState.asStateFlow()

    init {
        refreshSystemWarnings()

        // Observe persisted Room journey state continuously
        viewModelScope.launch {
            repository.activeJourneyFlow.collect { entity ->
                if (entity == null) {
                    _uiState.update { current ->
                        current.copy(
                            status = JourneyStatus.IDLE,
                            estimatedMinutesRemaining = null,
                            estimatedSecondsRemaining = null,
                            remainingDistanceMeters = null,
                            currentSpeedMps = null,
                            isOffRoute = false,
                            isAlarmTriggered = false,
                            isAlarmDismissed = false
                        )
                    }
                } else {
                    applyEntityToUiState(entity)
                }
            }
        }
    }

    fun refreshSystemWarnings() {
        val powerManager = app.getSystemService(Context.POWER_SERVICE) as PowerManager
        val ignoringBatteryOpt = powerManager.isIgnoringBatteryOptimizations(app.packageName)
        val alarmMuted = alarmController.isAlarmStreamMuted()

        _uiState.update {
            it.copy(
                alarmStreamMutedWarning = alarmMuted,
                batteryOptimizationWarning = !ignoringBatteryOpt
            )
        }
    }

    fun onSearchQueryChanged(query: String) {
        _uiState.update {
            it.copy(
                searchQuery = query,
                errorMessage = null
            )
        }
    }

    fun onSearchDestinations() {
        val query = _uiState.value.searchQuery.trim()
        if (query.isEmpty()) {
            _uiState.update { it.copy(errorMessage = "Enter a place name, address, or 'lat, lon'.") }
            return
        }

        _uiState.update { it.copy(isSearchingDestination = true, errorMessage = null) }

        viewModelScope.launch {
            val result = repository.searchDestinations(query)
            result.fold(
                onSuccess = { places ->
                    if (places.isEmpty()) {
                        _uiState.update {
                            it.copy(
                                isSearchingDestination = false,
                                searchResults = emptyList(),
                                errorMessage = "No matching places found. Try a broader name or use Pick on Map."
                            )
                        }
                    } else {
                        _uiState.update {
                            it.copy(
                                isSearchingDestination = false,
                                searchResults = places,
                                selectedDestination = places.first(),
                                errorMessage = null
                            )
                        }
                    }
                },
                onFailure = { err ->
                    _uiState.update {
                        it.copy(
                            isSearchingDestination = false,
                            errorMessage = "Search failed (${err.localizedMessage ?: "Offline"}). You can still enter 'lat, lon' or tap Pick on Map."
                        )
                    }
                }
            )
        }
    }

    fun onSelectDestination(destination: DestinationPoint) {
        _uiState.update {
            it.copy(
                selectedDestination = destination,
                searchQuery = destination.name,
                searchResults = emptyList(),
                showMapPicker = false,
                errorMessage = null
            )
        }
    }

    fun onToggleMapPicker(show: Boolean) {
        _uiState.update { it.copy(showMapPicker = show, errorMessage = null) }
    }

    fun onStartJourney() {
        refreshSystemWarnings()
        val current = _uiState.value

        val destination = current.selectedDestination
        if (destination != null) {
            launchTrackingService(destination)
            return
        }

        // If user typed a query and tapped Start Journey directly without pressing Search first,
        // resolve the top destination automatically and start the journey.
        val query = current.searchQuery.trim()
        if (query.isEmpty()) {
            _uiState.update { it.copy(errorMessage = "Please enter or select a destination first.") }
            return
        }

        _uiState.update { it.copy(status = JourneyStatus.SEARCHING_ROUTE, errorMessage = null) }
        viewModelScope.launch {
            val result = repository.searchDestinations(query)
            val topPlace = result.getOrNull()?.firstOrNull()
            if (topPlace != null) {
                _uiState.update { it.copy(selectedDestination = topPlace, searchResults = emptyList()) }
                launchTrackingService(topPlace)
            } else {
                _uiState.update {
                    it.copy(
                        status = JourneyStatus.IDLE,
                        errorMessage = "Could not resolve '$query'. Check internet or pick a point on the map."
                    )
                }
            }
        }
    }

    private fun launchTrackingService(destination: DestinationPoint) {
        _uiState.update { it.copy(status = JourneyStatus.SEARCHING_ROUTE, errorMessage = null) }
        val serviceIntent = Intent(app, JourneyTrackingService::class.java).apply {
            action = JourneyTrackingService.ACTION_START_NEW_JOURNEY
            putExtra(JourneyTrackingService.EXTRA_DEST_NAME, destination.name)
            putExtra(JourneyTrackingService.EXTRA_DEST_LAT, destination.latitude)
            putExtra(JourneyTrackingService.EXTRA_DEST_LON, destination.longitude)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            app.startForegroundService(serviceIntent)
        } else {
            app.startService(serviceIntent)
        }
    }

    fun onDismissAlarm() {
        val intent = Intent(app, JourneyTrackingService::class.java).apply {
            action = JourneyTrackingService.ACTION_DISMISS_ALARM
        }
        app.startService(intent)
    }

    fun onCancelJourney() {
        val intent = Intent(app, JourneyTrackingService::class.java).apply {
            action = JourneyTrackingService.ACTION_CANCEL_JOURNEY
        }
        app.startService(intent)
    }

    private fun applyEntityToUiState(entity: ActiveJourneyEntity) {
        val status = runCatching { JourneyStatus.valueOf(entity.status) }
            .getOrDefault(JourneyStatus.TRACKING)
        val mode = runCatching { ConnectionEtaMode.valueOf(entity.connectionMode) }
            .getOrDefault(ConnectionEtaMode.ONLINE)

        val minutesRemaining = (entity.latestEtaSeconds / 60.0).roundToInt().coerceAtLeast(0)

        _uiState.update { current ->
            current.copy(
                selectedDestination = DestinationPoint(
                    name = entity.destinationName,
                    latitude = entity.destinationLat,
                    longitude = entity.destinationLon
                ),
                status = status,
                estimatedMinutesRemaining = minutesRemaining,
                estimatedSecondsRemaining = entity.latestEtaSeconds.roundToInt(),
                remainingDistanceMeters = entity.remainingDistanceMeters.roundToInt(),
                currentSpeedMps = entity.smoothedSpeedMps,
                isOffRoute = entity.isOffRoute,
                connectionMode = mode,
                isAlarmTriggered = entity.isAlarmTriggered,
                isAlarmDismissed = entity.isAlarmDismissed
            )
        }
    }
}
