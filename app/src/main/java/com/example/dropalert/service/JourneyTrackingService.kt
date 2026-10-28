package com.example.dropalert.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.example.dropalert.DropAlertApp
import com.example.dropalert.MainActivity
import com.example.dropalert.R
import com.example.dropalert.data.local.ActiveJourneyEntity
import com.example.dropalert.domain.RouteProgressCalculator
import com.example.dropalert.domain.ProgressEvaluationInput
import com.example.dropalert.model.ConnectionEtaMode
import com.example.dropalert.model.DestinationPoint
import com.example.dropalert.model.JourneyStatus
import com.example.dropalert.model.LatLngPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Phases 3, 4, 5 & 6: Foreground Location Tracking Service.
 * - Continues running with the screen locked.
 * - Uses native Android LocationManager (GPS_PROVIDER + NETWORK_PROVIDER) so it works on all
 *   Android devices without requiring proprietary Google Play Services libraries.
 * - Periodically refreshes online OSRM routes every 3 minutes when connected, or immediately
 *   falls back to local cached polyline snapping + EMA speed / last-known ETA when offline.
 * - Recovers automatically from process termination (START_STICKY) by reading Room state.
 */
class JourneyTrackingService : Service() {

    companion object {
        const val ACTION_START_NEW_JOURNEY = "com.example.dropalert.action.START_NEW_JOURNEY"
        const val ACTION_RESUME_SAVED_JOURNEY = "com.example.dropalert.action.RESUME_SAVED_JOURNEY"
        const val ACTION_DISMISS_ALARM = "com.example.dropalert.action.DISMISS_ALARM"
        const val ACTION_CANCEL_JOURNEY = "com.example.dropalert.action.CANCEL_JOURNEY"

        const val EXTRA_DEST_NAME = "extra_dest_name"
        const val EXTRA_DEST_LAT = "extra_dest_lat"
        const val EXTRA_DEST_LON = "extra_dest_lon"

        private const val TRACKING_CHANNEL_ID = "dropalert_tracking_channel"
        private const val ALARM_CHANNEL_ID = "dropalert_alarm_channel"
        private const val TRACKING_NOTIFICATION_ID = 1001
        private const val ALARM_NOTIFICATION_ID = 1002

        private const val LOCATION_INTERVAL_MS = 5_000L
        private const val LOCATION_MIN_DISTANCE_M = 10f
        private const val ONLINE_ROUTE_REFRESH_INTERVAL_MS = 180_000L // 3 minutes
        private const val GPS_WATCHDOG_TIMEOUT_MS = 45_000L
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var watchdogJob: Job? = null

    private lateinit var locationManager: LocationManager
    private var cachedRoutePoints: List<LatLngPoint> = emptyList()
    private var isLocationRegistered = false

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            serviceScope.launch {
                handleNewLocation(location)
            }
        }

        override fun onProviderEnabled(provider: String) {}

        override fun onProviderDisabled(provider: String) {
            serviceScope.launch {
                checkGpsAvailability()
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        createNotificationChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_RESUME_SAVED_JOURNEY

        when (action) {
            ACTION_START_NEW_JOURNEY -> {
                val name = intent?.getStringExtra(EXTRA_DEST_NAME) ?: return START_NOT_STICKY
                val lat = intent.getDoubleExtra(EXTRA_DEST_LAT, Double.NaN)
                val lon = intent.getDoubleExtra(EXTRA_DEST_LON, Double.NaN)
                if (lat.isNaN() || lon.isNaN()) return START_NOT_STICKY

                promoteToForeground(
                    buildTrackingNotification(
                        destName = name,
                        minutesRemaining = null,
                        modeLabel = "Calculating initial route..."
                    )
                )

                serviceScope.launch {
                    startNewJourneyInternal(DestinationPoint(name, lat, lon))
                }
            }

            ACTION_RESUME_SAVED_JOURNEY -> {
                promoteToForeground(
                    buildTrackingNotification(
                        destName = "Resuming saved journey...",
                        minutesRemaining = null,
                        modeLabel = "Restoring cached route"
                    )
                )
                serviceScope.launch {
                    resumeSavedJourneyInternal()
                }
            }

            ACTION_DISMISS_ALARM -> {
                val app = application as DropAlertApp
                app.alarmController.stopAlarm()
                cancelAlarmNotification()
                serviceScope.launch {
                    app.repository.markAlarmDismissed()
                    val current = app.repository.getActiveJourneyOnce()
                    if (current != null) {
                        updateTrackingNotificationFromEntity(current)
                    }
                }
            }

            ACTION_CANCEL_JOURNEY -> {
                val app = application as DropAlertApp
                app.alarmController.stopAlarm()
                cancelAlarmNotification()
                stopLocationUpdates()
                watchdogJob?.cancel()
                serviceScope.launch {
                    app.repository.cancelAndClearJourney()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
                return START_NOT_STICKY
            }
        }

        return START_STICKY
    }

    private suspend fun startNewJourneyInternal(destination: DestinationPoint) {
        val app = application as DropAlertApp
        val initialLocation = getBestLastKnownLocation()

        val startLat = initialLocation?.latitude ?: destination.latitude
        val startLon = initialLocation?.longitude ?: destination.longitude

        val entity = app.repository.initializeAndCacheJourney(
            startLat = startLat,
            startLon = startLon,
            destination = destination
        )
        cachedRoutePoints = app.repository.getCachedRoutePoints()

        registerLocationUpdates()
        startGpsWatchdog()
        updateTrackingNotificationFromEntity(entity)

        if (entity.isAlarmTriggered && !entity.isAlarmDismissed) {
            triggerTenMinuteAlarm(entity)
        }
    }

    private suspend fun resumeSavedJourneyInternal() {
        val app = application as DropAlertApp
        val saved = app.repository.getActiveJourneyOnce()
        if (saved == null || saved.status == JourneyStatus.COMPLETED_OR_CANCELLED.name) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        cachedRoutePoints = app.repository.getCachedRoutePoints()
        registerLocationUpdates()
        startGpsWatchdog()
        updateTrackingNotificationFromEntity(saved)

        if (saved.isAlarmTriggered && !saved.isAlarmDismissed) {
            triggerTenMinuteAlarm(saved)
        }
    }

    private suspend fun handleNewLocation(location: Location) {
        val app = application as DropAlertApp
        var currentEntity = app.repository.getActiveJourneyOnce() ?: return
        if (cachedRoutePoints.isEmpty()) {
            cachedRoutePoints = app.repository.getCachedRoutePoints()
        }

        val nowMs = System.currentTimeMillis()
        val isOnline = isInternetAvailable()

        // If the journey was started before a GPS fix was available, or if 3+ minutes have elapsed
        // while online (or the user deviated off-route while online), refresh the OSRM route.
        val timeSinceLastOnlineMs = nowMs - currentEntity.lastOnlineEtaTimestampMs
        val shouldRefreshOnline = isOnline && (
            cachedRoutePoints.size <= 2 ||
                timeSinceLastOnlineMs >= ONLINE_ROUTE_REFRESH_INTERVAL_MS ||
                (currentEntity.isOffRoute && timeSinceLastOnlineMs >= 45_000L)
            )

        var refreshedThisTurn = false
        if (shouldRefreshOnline) {
            app.repository.tryRefreshOnlineRoute(
                currentLat = location.latitude,
                currentLon = location.longitude,
                currentEntity = currentEntity,
                nowMs = nowMs
            )?.let { (updatedEntity, newPoints) ->
                currentEntity = updatedEntity
                cachedRoutePoints = newPoints
                refreshedThisTurn = true
            }
        }

        val isOnlineFresh = isOnline &&
            (refreshedThisTurn || (nowMs - currentEntity.lastOnlineEtaTimestampMs) < ONLINE_ROUTE_REFRESH_INTERVAL_MS)

        val evalOutput = RouteProgressCalculator.evaluate(
            ProgressEvaluationInput(
                currentLat = location.latitude,
                currentLon = location.longitude,
                currentTimestampMs = nowMs,
                gpsReportedSpeedMps = if (location.hasSpeed()) location.speed else null,
                gpsAccuracyMeters = if (location.hasAccuracy()) location.accuracy else null,
                previousLat = currentEntity.lastKnownLat,
                previousLon = currentEntity.lastKnownLon,
                previousTimestampMs = currentEntity.lastLocationTimestampMs,
                previousSmoothedSpeedMps = currentEntity.smoothedSpeedMps,
                cachedRoutePoints = cachedRoutePoints,
                totalRouteDistanceMeters = currentEntity.totalRouteDistanceMeters,
                initialRouteDurationSeconds = currentEntity.initialDurationSeconds,
                lastOnlineEtaSeconds = currentEntity.lastOnlineEtaSeconds,
                lastOnlineEtaTimestampMs = currentEntity.lastOnlineEtaTimestampMs,
                isOnlineRouteFresh = isOnlineFresh,
                consecutiveUnderThresholdCount = currentEntity.consecutiveUnderThresholdCount,
                isAlarmAlreadyTriggered = currentEntity.isAlarmTriggered,
                isAlarmDismissed = currentEntity.isAlarmDismissed
            )
        )

        val alarmTriggeredNow = currentEntity.isAlarmTriggered || evalOutput.shouldTriggerAlarmNow
        val updatedStatus = if (evalOutput.shouldTriggerAlarmNow) {
            JourneyStatus.ALARM_RINGING.name
        } else {
            currentEntity.status
        }

        val updatedEntity = currentEntity.copy(
            status = updatedStatus,
            latestEtaSeconds = evalOutput.estimatedSecondsRemaining,
            remainingDistanceMeters = evalOutput.remainingDistanceMeters,
            smoothedSpeedMps = evalOutput.smoothedSpeedMps,
            lastKnownLat = location.latitude,
            lastKnownLon = location.longitude,
            lastLocationTimestampMs = nowMs,
            connectionMode = evalOutput.connectionMode.name,
            isOffRoute = evalOutput.isOffRoute,
            isAlarmTriggered = alarmTriggeredNow,
            consecutiveUnderThresholdCount = evalOutput.consecutiveUnderThresholdCount
        )

        app.repository.updateActiveJourney(updatedEntity)
        updateTrackingNotificationFromEntity(updatedEntity)

        if (evalOutput.shouldTriggerAlarmNow) {
            triggerTenMinuteAlarm(updatedEntity)
        }
    }

    private fun startGpsWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = serviceScope.launch {
            while (isActive) {
                delay(15_000L)
                checkGpsAvailability()
            }
        }
    }

    private suspend fun checkGpsAvailability() {
        val app = application as DropAlertApp
        val current = app.repository.getActiveJourneyOnce() ?: return
        val isGpsEnabled = runCatching {
            locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        }.getOrDefault(false)

        val nowMs = System.currentTimeMillis()
        val lastFixAgeMs = current.lastLocationTimestampMs?.let { nowMs - it } ?: Long.MAX_VALUE

        if (!isGpsEnabled || lastFixAgeMs > GPS_WATCHDOG_TIMEOUT_MS) {
            // Even when GPS is temporarily unavailable, update fallback countdown ETA
            val elapsedOnlineSec =
                ((nowMs - current.lastOnlineEtaTimestampMs).coerceAtLeast(0L)) / 1000.0
            val fallbackRemainingSec =
                (current.lastOnlineEtaSeconds - elapsedOnlineSec).coerceAtLeast(60.0)

            val shouldTriggerOnFallback = !current.isAlarmTriggered &&
                !current.isAlarmDismissed &&
                fallbackRemainingSec <= RouteProgressCalculator.ALARM_THRESHOLD_SECONDS

            val updated = current.copy(
                connectionMode = ConnectionEtaMode.GPS_UNAVAILABLE.name,
                latestEtaSeconds = fallbackRemainingSec,
                isAlarmTriggered = current.isAlarmTriggered || shouldTriggerOnFallback,
                status = if (shouldTriggerOnFallback) {
                    JourneyStatus.ALARM_RINGING.name
                } else {
                    current.status
                }
            )
            app.repository.updateActiveJourney(updated)
            updateTrackingNotificationFromEntity(updated)

            if (shouldTriggerOnFallback) {
                triggerTenMinuteAlarm(updated)
            }
        }
    }

    private fun triggerTenMinuteAlarm(entity: ActiveJourneyEntity) {
        val app = application as DropAlertApp
        app.alarmController.startAlarm()

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(ALARM_NOTIFICATION_ID, buildAlarmNotification(entity.destinationName))
    }

    private fun cancelAlarmNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(ALARM_NOTIFICATION_ID)
    }

    @SuppressLint("MissingPermission")
    private fun registerLocationUpdates() {
        if (isLocationRegistered) return
        try {
            if (locationManager.AllProviders.contains(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    LOCATION_INTERVAL_MS,
                    LOCATION_MIN_DISTANCE_M,
                    locationListener,
                    Looper.getMainLooper()
                )
            }
            if (locationManager.AllProviders.contains(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    LOCATION_INTERVAL_MS,
                    LOCATION_MIN_DISTANCE_M,
                    locationListener,
                    Looper.getMainLooper()
                )
            }
            isLocationRegistered = true
        } catch (_: SecurityException) {
        }
    }

    private fun stopLocationUpdates() {
        if (!isLocationRegistered) return
        try {
            locationManager.removeUpdates(locationListener)
        } catch (_: Exception) {
        }
        isLocationRegistered = false
    }

    @SuppressLint("MissingPermission")
    private fun getBestLastKnownLocation(): Location? {
        return try {
            val gpsLoc = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            val netLoc = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            when {
                gpsLoc != null && netLoc != null ->
                    if (gpsLoc.time >= netLoc.time) gpsLoc else netLoc
                gpsLoc != null -> gpsLoc
                else -> netLoc
            }
        } catch (_: SecurityException) {
            null
        }
    }

    private fun isInternetAvailable(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun promoteToForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                TRACKING_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else {
            startForeground(TRACKING_NOTIFICATION_ID, notification)
        }
    }

    private fun updateTrackingNotificationFromEntity(entity: ActiveJourneyEntity) {
        val mins = (entity.latestEtaSeconds / 60.0).roundToInt().coerceAtLeast(0)
        val mode = runCatching { ConnectionEtaMode.valueOf(entity.connectionMode) }
            .getOrDefault(ConnectionEtaMode.ONLINE)

        val notification = buildTrackingNotification(
            destName = entity.destinationName,
            minutesRemaining = mins,
            modeLabel = mode.label
        )
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(TRACKING_NOTIFICATION_ID, notification)
    }

    private fun buildTrackingNotification(
        destName: String,
        minutesRemaining: Int?,
        modeLabel: String
    ): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java)
        val openAppPending = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val cancelIntent = Intent(this, JourneyTrackingService::class.java).apply {
            action = ACTION_CANCEL_JOURNEY
        }
        val cancelPending = PendingIntent.getService(
            this,
            1,
            cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val etaText = minutesRemaining?.let { "~$it min remaining" } ?: "Calculating ETA..."

        return NotificationCompat.Builder(this, TRACKING_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("DropAlert: $destName")
            .setContentText("$etaText • $modeLabel")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openAppPending)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Cancel Journey",
                cancelPending
            )
            .build()
    }

    private fun buildAlarmNotification(destName: String): Notification {
        val fullScreenIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val fullScreenPending = PendingIntent.getActivity(
            this,
            2,
            fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopAlarmIntent = Intent(this, JourneyTrackingService::class.java).apply {
            action = ACTION_DISMISS_ALARM
        }
        val stopAlarmPending = PendingIntent.getService(
            this,
            3,
            stopAlarmIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, ALARM_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("WAKE UP! ~10 MIN TO STOP")
            .setContentText("Approaching $destName — Tap Stop Alarm to dismiss")
            .setOngoing(true)
            .setAutoCancel(false)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setFullScreenIntent(fullScreenPending, true)
            .setContentIntent(fullScreenPending)
            .addAction(
                android.R.drawable.ic_media_pause,
                "STOP ALARM",
                stopAlarmPending
            )
            .build()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            val trackingChannel = NotificationChannel(
                TRACKING_CHANNEL_ID,
                getString(R.string.notification_channel_tracking_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Persistent status while monitoring journey progress"
            }

            val alarmChannel = NotificationChannel(
                ALARM_CHANNEL_ID,
                getString(R.string.notification_channel_alarm_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "High-priority 10-minute arrival warning"
                enableVibration(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }

            nm.createNotificationChannel(trackingChannel)
            nm.createNotificationChannel(alarmChannel)
        }
    }

    override fun onDestroy() {
        stopLocationUpdates()
        watchdogJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
