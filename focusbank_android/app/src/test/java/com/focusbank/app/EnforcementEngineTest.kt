package com.focusbank.app

import com.focusbank.app.StoreFixture.Companion.dayKey
import com.focusbank.app.StoreFixture.Companion.task
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val LAUNCHER = "com.android.launcher3"
private const val CHROME = "com.android.chrome"
private const val INSTA = "com.instagram.android"
private const val NOTES = "com.example.notes"
private const val KEYBOARD = "com.google.android.inputmethod.latin"

/** A scriptable phone: tests set what is on screen and read back what the engine did. */
private class FakePhone : EnforcementHost {
    override val ownPackage = "com.focusbank.app"
    var clock = 1_000_000L
    var onScreen: List<String> = listOf(LAUNCHER)
    var keyguardLocked = false
    val blocks = mutableListOf<Triple<String, Boolean, Long>>()
    val statuses = mutableListOf<String>()
    val expired = mutableListOf<String>()

    override fun now() = clock
    override fun foregroundCandidates() = onScreen
    override fun isKeyguardLocked() = keyguardLocked
    override fun isInputMethod(packageName: String) = packageName == KEYBOARD
    override fun isLauncherOrLaunchable(packageName: String) = packageName in setOf(LAUNCHER, CHROME, INSTA, NOTES)
    override fun block(packageName: String, isPenalty: Boolean, penaltySeconds: Long) {
        blocks += Triple(packageName, isPenalty, penaltySeconds)
        onScreen = listOf(ownPackage) // the overlay comes up
    }
    override fun showStatus(text: String) { statuses += text }
    override fun appLabel(packageName: String) = packageName.substringAfterLast('.')
    override fun onPenaltyExpired(packageName: String) { expired += packageName }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EnforcementEngineTest {
    private lateinit var fx: StoreFixture
    private lateinit var store: FocusBankStore
    private lateinit var phone: FakePhone
    private lateinit var engine: EnforcementEngine

    @Before fun setUp() {
        fx = StoreFixture()
        store = fx.newStore()
        store.addBlockedApp(CHROME)
        store.addBlockedApp(INSTA)
        phone = FakePhone()
        engine = EnforcementEngine(store, phone)
    }

    @After fun tearDown() = fx.close()

    /** Advance the fake clock and run the engine's 1-second tick [n] times. */
    private fun seconds(n: Int) = repeat(n) {
        phone.clock += 1_000L
        engine.tick()
    }

    private fun open(vararg packages: String) {
        phone.onScreen = packages.toList()
        engine.onWindowEvent(packages.first())
    }

    private fun usage(pkg: String) = store.usageToday().firstOrNull { it.packageName == pkg }?.seconds ?: 0L

    // --- Charging the Vault (FR-2.2) ---

    @Test fun protectedAppCostsOneVaultSecondPerSecond() {
        fx.setBalance(100)
        open(CHROME)
        seconds(10)
        assertEquals(90L, store.balanceSeconds())
        assertEquals(10L, usage(CHROME))
        assertEquals(CHROME, engine.activePackage)
    }

    @Test fun unprotectedAppsAndTheLauncherAreFree() {
        fx.setBalance(100)
        open(NOTES); seconds(5)
        open(LAUNCHER); seconds(5)
        assertEquals(100L, store.balanceSeconds())
    }

    @Test fun leavingForTheLauncherStopsCharging() {
        fx.setBalance(100)
        open(CHROME); seconds(3)
        open(LAUNCHER); seconds(5)
        assertEquals(97L, store.balanceSeconds())
        assertNull(engine.activePackage)
        assertEquals(EnforcementEngine.IDLE_STATUS, phone.statuses.last())
    }

    @Test fun switchingProtectedAppsWithoutAnEventKeepsCharging() {
        // Regression (1.2 audit): a switch noticed only by the tick left the new app uncharged.
        fx.setBalance(100)
        open(CHROME); seconds(2)
        phone.onScreen = listOf(INSTA) // no window event delivered
        seconds(5)
        assertEquals(93L, store.balanceSeconds())
        assertEquals(5L, usage(INSTA))
        assertEquals(INSTA, engine.activePackage)
    }

    @Test fun droppedWindowEventIsRecoveredByTheTick() {
        fx.setBalance(100)
        phone.onScreen = listOf(CHROME) // never announced
        seconds(3)
        assertEquals(97L, store.balanceSeconds())
    }

    @Test fun keyboardAndSystemUiDoNotEndTheSession() {
        fx.setBalance(100)
        open(CHROME)
        engine.onWindowEvent(KEYBOARD)
        engine.onWindowEvent("com.android.systemui")
        phone.onScreen = listOf(KEYBOARD, CHROME)
        seconds(4)
        assertEquals(96L, store.balanceSeconds())
    }

    @Test fun oemHelpersAreIgnoredUnlessTheUserProtectedThem() {
        fx.setBalance(100)
        open(CHROME)
        engine.onWindowEvent("com.vivo.upslide")
        assertEquals(CHROME, engine.activePackage)

        store.addBlockedApp("com.vivo.browser")
        assertTrue(!engine.isIgnored("com.vivo.browser"))
        assertTrue(engine.isIgnored("com.vivo.upslide"))
    }

    @Test fun openingFocusBankStopsChargingThePreviousApp() {
        fx.setBalance(100)
        open(CHROME); seconds(2)
        open(phone.ownPackage); seconds(5)
        assertEquals(98L, store.balanceSeconds())
        assertNull(engine.activePackage)
    }

    @Test fun screenOffAndLockScreenPauseCharging() {
        fx.setBalance(100)
        open(CHROME)
        engine.onScreenOff(); seconds(5)
        engine.onScreenOn()
        phone.keyguardLocked = true; seconds(5)
        phone.keyguardLocked = false; seconds(2)
        assertEquals(98L, store.balanceSeconds())
    }

    @Test fun appRemovedFromProtectionMidSessionStopsBeingCharged() {
        fx.setBalance(100)
        open(CHROME); seconds(2)
        store.removeBlockedApp(CHROME); seconds(3)
        assertEquals(98L, store.balanceSeconds())
        assertNull(engine.activePackage)
    }

    // --- Blocking (FR-2.3, FR-3.1) ---

    @Test fun emptyVaultBlocksOnEntry() {
        fx.setBalance(0)
        open(CHROME)
        assertEquals(listOf(Triple(CHROME, false, 0L)), phone.blocks)
        assertNull(engine.activePackage)
    }

    @Test fun runningOutMidSessionBlocksOnTheLastSecond() {
        fx.setBalance(3)
        open(CHROME); seconds(3)
        assertEquals(0L, store.balanceSeconds())
        assertEquals(listOf(Triple(CHROME, false, 0L)), phone.blocks)
    }

    @Test fun repeatedBlocksAreDebounced() {
        fx.setBalance(0)
        open(CHROME)
        phone.onScreen = listOf(CHROME) // the user fights the overlay
        phone.clock += 500L; engine.onWindowEvent(CHROME)
        assertEquals(1, phone.blocks.size)
        phone.clock += EnforcementEngine.BLOCK_DEBOUNCE_MS; engine.onWindowEvent(CHROME)
        assertEquals(2, phone.blocks.size)
    }

    @Test fun penalizedAppIsLockedEvenWithVaultTime() {
        fx.setBalance(100)
        store.setPenalty(INSTA, 10, "t")
        open(INSTA)
        assertEquals(listOf(Triple(INSTA, true, 600L)), phone.blocks)
        assertEquals(100L, store.balanceSeconds())
    }

    @Test fun penaltyStartingMidSessionLocksOnTheNextTick() {
        fx.setBalance(100)
        open(INSTA); seconds(2)
        store.setPenalty(INSTA, 1, "t")
        seconds(1)
        assertEquals(INSTA, phone.blocks.single().first)
        assertTrue(phone.blocks.single().second)
    }

    @Test fun dailyUnlockLetsProtectedAppsRunWithAnEmptyVault() {
        fx.setBalance(0)
        store.addTask(task("a")); store.completeTask("a")
        fx.setBalance(0)
        open(CHROME); seconds(3)
        assertTrue(phone.blocks.isEmpty())
        assertEquals(3L, usage(CHROME))
        assertTrue(phone.statuses.last().startsWith("Daily goal reached"))
    }

    @Test fun dailyUnlockWaitsForTheScreenFreeGoal() {
        store.addTask(task("a")); store.completeTask("a")
        fx.setBalance(0)
        store.setAwayGoalMinutes(60)
        open(CHROME)
        assertEquals(1, phone.blocks.size)
        fx.prefs.edit().putString("away_day", dayKey()).putLong("away_seconds", 3_600L).commit()
        phone.clock += EnforcementEngine.BLOCK_DEBOUNCE_MS
        open(CHROME)
        assertEquals(1, phone.blocks.size)
        assertEquals(CHROME, engine.activePackage)
    }

    // --- Penalties count down in real time while the Vault has time (FR-3.5) ---

    @Test fun penaltyCountsDownWhileTheVaultHasTimeEvenWhenIdle() {
        fx.setBalance(100)
        store.setPenalty(INSTA, 1, "t")
        open(LAUNCHER); seconds(10)
        engine.onScreenOff(); seconds(10)
        assertEquals(40L, store.penaltyRemainingSeconds(INSTA))
        assertEquals("serving a penalty costs no Vault time", 100L, store.balanceSeconds())
    }

    @Test fun penaltyPausesWhileTheVaultIsEmpty() {
        fx.setBalance(0)
        store.setPenalty(INSTA, 1, "t")
        open(LAUNCHER); seconds(30)
        assertEquals(60L, store.penaltyRemainingSeconds(INSTA))
    }

    @Test fun penaltyResumesOnceTheVaultHasTimeAgain() {
        fx.setBalance(0)
        store.setPenalty(INSTA, 1, "t")
        seconds(10)
        fx.setBalance(50)
        seconds(10)
        assertEquals(50L, store.penaltyRemainingSeconds(INSTA))
    }

    @Test fun usingAnotherAppDoesNotSpeedUpThePenalty() {
        fx.setBalance(100)
        store.setPenalty(INSTA, 1, "t")
        open(CHROME); seconds(20)
        assertEquals(40L, store.penaltyRemainingSeconds(INSTA))
        assertEquals(80L, store.balanceSeconds())
    }

    @Test fun penalizedAppStaysLockedUntilServed() {
        fx.setBalance(100)
        store.setPenalty(INSTA, 1, "t")
        seconds(30)
        open(INSTA)
        assertEquals(listOf(Triple(INSTA, true, 30L)), phone.blocks)
    }

    @Test fun penaltyRunningOutWhileTheVaultEmptiesStopsExactlyThere() {
        // Chrome drains the Vault; the penalty pauses the moment it hits 0.
        fx.setBalance(5)
        store.setPenalty(INSTA, 1, "t")
        open(CHROME); seconds(10)
        assertEquals(0L, store.balanceSeconds())
        assertEquals(55L, store.penaltyRemainingSeconds(INSTA))
    }

    @Test fun penaltyExpiryIsReportedOnceForTheRecheck() {
        fx.setBalance(100)
        store.setPenalty(INSTA, 1, "t")
        seconds(65)
        assertEquals(listOf(INSTA), phone.expired)
        open(INSTA)
        assertTrue("Instagram is free again once the penalty is served", phone.blocks.isEmpty())
    }
}
