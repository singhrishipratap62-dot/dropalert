package com.example.dropalert

import android.app.Application
import com.example.dropalert.alarm.AlarmController
import com.example.dropalert.data.local.DropAlertDatabase
import com.example.dropalert.data.remote.OsmRoutingService
import com.example.dropalert.data.repository.JourneyRepository
import org.osmdroid.config.Configuration

class DropAlertApp : Application() {

    lateinit var database: DropAlertDatabase
        private set

    lateinit var repository: JourneyRepository
        private set

    lateinit var alarmController: AlarmController
        private set

    override fun onCreate() {
        super.onCreate()
        // Configure OSMDroid User-Agent so tile/nominatim servers accept requests
        Configuration.getInstance().userAgentValue = "DropAlert-PersonalUtility/1.0 (Android)"

        database = DropAlertDatabase.getInstance(this)
        val routingService = OsmRoutingService()
        repository = JourneyRepository(
            dao = database.journeyDao(),
            routingService = routingService
        )
        alarmController = AlarmController(this)
    }
}
