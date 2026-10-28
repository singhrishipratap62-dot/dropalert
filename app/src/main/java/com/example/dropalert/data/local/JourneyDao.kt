package com.example.dropalert.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface JourneyDao {

    @Query("SELECT * FROM active_journey WHERE id = 1 LIMIT 1")
    fun observeActiveJourney(): Flow<ActiveJourneyEntity?>

    @Query("SELECT * FROM active_journey WHERE id = 1 LIMIT 1")
    suspend fun getActiveJourneyOnce(): ActiveJourneyEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertActiveJourney(journey: ActiveJourneyEntity)

    @Query("SELECT * FROM cached_route_points WHERE journeyId = 1 ORDER BY sequenceIndex ASC")
    suspend fun getCachedRoutePoints(): List<CachedRoutePointEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRoutePoints(points: List<CachedRoutePointEntity>)

    @Query("DELETE FROM cached_route_points WHERE journeyId = 1")
    suspend fun clearRoutePoints()

    @Query("DELETE FROM active_journey WHERE id = 1")
    suspend fun clearActiveJourney()

    @Transaction
    suspend fun saveNewJourneyWithRoute(
        journey: ActiveJourneyEntity,
        points: List<CachedRoutePointEntity>
    ) {
        clearRoutePoints()
        upsertActiveJourney(journey)
        insertRoutePoints(points)
    }

    @Transaction
    suspend fun deleteEntireJourney() {
        clearRoutePoints()
        clearActiveJourney()
    }
}
