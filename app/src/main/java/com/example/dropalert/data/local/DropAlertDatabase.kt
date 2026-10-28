package com.example.dropalert.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [ActiveJourneyEntity::class, CachedRoutePointEntity::class],
    version = 1,
    exportSchema = false
)
abstract class DropAlertDatabase : RoomDatabase() {

    abstract fun journeyDao(): JourneyDao

    companion object {
        @Volatile
        private var INSTANCE: DropAlertDatabase? = null

        fun getInstance(context: Context): DropAlertDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    DropAlertDatabase::class.java,
                    "drop_alert_db"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
