package com.focusbank.app

import com.focusbank.app.StoreFixture.Companion.HOUR
import com.focusbank.app.StoreFixture.Companion.dayKey
import com.focusbank.app.StoreFixture.Companion.task
import com.focusbank.app.StoreFixture.Companion.todayAt
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FocusBankStoreTest {
    private lateinit var fx: StoreFixture
    private lateinit var store: FocusBankStore

    @Before fun setUp() {
        fx = StoreFixture()
        store = fx.newStore()
    }

    @After fun tearDown() = fx.close()

    // --- Daily and extra allowance (FR-2.4 – FR-2.6) ---

    @Test fun dailyAllowanceIsGrantedOncePerDay() {
        assertEquals(30 * 60L, store.balanceSeconds())
        fx.newStore()
        store.ensureDailyAllowance()
        assertEquals(30 * 60L, store.balanceSeconds())
    }

    @Test fun newDayGrantsAgainAndPrunesOldMarkers() {
        fx.prefs.edit()
            .putString("daily_allowance_day", "2000-01-01")
            .putBoolean("extra_allowance_granted_2000-01-01", true)
            .putBoolean("penalty_roll_shown_42", true)
            .commit()
        store.ensureDailyAllowance()
        assertEquals(60 * 60L, store.balanceSeconds())
        assertFalse(fx.prefs.contains("extra_allowance_granted_2000-01-01"))
        assertFalse(fx.prefs.contains("penalty_roll_shown_42"))
    }

    @Test fun extraAllowanceIsGrantedOncePerDay() {
        assertTrue(store.grantExtraAllowance())
        assertFalse(store.grantExtraAllowance())
        assertEquals(60 * 60L, store.balanceSeconds())
    }

    // --- Task completion (FR-1.3, FR-1.4, §1.3) ---

    @Test fun onTimeCompletionCreditsRewardOnce() {
        store.addTask(task("a", reward = 20))
        store.completeTask("a")
        store.completeTask("a")
        assertEquals((30 + 20) * 60L, store.balanceSeconds())
        assertTrue(store.tasks().single().completed)
    }

    @Test fun lateCompletionCreditsNothing() {
        store.addTask(task("late", deadline = System.currentTimeMillis() - 1_000L))
        store.completeTask("late")
        assertEquals(30 * 60L, store.balanceSeconds())
        assertTrue(store.tasks().single().completed)
    }

    @Test fun finishingADayEarlyGrantsAReroll() {
        store.addTask(task("early", deadline = System.currentTimeMillis() + 25 * HOUR))
        store.addTask(task("soon", deadline = System.currentTimeMillis() + HOUR))
        store.completeTask("early")
        store.completeTask("soon")
        assertEquals(1, store.rerolls())
        assertTrue(store.useReroll())
        assertFalse(store.useReroll())
    }

    // --- Vault charging and penalties (FR-2.2, FR-3.5) ---

    @Test fun chargingTakesOneVaultSecondAndRecordsUsage() {
        fx.setBalance(2)
        assertTrue(store.chargeVaultSecond("com.chrome"))
        assertTrue(store.chargeVaultSecond("com.chrome"))
        assertFalse("an empty Vault cannot be charged", store.chargeVaultSecond("com.chrome"))
        assertEquals(0L, store.balanceSeconds())
        assertEquals(3L, store.usageToday().single { it.packageName == "com.chrome" }.seconds)
    }

    @Test fun penaltiesCountDownWhileTheVaultHasTimeAndDoNotCostVaultTime() {
        store.addBlockedApp("com.insta"); store.addBlockedApp("com.chrome")
        store.setPenalty("com.insta", 1, "t1")
        store.setPenalty("com.chrome", 1, "t2")
        store.tickPenalties()
        assertEquals(59L, store.penaltyRemainingSeconds("com.insta"))
        assertEquals(59L, store.penaltyRemainingSeconds("com.chrome"))
        assertEquals(30 * 60L, store.balanceSeconds())
    }

    @Test fun penaltiesPauseWhileTheVaultIsEmpty() {
        store.addBlockedApp("com.insta")
        store.setPenalty("com.insta", 1, "t1")
        fx.setBalance(0)
        repeat(10) { store.tickPenalties() }
        assertEquals(60L, store.penaltyRemainingSeconds("com.insta"))
        fx.setBalance(1)
        store.tickPenalties()
        assertEquals(59L, store.penaltyRemainingSeconds("com.insta"))
    }

    @Test fun penaltyExpiryIsReportedOnTheLastSecondOnly() {
        store.addBlockedApp("com.insta")
        store.setPenalty("com.insta", 1, "t1")
        val expiries = (1..61).map { store.tickPenalties() }
        assertEquals(listOf("com.insta"), expiries[59])
        assertEquals(1, expiries.count { it.isNotEmpty() })
        assertEquals(0L, store.penaltyRemainingSeconds("com.insta"))
        assertTrue(store.activePenalties().isEmpty())
    }

    @Test fun appAllowanceUsesItsOwnBucket() {
        store.addBlockedApp("com.game")
        store.setAppAllowance("com.game", 1)
        repeat(3) { store.consumeAppSecond("com.game") }
        assertEquals(57L, store.appAllowanceSeconds("com.game"))
        assertEquals(30 * 60L, store.balanceSeconds())
    }

    @Test fun reduceAllowanceNeverGoesNegative() {
        store.reduceAllowance(45)
        assertEquals(0L, store.balanceSeconds())
    }

    // --- Screen-free goal (FR-5.6) and daily unlock (FR-3.8) ---

    @Test fun screenOffTimeCountsOnlyAfterTheFirstWake() {
        store.onScreenOff(todayAt(0, 30))
        store.onScreenOn(todayAt(7))           // first wake: the night is not credited
        assertEquals(0L, store.awaySecondsToday(todayAt(7)))
        store.onScreenOff(todayAt(9))
        store.onScreenOn(todayAt(9, 45))
        store.onScreenOff(todayAt(12))
        store.onScreenOn(todayAt(12, 15))
        assertEquals(60L * 60L, store.awaySecondsToday(todayAt(13)))
    }

    @Test fun dailyUnlockWaitsForTheScreenFreeGoal() {
        store.addTask(task("a"))
        store.completeTask("a")
        assertTrue(store.dailyUsageUnlocked())
        store.setAwayGoalMinutes(30)
        assertFalse(store.dailyUsageUnlocked())
        fx.prefs.edit().putString("away_day", dayKey()).putLong("away_seconds", 30 * 60L).commit()
        assertTrue(store.dailyUsageUnlocked())
    }

    @Test fun openTaskKeepsTheDayLocked() {
        store.addTask(task("done")); store.addTask(task("open"))
        store.completeTask("done")
        assertFalse(store.dailyUsageUnlocked())
    }

    @Test fun awayGoalIsClamped() {
        store.setAwayGoalMinutes(99_999)
        assertEquals(TaskRules.MAX_AWAY_GOAL_MINUTES, store.awayGoalMinutes())
        store.setAwayGoalMinutes(-5)
        assertEquals(0, store.awayGoalMinutes())
    }

    // --- Backup, restore, reset (FR-6.1 – FR-6.3) ---

    @Test fun backupRoundTripsEverySetting() {
        store.addTask(task("a", priority = "HIGH", reward = 30))
        store.addBlockedApp("com.insta")
        store.setAppCategory("com.insta", "Social")
        store.setAppAllowance("com.insta", 15)
        store.setExtraAllowanceTime(18, 30)
        store.setAwayGoalMinutes(90)
        val json = store.backupJson()

        store.resetAll()
        val restored = fx.newStore()
        restored.restoreJson(json)

        assertEquals(listOf("a"), restored.tasks().map { it.id })
        assertEquals(30 * 60L, restored.balanceSeconds())
        assertEquals(listOf("com.insta"), restored.blockedApps())
        assertEquals("Social", restored.appCategories()["com.insta"])
        assertEquals(15, restored.appAllowance("com.insta"))
        assertEquals(18, restored.extraAllowanceHour())
        assertEquals(30, restored.extraAllowanceMinute())
        assertEquals(90, restored.awayGoalMinutes())
    }

    @Test fun invalidBackupIsRejectedAndLeavesTasksUntouched() {
        store.addTask(task("keep"))
        val bad = JSONObject(store.backupJson())
        bad.getJSONArray("tasks").getJSONObject(0).put("priority", "URGENT")
        assertRestoreFails(bad.toString())
        bad.getJSONArray("tasks").getJSONObject(0).put("priority", "LOW").put("rewardMinutes", 99_999)
        assertRestoreFails(bad.toString())
        assertRestoreFails("{}")
        assertEquals(listOf("keep"), store.tasks().map { it.id })
    }

    private fun assertRestoreFails(json: String) {
        try {
            store.restoreJson(json)
            fail("restore should have been rejected")
        } catch (expected: Exception) {
        }
    }

    @Test fun resetClearsDataButDoesNotMintAnotherDailyAllowance() {
        store.addTask(task("a"))
        store.addBlockedApp("com.insta")
        store.setPenalty("com.insta", 5, "a")
        fx.prefs.edit().putBoolean("dark_theme", true).commit()
        store.resetAll()

        val fresh = fx.newStore()
        assertTrue(fresh.tasks().isEmpty())
        assertTrue(fresh.blockedApps().isEmpty())
        assertEquals(0L, fresh.balanceSeconds())
        assertTrue(fx.prefs.getBoolean("dark_theme", false))
    }

    @Test fun legacyTasksMigrateOnFirstRead() {
        val title = android.util.Base64.encodeToString("Old task".toByteArray(), android.util.Base64.DEFAULT)
        fx.prefs.edit()
            .remove("room_tasks_migrated")
            .putStringSet("tasks", setOf("legacy|$title|HIGH|30|false|0|0|false|0"))
            .commit()
        val migrated = fx.newStore().tasks().single()
        assertEquals("Old task", migrated.title.trim())
        assertEquals("HIGH", migrated.priority)
        assertFalse(fx.prefs.contains("tasks"))
    }
}
