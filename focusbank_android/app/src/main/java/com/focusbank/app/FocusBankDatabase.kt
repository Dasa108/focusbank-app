package com.focusbank.app

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase

@Entity(tableName = "tasks")
data class TaskEntity(
    @androidx.room.PrimaryKey val id: String,
    val title: String,
    val priority: String,
    val rewardMinutes: Int,
    val completed: Boolean,
    val deadlineMillis: Long,
    val penaltyMinutes: Int,
    val penaltyProcessed: Boolean,
    val reminderMillis: Long,
    val completedAt: Long
)

@Entity(tableName = "usage_records", primaryKeys = ["packageName", "dayKey"])
data class UsageEntity(
    val packageName: String,
    val dayKey: String,
    val seconds: Long
)

@Dao
interface FocusBankDao {
    @Query("SELECT * FROM tasks ORDER BY completed ASC, deadlineMillis ASC")
    fun allTasks(): List<TaskEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun saveTask(task: TaskEntity)

    @Query("DELETE FROM tasks WHERE id = :id")
    fun deleteTask(id: String)

    @Query("SELECT * FROM usage_records ORDER BY seconds DESC")
    fun allUsage(): List<UsageEntity>

    @Query("SELECT * FROM usage_records WHERE dayKey = :dayKey ORDER BY seconds DESC")
    fun usageForDay(dayKey: String): List<UsageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun saveUsage(usage: UsageEntity)

    @Query("DELETE FROM tasks")
    fun clearTasks()

    @Query("DELETE FROM usage_records")
    fun clearUsage()
}

@Database(entities = [TaskEntity::class, UsageEntity::class], version = 1, exportSchema = false)
abstract class FocusBankDatabase : RoomDatabase() {
    abstract fun dao(): FocusBankDao

    companion object {
        @Volatile private var instance: FocusBankDatabase? = null

        fun get(context: Context): FocusBankDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                FocusBankDatabase::class.java,
                "focusbank.db"
            ).allowMainThreadQueries().build().also { instance = it }
        }
    }
}
