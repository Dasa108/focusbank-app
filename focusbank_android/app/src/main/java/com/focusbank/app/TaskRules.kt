package com.focusbank.app

/**
 * Pure FocusBank rules with no Android dependencies, so they can be unit-tested on the JVM.
 * FocusBankStore, the receivers and the UI call into these instead of re-implementing them.
 */
object TaskRules {
    const val MAX_TITLE_LENGTH = 120
    const val MAX_TASK_MINUTES = 24 * 60
    const val MAX_AWAY_GOAL_MINUTES = 16 * 60
    const val DAY_MILLIS = 24L * 60L * 60L * 1000L
    val PRIORITIES = listOf("HIGH", "MEDIUM", "LOW")

    fun defaultReward(priority: String): Int = when (priority) { "HIGH" -> 30; "LOW" -> 10; else -> 20 }

    fun isLate(task: FocusTask, completedAt: Long = task.completedAt): Boolean =
        task.deadlineMillis > 0L && completedAt > task.deadlineMillis

    /** Spec §1.3: only tasks completed in time yield minutes. */
    fun rewardSeconds(task: FocusTask, completedAt: Long): Long =
        if (isLate(task, completedAt)) 0L else task.rewardMinutes * 60L

    fun earnedMinutes(task: FocusTask): Int = if (task.completed && !isLate(task)) task.rewardMinutes else 0

    /** FR-1.4: finishing at least 24 hours early grants a reroll. */
    fun grantsReroll(task: FocusTask, completedAt: Long): Boolean =
        task.deadlineMillis > 0L && task.deadlineMillis - completedAt >= DAY_MILLIS

    /**
     * One watchdog second of a penalty (FR-3.5). Returns the new remaining seconds, or null when the
     * penalty is paused (Vault empty) or already over. [PenaltyTick.expired] marks the final second.
     */
    fun penaltyTick(remainingSeconds: Long, balanceSeconds: Long): PenaltyTick? {
        if (balanceSeconds <= 0L || remainingSeconds <= 0L) return null
        return PenaltyTick(remainingSeconds - 1L, expired = remainingSeconds == 1L)
    }

    data class PenaltyTick(val remainingSeconds: Long, val expired: Boolean)

    /**
     * Screen-free seconds credited to today for one screen-off interval. Only time after
     * [countFrom] counts: the later of midnight and the day's first screen-on, so the night's
     * sleep before you pick the phone up does not satisfy the goal.
     */
    fun awayCreditSeconds(screenOffAt: Long, screenOnAt: Long, countFrom: Long): Long {
        if (screenOffAt <= 0L || countFrom <= 0L) return 0L
        val start = maxOf(screenOffAt, countFrom)
        return ((screenOnAt - start) / 1000L).coerceAtLeast(0L)
    }

    /**
     * FR-3.8 daily unlock: every stored task is done, at least one was finished today, and the
     * screen-free goal (if set) has been met.
     */
    fun dailyUnlock(tasks: List<FocusTask>, isToday: (Long) -> Boolean, awayGoalMinutes: Int, awaySecondsToday: Long): Boolean {
        val tasksDone = tasks.isNotEmpty() && tasks.all { it.completed } &&
            tasks.any { it.completedAt > 0L && isToday(it.completedAt) }
        return tasksDone && awayGoalMet(awayGoalMinutes, awaySecondsToday)
    }

    fun awayGoalMet(goalMinutes: Int, awaySecondsToday: Long): Boolean =
        goalMinutes <= 0 || awaySecondsToday >= goalMinutes * 60L

    enum class Quadrant(val label: String) {
        DO("Do now: urgent + important"),
        SCHEDULE("Schedule: important"),
        DELEGATE("Delegate: urgent"),
        DROP("Drop: neither")
    }

    // FR-1.2: priority stands in for importance, a deadline inside 24 hours for urgency.
    fun quadrant(task: FocusTask, now: Long): Quadrant {
        val important = task.priority != "LOW"
        val urgent = task.deadlineMillis > 0L && task.deadlineMillis - now <= DAY_MILLIS
        return when {
            important && urgent -> Quadrant.DO
            important -> Quadrant.SCHEDULE
            urgent -> Quadrant.DELEGATE
            else -> Quadrant.DROP
        }
    }

    enum class Sort(val label: String) { DUE_DATE("Due date"), PRIORITY("Priority"), EISENHOWER("Eisenhower") }

    fun priorityRank(priority: String): Int = PRIORITIES.indexOf(priority).let { if (it < 0) PRIORITIES.size else it }

    fun sort(tasks: List<FocusTask>, mode: Sort, now: Long): List<FocusTask> = when (mode) {
        Sort.DUE_DATE -> tasks.sortedBy { it.deadlineMillis }
        Sort.PRIORITY -> tasks.sortedWith(compareBy({ priorityRank(it.priority) }, { it.deadlineMillis }))
        Sort.EISENHOWER -> tasks.sortedWith(compareBy({ quadrant(it, now).ordinal }, { it.deadlineMillis }))
    }
}
