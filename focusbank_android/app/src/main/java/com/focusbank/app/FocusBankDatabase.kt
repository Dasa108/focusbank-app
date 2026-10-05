package com.focusbank.app

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction

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
    val completedAt: Long,
    // v2: schedule-only "plan" tasks (no reward, no penalty, never block the daily unlock).
    @ColumnInfo(defaultValue = "0") val isPlan: Boolean = false
)

@Entity(tableName = "usage_records", primaryKeys = ["packageName", "dayKey"])
data class UsageEntity(
    val packageName: String,
    val dayKey: String,
    val seconds: Long
)

@Dao
abstract class FocusBankDao {
    @Query("SELECT * FROM tasks ORDER BY completed ASC, deadlineMillis ASC")
    abstract fun allTasks(): List<TaskEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract fun saveTask(task: TaskEntity)

    @Query("DELETE FROM tasks WHERE id = :id")
    abstract fun deleteTask(id: String)

    @Query("SELECT * FROM usage_records ORDER BY seconds DESC")
    abstract fun allUsage(): List<UsageEntity>

    @Query("SELECT * FROM usage_records WHERE dayKey = :dayKey ORDER BY seconds DESC")
    abstract fun usageForDay(dayKey: String): List<UsageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract fun saveUsage(usage: UsageEntity)

    @Query("DELETE FROM tasks")
    abstract fun clearTasks()

    @Query("DELETE FROM usage_records")
    abstract fun clearUsage()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract fun saveTasks(tasks: List<TaskEntity>)

    /** Backup restore: all-or-nothing, so a failure cannot leave the task list half-replaced. */
    @Transaction
    open fun replaceTasks(tasks: List<TaskEntity>) {
        clearTasks()
        saveTasks(tasks)
    }
}

@Database(entities = [TaskEntity::class, UsageEntity::class], version = 2, exportSchema = false)
abstract class FocusBankDatabase : RoomDatabase() {
    abstract fun dao(): FocusBankDao

    companion object {
        @Volatile private var instance: FocusBankDatabase? = null

        /** 1 → 2: add the plan flag; every existing task stays a commitment. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tasks ADD COLUMN isPlan INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun get(context: Context): FocusBankDatabase = instance ?: synchronized(this) {
            instance ?: build(context, "focusbank.db").also { instance = it }
        }

        internal fun build(context: Context, name: String): FocusBankDatabase =
            Room.databaseBuilder(context.applicationContext, FocusBankDatabase::class.java, name)
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
