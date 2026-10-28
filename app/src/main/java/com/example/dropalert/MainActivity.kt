package com.example.dropalert

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.dropalert.model.ConnectionEtaMode
import com.example.dropalert.model.DestinationPoint
import com.example.dropalert.model.JourneyStatus
import com.example.dropalert.model.JourneyUiState
import com.example.dropalert.ui.DropAlertViewModel
import com.example.dropalert.ui.OsmMapPickerDialog

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val viewModel: DropAlertViewModel = viewModel()
                    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                    val context = LocalContext.current

                    val permissionLauncher = rememberLauncherForActivityResult(
                        contract = ActivityResultContracts.RequestMultiplePermissions()
                    ) { permissions ->
                        val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
                        val coarseGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
                        if (fineGranted || coarseGranted) {
                            viewModel.onStartJourney()
                        }
                    }

                    if (uiState.showMapPicker) {
                        OsmMapPickerDialog(
                            initialLat = uiState.selectedDestination?.latitude ?: 28.4595,
                            initialLon = uiState.selectedDestination?.longitude ?: 77.0266,
                            onDismiss = { viewModel.onToggleMapPicker(false) },
                            onConfirmPoint = { point -> viewModel.onSelectDestination(point) }
                        )
                    }

                    DropAlertMainScreen(
                        uiState = uiState,
                        onSearchQueryChanged = viewModel::onSearchQueryChanged,
                        onSearchClicked = viewModel::onSearchDestinations,
                        onOpenMapPicker = { viewModel.onToggleMapPicker(true) },
                        onSelectSearchResult = viewModel::onSelectDestination,
                        onStartJourney = {
                            val hasLocation = ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.ACCESS_FINE_LOCATION
                            ) == PackageManager.PERMISSION_GRANTED ||
                                ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.ACCESS_COARSE_LOCATION
                                ) == PackageManager.PERMISSION_GRANTED

                            if (hasLocation) {
                                viewModel.onStartJourney()
                            } else {
                                val required = mutableListOf(
                                    Manifest.permission.ACCESS_FINE_LOCATION,
                                    Manifest.permission.ACCESS_COARSE_LOCATION
                                )
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    required.add(Manifest.permission.POST_NOTIFICATIONS)
                                }
                                permissionLauncher.launch(required.toTypedArray())
                            }
                        },
                        onCancelJourney = viewModel::onCancelJourney,
                        onDismissAlarm = viewModel::onDismissAlarm,
                        onRequestIgnoreBatteryOptimizations = {
                            val intent = Intent(
                                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                Uri.parse("package:${context.packageName}")
                            )
                            runCatching { context.startActivity(intent) }
                        }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DropAlertMainScreen(
    uiState: JourneyUiState,
    onSearchQueryChanged: (String) -> Unit,
    onSearchClicked: () -> Unit,
    onOpenMapPicker: () -> Unit,
    onSelectSearchResult: (DestinationPoint) -> Unit,
    onStartJourney: () -> Unit,
    onCancelJourney: () -> Unit,
    onDismissAlarm: () -> Unit,
    onRequestIgnoreBatteryOptimizations: () -> Unit
) {
    val isJourneyActive = uiState.status == JourneyStatus.TRACKING ||
        uiState.status == JourneyStatus.SEARCHING_ROUTE ||
        uiState.status == JourneyStatus.ALARM_RINGING

    val isAlarmRingingNow = uiState.isAlarmTriggered && !uiState.isAlarmDismissed

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "DropAlert",
                        fontWeight = FontWeight.Bold
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. High-Visibility Stop Alarm Banner when <= 10 minutes remaining
            if (isAlarmRingingNow) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "WAKE UP! ~10 MIN TO DESTINATION",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Text(
                            text = uiState.selectedDestination?.name ?: "Approaching Stop",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Button(
                            onClick = onDismissAlarm,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                                contentColor = MaterialTheme.colorScheme.onError
                            )
                        ) {
                            Text(
                                text = "STOP ALARM",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            // 2. Android System Warnings (Alarm Stream Volume & Battery Optimization)
            if (uiState.alarmStreamMutedWarning || uiState.batteryOptimizationWarning) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (uiState.alarmStreamMutedWarning) {
                            Text(
                                text = "Warning: Your phone's Alarm Volume is currently set to 0. Turn up Alarm Volume so DropAlert can play audio over Silent/DND mode.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                fontWeight = FontWeight.Medium
                            )
                        }
                        if (uiState.batteryOptimizationWarning) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Battery saver may restrict background GPS while locked.",
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(onClick = onRequestIgnoreBatteryOptimizations) {
                                    Text("Allow Background")
                                }
                            }
                        }
                    }
                }
            }

            // 3. Destination Selection Section
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Destination",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )

                    OutlinedTextField(
                        value = uiState.searchQuery,
                        onValueChange = onSearchQueryChanged,
                        label = { Text("Search address, stop name, or 'lat, lon'") },
                        singleLine = true,
                        enabled = !isJourneyActive,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = onSearchClicked,
                            enabled = !isJourneyActive && !uiState.isSearchingDestination,
                            modifier = Modifier.weight(1f)
                        ) {
                            if (uiState.isSearchingDestination) {
                                CircularProgressIndicator(
                                    modifier = Modifier.height(18.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Text("Search Place")
                            }
                        }
                        OutlinedButton(
                            onClick = onOpenMapPicker,
                            enabled = !isJourneyActive,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Pick on Map")
                        }
                    }

                    if (uiState.errorMessage != null) {
                        Text(
                            text = uiState.errorMessage,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }

                    // Search Results List
                    if (uiState.searchResults.isNotEmpty() && !isJourneyActive) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surface
                            )
                        ) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                uiState.searchResults.forEachIndexed { index, place ->
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { onSelectSearchResult(place) }
                                            .padding(12.dp)
                                    ) {
                                        Text(
                                            text = place.name,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Medium
                                        )
                                        Text(
                                            text = String.format(
                                                "%.5f, %.5f",
                                                place.latitude,
                                                place.longitude
                                            ),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    if (index < uiState.searchResults.lastIndex) {
                                        Divider()
                                    }
                                }
                            }
                        }
                    }

                    if (uiState.selectedDestination != null) {
                        Text(
                            text = "Selected: ${uiState.selectedDestination.name}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            // 4. Primary Action: Start Journey or Cancel Journey
            if (!isJourneyActive) {
                Button(
                    onClick = onStartJourney,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                ) {
                    Text(
                        text = "Start Journey",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            } else {
                OutlinedButton(
                    onClick = onCancelJourney,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text(
                        text = "Cancel Journey",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            // 5. Active Journey Status & Connection/ETA Telemetry Card
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "Journey Status",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )

                    StatusRow(
                        label = "State",
                        value = when (uiState.status) {
                            JourneyStatus.IDLE -> "Idle — Enter destination & tap Start Journey"
                            JourneyStatus.SEARCHING_ROUTE -> "Calculating & caching route..."
                            JourneyStatus.TRACKING ->
                                if (uiState.isAlarmDismissed) {
                                    "Active — Alarm dismissed, approaching destination"
                                } else {
                                    "Active — Monitoring progress (10-min alarm armed)"
                                }
                            JourneyStatus.ALARM_RINGING -> "ALARM RINGING (<= 10 min remaining)"
                            JourneyStatus.COMPLETED_OR_CANCELLED -> "Completed / Cancelled"
                        }
                    )

                    StatusRow(
                        label = "Destination",
                        value = uiState.selectedDestination?.name ?: "None selected"
                    )

                    StatusRow(
                        label = "Estimated Time Remaining",
                        value = uiState.estimatedMinutesRemaining?.let { "~$it min" } ?: "—"
                    )

                    StatusRow(
                        label = "Remaining Route Distance",
                        value = uiState.remainingDistanceMeters?.let { meters ->
                            if (meters >= 1000) {
                                String.format("%.2f km (%d m)", meters / 1000.0, meters)
                            } else {
                                "$meters m"
                            }
                        } ?: "—"
                    )

                    StatusRow(
                        label = "Observed Transit Speed",
                        value = uiState.currentSpeedMps?.let { mps ->
                            if (mps >= 0.5) {
                                String.format("%.1f km/h", mps * 3.6)
                            } else {
                                "Stationary / Waiting for movement"
                            }
                        } ?: "—"
                    )

                    StatusRow(
                        label = "Connection / ETA Mode",
                        value = uiState.connectionMode.label,
                        highlightWarning = uiState.connectionMode != ConnectionEtaMode.ONLINE
                    )

                    if (uiState.isOffRoute) {
                        Text(
                            text = "Note: Vehicle deviated >300m from cached route. Using direct distance & fallback ETA until online route refreshes.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusRow(
    label: String,
    value: String,
    highlightWarning: Boolean = false
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            color = if (highlightWarning) {
                MaterialTheme.colorScheme.tertiary
            } else {
                MaterialTheme.colorScheme.onSurface
            }
        )
    }
}
