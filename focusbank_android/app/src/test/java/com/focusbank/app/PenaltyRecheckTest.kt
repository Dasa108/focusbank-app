package com.focusbank.app

import android.app.AlarmManager
import android.content.Context
import com.focusbank.app.StoreFixture.Companion.HOUR
import com.focusbank.app.StoreFixture.Companion.task
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PenaltyRecheckTest {
    private lateinit var fx: StoreFixture
    private lateinit var store: FocusBankStore

    @Before fun setUp() {
        fx = StoreFixture()
        store = fx.newStore()
    }

    @After fun tearDown() = fx.close()

    // --- FR-3.10: what happens when a penalty has been fully served ---

    @Test fun stillOpenTaskRollsANewPenalty() {
        store.addBlockedApp("com.insta")
        store.addTask(task("t", penalty = 15))
        store.setPenalty("com.insta", 15, "t")
        fx.prefs.edit().putLong("penalty_remaining_com.insta", 0L).commit() // served
        TaskDeadlineReceiver.onPenaltyExpired(fx.context, store, "com.insta")
        assertEquals(15 * 60L, store.penaltyRemainingSeconds("com.insta"))
        assertEquals("t", store.penaltyTask("com.insta"))
    }

    @Test fun completedTaskEndsThePenaltyChain() {
        store.addBlockedApp("com.insta")
        store.addTask(task("t"))
        store.completeTask("t")
        store.setPenalty("com.insta", 10, "t")
        fx.prefs.edit().putLong("penalty_remaining_com.insta", 0L).commit()
        TaskDeadlineReceiver.onPenaltyExpired(fx.context, store, "com.insta")
        assertEquals(0L, store.penaltyRemainingSeconds("com.insta"))
    }

    @Test fun aRerollAbsorbsTheNextPenalty() {
        store.addBlockedApp("com.insta")
        store.addTask(task("t"))
        fx.prefs.edit().putInt("penalty_rerolls", 1).commit()
        store.setPenalty("com.insta", 10, "t")
        fx.prefs.edit().putLong("penalty_remaining_com.insta", 0L).commit()
        TaskDeadlineReceiver.onPenaltyExpired(fx.context, store, "com.insta")
        assertEquals(0, store.rerolls())
        assertEquals(0L, store.penaltyRemainingSeconds("com.insta"))
    }

    @Test fun rerollIsKeptWhenNothingCouldBeLocked() {
        store.addBlockedApp("com.insta")
        store.addTask(task("t", penalty = 0))
        fx.prefs.edit().putInt("penalty_rerolls", 1).commit()
        store.setPenalty("com.insta", 10, "t")
        fx.prefs.edit().putLong("penalty_remaining_com.insta", 0L).commit()
        TaskDeadlineReceiver.onPenaltyExpired(fx.context, store, "com.insta")
        assertEquals(1, store.rerolls())
    }

    @Test fun planNeverStartsAPenaltyChain() {
        store.addBlockedApp("com.insta")
        store.addTask(task("p", penalty = 15).copy(isPlan = true))
        store.setPenalty("com.insta", 1, "p")
        fx.prefs.edit().putLong("penalty_remaining_com.insta", 0L).commit()
        TaskDeadlineReceiver.onPenaltyExpired(fx.context, store, "com.insta")
        assertEquals(0L, store.penaltyRemainingSeconds("com.insta"))
    }

    // --- Alarms (FR-1.1b, FR-6.4) ---

    @Test fun schedulingSetsDeadlineAndReminderAlarms() {
        val alarms = shadowOf(fx.context.getSystemService(Context.ALARM_SERVICE) as AlarmManager)
        val deadline = System.currentTimeMillis() + 3 * HOUR
        TaskDeadlineReceiver.schedule(fx.context, task("t", deadline = deadline).copy(reminderMillis = deadline - 2 * HOUR))
        assertEquals(setOf(deadline, deadline - 2 * HOUR), alarms.scheduledAlarms.map { it.triggerAtTime }.toSet())
    }

    @Test fun reminderAfterTheDeadlineFallsBackToOneHourBefore() {
        val alarms = shadowOf(fx.context.getSystemService(Context.ALARM_SERVICE) as AlarmManager)
        val deadline = System.currentTimeMillis() + 3 * HOUR
        TaskDeadlineReceiver.schedule(fx.context, task("t", deadline = deadline).copy(reminderMillis = deadline + HOUR))
        assertEquals(setOf(deadline, deadline - HOUR), alarms.scheduledAlarms.map { it.triggerAtTime }.toSet())
    }

    @Test fun planSchedulesOnlyItsReminder() {
        val alarms = shadowOf(fx.context.getSystemService(Context.ALARM_SERVICE) as AlarmManager)
        val at = System.currentTimeMillis() + 3 * HOUR
        TaskDeadlineReceiver.schedule(fx.context, task("p", deadline = at).copy(reminderMillis = at - 2 * HOUR, isPlan = true))
        assertEquals(listOf(at - 2 * HOUR), alarms.scheduledAlarms.map { it.triggerAtTime })
    }
}
