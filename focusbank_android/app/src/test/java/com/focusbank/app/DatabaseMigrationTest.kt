package com.focusbank.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Upgrading from 1.5.x: a version 1 database (exact schema Room generated) must migrate to v2. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DatabaseMigrationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = "migration-test.db"

    @After fun tearDown() {
        context.deleteDatabase(name)
    }

    @Test fun version1TasksSurviveAndBecomeCommitments() {
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { db ->
            db.execSQL("CREATE TABLE IF NOT EXISTS `tasks` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, `priority` TEXT NOT NULL, `rewardMinutes` INTEGER NOT NULL, `completed` INTEGER NOT NULL, `deadlineMillis` INTEGER NOT NULL, `penaltyMinutes` INTEGER NOT NULL, `penaltyProcessed` INTEGER NOT NULL, `reminderMillis` INTEGER NOT NULL, `completedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
            db.execSQL("CREATE TABLE IF NOT EXISTS `usage_records` (`packageName` TEXT NOT NULL, `dayKey` TEXT NOT NULL, `seconds` INTEGER NOT NULL, PRIMARY KEY(`packageName`, `dayKey`))")
            db.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
            db.execSQL("INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, '57c0ff0f14d6c719ec09bb9790ff007a')")
            db.execSQL("INSERT INTO tasks VALUES('old', 'Write report', 'HIGH', 30, 0, 123456789, 60, 0, 0, 0)")
            db.execSQL("INSERT INTO usage_records VALUES('com.chrome', '2026-10-05', 90)")
            db.version = 1
        }

        // The production builder forbids main-thread queries, so read from a worker as the app does.
        val database = FocusBankDatabase.build(context, name)
        var migrated: TaskEntity? = null
        var usageSeconds = 0L
        var failure: Throwable? = null
        Thread {
            try {
                migrated = database.dao().allTasks().single()
                usageSeconds = database.dao().usageForDay("2026-10-05").single().seconds
            } catch (t: Throwable) {
                failure = t
            }
        }.apply { start(); join() }
        database.close()
        failure?.let { throw it }

        assertEquals("Write report", migrated!!.title)
        assertEquals(30, migrated!!.rewardMinutes)
        assertFalse(migrated!!.isPlan)
        assertEquals(90L, usageSeconds)
    }
}
