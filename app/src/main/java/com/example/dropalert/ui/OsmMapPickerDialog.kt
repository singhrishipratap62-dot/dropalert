package com.example.dropalert.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import com.example.dropalert.model.DestinationPoint
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker

/**
 * Phase 2: Optional interactive OpenStreetMap destination picker dialog.
 * Allows tapping any exact stop on the map without requiring an API key.
 */
@Composable
fun OsmMapPickerDialog(
    initialLat: Double = 28.4595, // Defaults to Gurugram center if no prior point selected
    initialLon: Double = 77.0266,
    onDismiss: () -> Unit,
    onConfirmPoint: (DestinationPoint) -> Unit
) {
    var tappedGeoPoint by remember { mutableStateOf(GeoPoint(initialLat, initialLon)) }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(480.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Tap Map to Select Exact Drop Point",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    text = String.format(
                        "Lat: %.5f, Lon: %.5f",
                        tappedGeoPoint.latitude,
                        tappedGeoPoint.longitude
                    ),
                    style = MaterialTheme.typography.bodySmall
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            MapView(ctx).apply {
                                setTileSource(TileSourceFactory.MAPNIK)
                                setMultiTouchControls(true)
                                controller.setZoom(13.5)
                                controller.setCenter(tappedGeoPoint)

                                val pinMarker = Marker(this).apply {
                                    position = tappedGeoPoint
                                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                                    title = "Drop Point"
                                }
                                overlays.add(pinMarker)

                                val receiver = object : MapEventsReceiver {
                                    override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean {
                                        if (p != null) {
                                            tappedGeoPoint = p
                                            pinMarker.position = p
                                            invalidate()
                                        }
                                        return true
                                    }

                                    override fun longPressHelper(p: GeoPoint?): Boolean = false
                                }
                                overlays.add(MapEventsOverlay(receiver))
                            }
                        }
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Cancel")
                    }
                    Button(
                        onClick = {
                            onConfirmPoint(
                                DestinationPoint(
                                    name = String.format(
                                        "Map Pin (%.4f, %.4f)",
                                        tappedGeoPoint.latitude,
                                        tappedGeoPoint.longitude
                                    ),
                                    latitude = tappedGeoPoint.latitude,
                                    longitude = tappedGeoPoint.longitude
                                )
                            )
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Use This Point")
                    }
                }
            }
        }
    }
}
