package com.example.dropalert.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.example.dropalert.DropAlertApp
import com.example.dropalert.model.JourneyStatus
import com.example.dropalert.service.JourneyTrackingService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Phase 6: Automatically resumes an active journey if the phone restarts or the app package is updated
 * mid-journey.
 */
class BootRecoveryReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            return
        }

        val pendingResult = goAsync()
        val app = context.applicationContext as DropAlertApp

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val active = app.repository.getActiveJourneyOnce()
                if (active != null &&
                    (active.status == JourneyStatus.TRACKING.name ||
                        active.status == JourneyStatus.ALARM_RINGING.name)
                ) {
                    val serviceIntent = Intent(context, JourneyTrackingService::class.java).apply {
                        this.action = JourneyTrackingService.ACTION_RESUME_SAVED_JOURNEY
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceIntent)
                    } else {
                        context.startService(serviceIntent)
                    }
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
