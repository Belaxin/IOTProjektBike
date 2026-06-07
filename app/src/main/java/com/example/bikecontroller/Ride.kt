package com.example.bikecontroller

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Delete
import androidx.room.Database
import androidx.room.RoomDatabase
import android.content.Context
import androidx.room.Room

@Entity(tableName = "rides")
data class Ride(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val date: Long,
    val distanceKm: Double,
    val durationSeconds: Long,
    val pathData: String,
    val name: String
)

@Dao
interface RideDao {
    @Insert
    suspend fun insertRide(ride: Ride)

    @Query("SELECT * FROM rides ORDER BY date DESC")
    suspend fun getAllRides(): List<Ride>

    @Query("SELECT * FROM rides ORDER BY distanceKm DESC LIMIT 10")
    suspend fun getTopDistanceRides(): List<Ride>

    @Query("SELECT * FROM rides ORDER BY durationSeconds DESC LIMIT 10")
    suspend fun getTopDurationRides(): List<Ride>

    @Query("SELECT * FROM rides ORDER BY (CASE WHEN durationSeconds > 0 THEN distanceKm / (durationSeconds / 3600.0) ELSE 0 END) DESC LIMIT 10")
    suspend fun getTopSpeedRides(): List<Ride>

    @Query("SELECT COUNT(*) FROM rides WHERE date >= :startOfDay AND date <= :endOfDay")
    suspend fun getRidesCountForDay(startOfDay: Long, endOfDay: Long): Int

    @Delete
    suspend fun deleteRide(ride: Ride)
}

@Database(entities = [Ride::class], version = 3)
abstract class AppDatabase : RoomDatabase() {
    abstract fun rideDao(): RideDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "bike_database"
                )
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
