package com.focusbank.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskRulesTest {
    private val hour = 60L * 60L * 1000L
    private val now = 1_000L * hour

    private fun task(
        priority: String = "MEDIUM",
        reward: Int = 20,
        deadline: Long = now + hour,
        completed: Boolean = false,
        completedAt: Long = 0L
    ) = FocusTask("id-$priority-$deadline", "Task", priority, reward, completed, deadline, 60, false, 0L, completedAt)

    // --- Rewards and rerolls (§1.3, FR-1.4) ---

    @Test fun onTimeCompletionEarnsFullReward() {
        assertEquals(20 * 60L, TaskRules.rewardSeconds(task(), completedAt = now))
    }

    @Test fun completionExactlyAtDeadlineStillCounts() {
        val t = task(deadline = now)
        assertEquals(20 * 60L, TaskRules.rewardSeconds(t, completedAt = now))
    }

    @Test fun lateCompletionEarnsNothing() {
        assertEquals(0L, TaskRules.rewardSeconds(task(deadline = now - 1), completedAt = now))
    }

    @Test fun taskWithoutDeadlineIsNeverLate() {
        assertFalse(TaskRules.isLate(task(deadline = 0L), completedAt = now))
    }

    @Test fun earnedMinutesIgnoresOpenAndLateTasks() {
        assertEquals(0, TaskRules.earnedMinutes(task()))
        assertEquals(0, TaskRules.earnedMinutes(task(deadline = now, completed = true, completedAt = now + 1)))
        assertEquals(20, TaskRules.earnedMinutes(task(deadline = now, completed = true, completedAt = now)))
    }

    @Test fun rerollNeedsTwentyFourHoursToSpare() {
        assertTrue(TaskRules.grantsReroll(task(deadline = now + 24 * hour), completedAt = now))
        assertFalse(TaskRules.grantsReroll(task(deadline = now + 24 * hour - 1), completedAt = now))
        assertFalse(TaskRules.grantsReroll(task(deadline = 0L), completedAt = now))
    }

    @Test fun defaultRewardsFollowPriority() {
        assertEquals(30, TaskRules.defaultReward("HIGH"))
        assertEquals(20, TaskRules.defaultReward("MEDIUM"))
        assertEquals(10, TaskRules.defaultReward("LOW"))
    }

    // --- Penalty countdown (FR-3.5, FR-3.10) ---

    @Test fun penaltyPausesWhileTheVaultIsEmpty() {
        assertNull(TaskRules.penaltyTick(remainingSeconds = 30, balanceSeconds = 0))
    }

    @Test fun penaltyCountsDownWhileTheVaultHasTime() {
        assertEquals(TaskRules.PenaltyTick(29, expired = false), TaskRules.penaltyTick(30, balanceSeconds = 1))
    }

    @Test fun lastPenaltySecondReportsExpiryExactlyOnce() {
        assertEquals(TaskRules.PenaltyTick(0, expired = true), TaskRules.penaltyTick(1, balanceSeconds = 5))
        assertNull(TaskRules.penaltyTick(0, balanceSeconds = 5))
    }

    // --- Screen-free goal ---

    @Test fun screenOffTimeCountsAfterFirstWake() {
        val wake = now
        assertEquals(30 * 60L, TaskRules.awayCreditSeconds(wake + hour, wake + hour + 30 * 60_000L, countFrom = wake))
    }

    @Test fun nightBeforeFirstWakeDoesNotCount() {
        // Screen off from 23:00 until the first wake at 07:00: nothing credited.
        assertEquals(0L, TaskRules.awayCreditSeconds(now - 8 * hour, now, countFrom = 0L))
        // An interval that straddles the first wake only counts the part after it.
        assertEquals(60 * 60L, TaskRules.awayCreditSeconds(now - hour, now + hour, countFrom = now))
    }

    @Test fun missingScreenOffTimestampCreditsNothing() {
        assertEquals(0L, TaskRules.awayCreditSeconds(0L, now, countFrom = now - hour))
    }

    @Test fun goalOfZeroIsAlwaysMet() {
        assertTrue(TaskRules.awayGoalMet(0, 0L))
        assertFalse(TaskRules.awayGoalMet(60, 59 * 60L))
        assertTrue(TaskRules.awayGoalMet(60, 60 * 60L))
    }

    // --- Daily unlock (FR-3.8) ---

    private val doneToday = task(completed = true, completedAt = now)
    private val isToday: (Long) -> Boolean = { it >= now - hour }

    @Test fun unlockNeedsEveryTaskDoneAndOneFinishedToday() {
        assertTrue(TaskRules.dailyUnlock(listOf(doneToday), isToday, 0, 0L))
        assertFalse(TaskRules.dailyUnlock(emptyList(), isToday, 0, 0L))
        assertFalse(TaskRules.dailyUnlock(listOf(doneToday, task()), isToday, 0, 0L))
        val doneYesterday = task(completed = true, completedAt = now - 30 * hour)
        assertFalse(TaskRules.dailyUnlock(listOf(doneYesterday), isToday, 0, 0L))
    }

    @Test fun unlockWaitsForScreenFreeGoal() {
        assertFalse(TaskRules.dailyUnlock(listOf(doneToday), isToday, awayGoalMinutes = 120, awaySecondsToday = 119 * 60L))
        assertTrue(TaskRules.dailyUnlock(listOf(doneToday), isToday, awayGoalMinutes = 120, awaySecondsToday = 120 * 60L))
    }

    // --- Sorting (FR-1.2) ---

    @Test fun eisenhowerQuadrants() {
        assertEquals(TaskRules.Quadrant.DO, TaskRules.quadrant(task("HIGH", deadline = now + hour), now))
        assertEquals(TaskRules.Quadrant.SCHEDULE, TaskRules.quadrant(task("MEDIUM", deadline = now + 48 * hour), now))
        assertEquals(TaskRules.Quadrant.DELEGATE, TaskRules.quadrant(task("LOW", deadline = now + hour), now))
        assertEquals(TaskRules.Quadrant.DROP, TaskRules.quadrant(task("LOW", deadline = now + 48 * hour), now))
    }

    @Test fun prioritySortBreaksTiesByDeadline() {
        val lowSoon = task("LOW", deadline = now + hour)
        val highLate = task("HIGH", deadline = now + 5 * hour)
        val highSoon = task("HIGH", deadline = now + 2 * hour)
        val sorted = TaskRules.sort(listOf(lowSoon, highLate, highSoon), TaskRules.Sort.PRIORITY, now)
        assertEquals(listOf(highSoon, highLate, lowSoon), sorted)
    }

    @Test fun eisenhowerSortPutsDoNowFirst() {
        val drop = task("LOW", deadline = now + 48 * hour)
        val schedule = task("HIGH", deadline = now + 48 * hour)
        val doNow = task("MEDIUM", deadline = now + 3 * hour)
        assertEquals(listOf(doNow, schedule, drop), TaskRules.sort(listOf(drop, schedule, doNow), TaskRules.Sort.EISENHOWER, now))
    }
}
