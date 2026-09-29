package com.focusbank.app

import android.content.Context
import android.content.SharedPreferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** A FocusBankStore backed by Robolectric SharedPreferences and an in-memory Room database. */
class StoreFixture {
    val context: Context = ApplicationProvider.getApplicationContext()
    val prefs: SharedPreferences = context.getSharedPreferences("focusbank", Context.MODE_PRIVATE).also {
        it.edit().clear().commit()
    }
    private val db = Room.inMemoryDatabaseBuilder(context, FocusBankDatabase::class.java)
        .allowMainThreadQueries() // tests drive the store synchronously
        .build()

    init {
        FocusBankStore.clearUsageCacheForTests()
    }

    fun newStore() = FocusBankStore(prefs, context, db.dao())

    fun setBalance(seconds: Long) = prefs.edit().putLong("balance_seconds", seconds).commit()

    fun close() {
        db.close()
        FocusBankStore.clearUsageCacheForTests()
    }

    companion object {
        const val HOUR = 60L * 60L * 1000L

        fun dayKey(time: Long = System.currentTimeMillis()): String =
            SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(time))

        /** Today at the given hour, so time-of-day tests never straddle midnight. */
        fun todayAt(hour: Int, minute: Int = 0): Long = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        fun task(
            id: String,
            priority: String = "MEDIUM",
            reward: Int = 20,
            deadline: Long = System.currentTimeMillis() + HOUR,
            penalty: Int = 10,
            completed: Boolean = false,
            completedAt: Long = 0L
        ) = FocusTask(id, "Task $id", priority, reward, completed, deadline, penalty, false, 0L, completedAt)
    }
}
