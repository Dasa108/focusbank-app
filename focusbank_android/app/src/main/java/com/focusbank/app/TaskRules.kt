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
    /** Plans never pay out; otherwise they would be a free way to mint minutes. */
    fun rewardSeconds(task: FocusTask, completedAt: Long): Long =
        if (task.isPlan || isLate(task, completedAt)) 0L else task.rewardMinutes * 60L

    fun earnedMinutes(task: FocusTask): Int = if (task.completed && !task.isPlan && !isLate(task)) task.rewardMinutes else 0

    /** FR-1.4: finishing a commitment at least 24 hours early grants a reroll. */
    fun grantsReroll(task: FocusTask, completedAt: Long): Boolean =
        !task.isPlan && task.deadlineMillis > 0L && task.deadlineMillis - completedAt >= DAY_MILLIS

    /** Last millisecond of the calendar day containing [time], in the device's time zone. */
    fun endOfDay(time: Long): Long = java.util.Calendar.getInstance().apply {
        timeInMillis = time
        set(java.util.Calendar.HOUR_OF_DAY, 23); set(java.util.Calendar.MINUTE, 59)
        set(java.util.Calendar.SECOND, 59); set(java.util.Calendar.MILLISECOND, 999)
    }.timeInMillis

    /**
     * One second of a penalty (FR-3.5). A penalty counts down in real time while the Vault holds
     * more than 0 seconds and pauses while it is empty. Returns null when nothing changes.
     * [PenaltyTick.expired] marks the final second.
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
     * FR-3.8 daily unlock: every commitment that is due by [dueBy] (today or overdue, or with no
     * deadline) is done, at least one commitment was finished today, and the screen-free goal (if
     * set) has been met. Plans never count, and commitments due on a later day don't hold it back.
     */
    fun dailyUnlock(tasks: List<FocusTask>, isToday: (Long) -> Boolean, dueBy: Long, awayGoalMinutes: Int, awaySecondsToday: Long): Boolean {
        val commitments = tasks.filterNot { it.isPlan }
        val dueOpen = commitments.any { !it.completed && (it.deadlineMillis <= 0L || it.deadlineMillis <= dueBy) }
        val finishedToday = commitments.any { it.completed && it.completedAt > 0L && isToday(it.completedAt) }
        return !dueOpen && finishedToday && awayGoalMet(awayGoalMinutes, awaySecondsToday)
    }

    /**
     * FR-4.6: a protected app's daily time limit is a cap on top of the Vault, not a separate bucket.
     * The app may run only while its limit (if any) has time left AND either the Vault has time or
     * the daily unlock is on. The limit holds even on an unlock day. [unlocked] is only evaluated
     * when it matters (it reads the task list).
     */
    fun canUseApp(vaultSeconds: Long, limitSecondsLeft: Long?, unlocked: () -> Boolean): Boolean {
        if (limitSecondsLeft != null && limitSecondsLeft <= 0L) return false
        return vaultSeconds > 0L || unlocked()
    }

    /** Seconds the user can still spend in the app right now, for the status line. */
    fun appSecondsLeft(vaultSeconds: Long, limitSecondsLeft: Long?, unlocked: Boolean): Long = when {
        limitSecondsLeft == null -> vaultSeconds
        vaultSeconds <= 0L && unlocked -> limitSecondsLeft
        else -> minOf(vaultSeconds, limitSecondsLeft)
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
