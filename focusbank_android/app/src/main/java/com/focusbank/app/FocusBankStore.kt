package com.focusbank.app

import android.content.SharedPreferences
import android.util.Base64
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

data class FocusTask(
    val id: String,
    val title: String,
    val priority: String,
    val rewardMinutes: Int,
    val completed: Boolean,
    val deadlineMillis: Long = 0L,
    val penaltyMinutes: Int = 0,
    val penaltyProcessed: Boolean = false,
    val reminderMillis: Long = 0L,
    val completedAt: Long = 0L
)

fun appLabel(context: android.content.Context, packageName: String): String = runCatching {
    val pm = context.packageManager
    pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
}.getOrDefault(packageName)

data class UsageSummary(val packageName: String, val seconds: Long)
data class ActivePenalty(val packageName: String, val untilMillis: Long, val penaltyId: Long)

class FocusBankStore(
    private val preferences: SharedPreferences,
    context: android.content.Context
) {
    private val dao = FocusBankDatabase.get(context).dao()

    init {
        migrateLegacyTasks()
        ensureDailyAllowance()
    }

    fun balanceSeconds(): Long {
        if (!preferences.contains("balance_seconds")) {
            return preferences.getInt("balance_minutes", 0).toLong() * 60L
        }
        return preferences.getLong("balance_seconds", 0L)
    }

    fun balanceMinutes(): Int = (balanceSeconds() / 60L).toInt()

    fun tasks(): List<FocusTask> = dao.allTasks().map(::fromEntity)

    fun addTask(task: FocusTask) = dao.saveTask(toEntity(task))

    fun completeTask(id: String) {
        val task = tasks().firstOrNull { it.id == id } ?: return
        if (task.completed) return
        val completedAt = System.currentTimeMillis()
        addTask(task.copy(completed = true, completedAt = completedAt))
        // Spec §1.3: only tasks completed in time yield minutes; a late completion stops re-penalties but earns nothing.
        if (!isLate(task, completedAt)) {
            preferences.edit().putLong("balance_seconds", balanceSeconds() + task.rewardMinutes * 60L).apply()
        }
        if (task.deadlineMillis - completedAt >= 24L * 60L * 60L * 1000L) {
            preferences.edit().putInt("penalty_rerolls", rerolls() + 1).apply()
        }
    }

    fun isLate(task: FocusTask, completedAt: Long = task.completedAt): Boolean =
        task.deadlineMillis > 0L && completedAt > task.deadlineMillis

    fun earnedMinutes(task: FocusTask): Int = if (task.completed && !isLate(task)) task.rewardMinutes else 0

    fun deleteTask(id: String) = dao.deleteTask(id)

    fun consumeSecond(packageName: String? = null) {
        preferences.edit().putLong("balance_seconds", (balanceSeconds() - 1L).coerceAtLeast(0L)).apply()
        if (packageName != null) recordUsageSecond(packageName)
    }

    fun reduceAllowance(minutes: Int) {
        if (minutes <= 0) return
        preferences.edit().putLong("balance_seconds", (balanceSeconds() - minutes * 60L).coerceAtLeast(0L)).apply()
    }

    fun rerolls(): Int = preferences.getInt("penalty_rerolls", 0)
    fun useReroll(): Boolean {
        if (rerolls() <= 0) return false
        preferences.edit().putInt("penalty_rerolls", rerolls() - 1).apply()
        return true
    }

    fun penaltyUntil(packageName: String): Long = preferences.getLong("penalty_until_$packageName", 0L)
    fun penaltyRemainingSeconds(packageName: String): Long = preferences.getLong("penalty_remaining_$packageName", 0L)
    fun setPenalty(packageName: String, durationMinutes: Int, taskId: String = "") {
        preferences.edit()
            .putLong("penalty_remaining_$packageName", durationMinutes.coerceAtLeast(0) * 60L)
            .putLong("penalty_id_$packageName", System.currentTimeMillis())
            .putString("penalty_task_$packageName", taskId)
            .apply()
    }
    fun penaltyId(packageName: String): Long = preferences.getLong("penalty_id_$packageName", 0L)
    fun penaltyTask(packageName: String): String = preferences.getString("penalty_task_$packageName", "").orEmpty()
    fun penaltyRollShown(penaltyId: Long): Boolean = preferences.getBoolean("penalty_roll_shown_$penaltyId", false)
    fun markPenaltyRollShown(penaltyId: Long) = preferences.edit().putBoolean("penalty_roll_shown_$penaltyId", true).apply()
    /** Burns one penalty second. Returns true when this second finished the penalty. */
    fun consumePenaltySecond(packageName: String): Boolean {
        if (balanceSeconds() <= 0L) return false
        val remaining = penaltyRemainingSeconds(packageName)
        if (remaining <= 0L) return false
        preferences.edit().putLong("penalty_remaining_$packageName", remaining - 1L).apply()
        return remaining == 1L
    }
    fun activePenalties(): List<ActivePenalty> = blockedApps().mapNotNull { packageName ->
        penaltyRemainingSeconds(packageName).takeIf { it > 0L }?.let { ActivePenalty(packageName, System.currentTimeMillis() + it * 1000L, penaltyId(packageName)) }
    }

    fun penaltyConfiguredApps(): List<String> = blockedApps().filter { penaltyRemainingSeconds(it) > 0L }

    fun extraAllowanceHour(): Int = preferences.getInt("extra_allowance_hour", -1)
    fun extraAllowanceMinute(): Int = preferences.getInt("extra_allowance_minute", -1)
    fun setExtraAllowanceTime(hour: Int, minute: Int) {
        preferences.edit().putInt("extra_allowance_hour", hour).putInt("extra_allowance_minute", minute).apply()
    }
    fun dailyUsageUnlocked(): Boolean {
        val today = dayKey(System.currentTimeMillis())
        val todayTasks = tasks().filter { it.completedAt > 0L && dayKey(it.completedAt) == today }
        val allTasks = tasks()
        return allTasks.isNotEmpty() && allTasks.all { it.completed } && todayTasks.isNotEmpty()
    }

    fun backupJson(): String {
        val root = JSONObject()
        root.put("balanceSeconds", balanceSeconds())
        root.put("rerolls", rerolls())
        root.put("blockedApps", JSONArray(blockedApps()))
        root.put("tasks", JSONArray(tasks().map { task ->
            JSONObject().apply {
                put("id", task.id); put("title", task.title); put("priority", task.priority)
                put("rewardMinutes", task.rewardMinutes); put("completed", task.completed)
                put("deadlineMillis", task.deadlineMillis); put("penaltyMinutes", task.penaltyMinutes)
                put("penaltyProcessed", task.penaltyProcessed); put("reminderMillis", task.reminderMillis)
                put("completedAt", task.completedAt)
            }
        }))
        root.put("appCategories", JSONObject(appCategories()))
        root.put("appAllowances", JSONObject(appAllowances()))
        root.put("extraAllowanceHour", extraAllowanceHour())
        root.put("extraAllowanceMinute", extraAllowanceMinute())
        root.put("exportedAt", System.currentTimeMillis())
        return root.toString()
    }

    fun restoreJson(json: String) {
        require(json.length <= 5_000_000) { "Backup is too large" }
        val root = JSONObject(json)
        val values = root.optJSONArray("tasks") ?: throw IllegalArgumentException("Backup has no tasks")
        require(values.length() <= 10_000) { "Backup contains too many tasks" }
        val restoredTasks = mutableListOf<FocusTask>()
        for (index in 0 until values.length()) {
            val value = values.getJSONObject(index)
            val title = value.getString("title")
            require(title.isNotBlank() && title.length <= MAX_TITLE_LENGTH) { "Task title is empty or too long" }
            val priority = value.getString("priority")
            require(priority in PRIORITIES) { "Unknown task priority: $priority" }
            val reward = value.getInt("rewardMinutes")
            require(reward in 0..MAX_TASK_MINUTES) { "Task reward is out of range" }
            val penalty = value.optInt("penaltyMinutes")
            require(penalty in 0..MAX_TASK_MINUTES) { "Task penalty is out of range" }
            restoredTasks += FocusTask(value.getString("id"), title, priority, reward, value.getBoolean("completed"), value.optLong("deadlineMillis"), penalty, value.optBoolean("penaltyProcessed"), value.optLong("reminderMillis"), value.optLong("completedAt"))
        }
        dao.clearTasks()
        restoredTasks.forEach(::addTask)
        val editor = preferences.edit().putLong("balance_seconds", root.optLong("balanceSeconds", 0L).coerceAtLeast(0L)).putInt("penalty_rerolls", root.optInt("rerolls", 0))
        root.optJSONArray("blockedApps")?.let { arr -> editor.putStringSet("blocked_apps", (0 until arr.length()).map { arr.getString(it) }.toSet()) }
        root.optJSONObject("appCategories")?.let { cats -> cats.keys().forEach { key -> editor.putString("app_category_$key", cats.getString(key)) } }
        root.optJSONObject("appAllowances")?.let { allows -> allows.keys().forEach { key -> setAppAllowance(key, allows.getInt(key).coerceIn(0, MAX_TASK_MINUTES)) } }
        val extraHour = root.optInt("extraAllowanceHour", -1)
        val extraMinute = root.optInt("extraAllowanceMinute", -1)
        if (extraHour in 0..23 && extraMinute in 0..59) {
            editor.putInt("extra_allowance_hour", extraHour).putInt("extra_allowance_minute", extraMinute)
        }
        editor.apply()
    }

    fun resetAll() {
        dao.clearTasks(); dao.clearUsage()
        usageCache.clear()
        val today = dayKey(System.currentTimeMillis())
        val extraGranted = preferences.getBoolean("extra_allowance_granted_$today", false)
        val darkTheme = preferences.getBoolean("dark_theme", false)
        // Keep today's grant markers: otherwise a reset would immediately mint another daily 30 minutes.
        preferences.edit().clear()
            .putString("daily_allowance_day", today)
            .putBoolean("extra_allowance_granted_$today", extraGranted)
            .putBoolean("dark_theme", darkTheme)
            .putBoolean("room_tasks_migrated", true)
            .apply()
    }

    fun ensureDailyAllowance() {
        val today = dayKey(System.currentTimeMillis())
        if (preferences.getString("daily_allowance_day", null) != today) {
            preferences.edit()
                .putString("daily_allowance_day", today)
                .putBoolean("extra_allowance_granted_$today", false)
                .putLong("balance_seconds", balanceSeconds() + 30L * 60L)
                .apply()
        }
        blockedApps().forEach { pkg ->
            val allowance = appAllowance(pkg)
            if (allowance != null) {
                val appDay = preferences.getString("app_allowance_day_$pkg", "")
                if (appDay != today) {
                    preferences.edit()
                        .putString("app_allowance_day_$pkg", today)
                        .putLong("app_allowance_seconds_$pkg", allowance * 60L)
                        .apply()
                }
            }
        }
    }

    fun blockedApps(): List<String> = preferences.getStringSet("blocked_apps", emptySet()).orEmpty().sorted()
    fun addBlockedApp(packageName: String) = preferences.edit().putStringSet("blocked_apps", blockedApps().toMutableSet().apply { add(packageName) }).apply()
    fun removeBlockedApp(packageName: String) = preferences.edit().putStringSet("blocked_apps", blockedApps().toMutableSet().apply { remove(packageName) }).apply()

    fun appCategories(): Map<String, String> = preferences.all
        .filterKeys { it.startsWith("app_category_") }
        .mapNotNull { (key, value) -> (value as? String)?.let { key.removePrefix("app_category_") to it } }
        .toMap()

    fun setAppCategory(packageName: String, category: String) {
        preferences.edit().putString("app_category_$packageName", category).apply()
    }

    fun appAllowance(packageName: String): Int? = preferences.getInt("app_allowance_$packageName", -1).takeIf { it >= 0 }
    fun appAllowanceSeconds(packageName: String): Long = preferences.getLong(
        "app_allowance_seconds_$packageName",
        appAllowance(packageName)?.toLong()?.times(60L) ?: -1L
    )
    fun appAllowances(): Map<String, Int> = blockedApps().mapNotNull { packageName -> appAllowance(packageName)?.let { packageName to it } }.toMap()

    fun setAppAllowance(packageName: String, minutes: Int) {
        val safeMinutes = minutes.coerceAtLeast(0)
        val today = dayKey(System.currentTimeMillis())
        preferences.edit()
            .putInt("app_allowance_$packageName", safeMinutes)
            .putLong("app_allowance_seconds_$packageName", safeMinutes * 60L)
            .putString("app_allowance_day_$packageName", today)
            .apply()
    }

    fun removeAppAllowance(packageName: String) {
        preferences.edit()
            .remove("app_allowance_$packageName")
            .remove("app_allowance_seconds_$packageName")
            .remove("app_allowance_day_$packageName")
            .apply()
    }

    fun consumeAppSecond(packageName: String): Boolean {
        val seconds = preferences.getLong("app_allowance_seconds_$packageName", appAllowance(packageName)?.toLong()?.times(60L) ?: -1L)
        if (seconds < 0L) return false
        val remaining = (seconds - 1L).coerceAtLeast(0L)
        preferences.edit().putLong("app_allowance_seconds_$packageName", remaining).apply()
        recordUsageSecond(packageName)
        return true
    }

    fun usageToday(): List<UsageSummary> = usageFor(dayKey(System.currentTimeMillis()))

    private fun usageFor(dayKey: String): List<UsageSummary> = dao.usageForDay(dayKey).map { UsageSummary(it.packageName, it.seconds) }

    private fun recordUsageSecond(packageName: String) {
        val key = dayKey(System.currentTimeMillis())
        val cacheKey = "$packageName:$key"
        val current = usageCache.compute(cacheKey) { _, prev ->
            if (prev == null) {
                runCatching {
                    dao.usageForDay(key).firstOrNull { it.packageName == packageName }?.seconds ?: 0L
                }.getOrDefault(0L) + 1L
            } else {
                prev + 1L
            }
        } ?: 1L

        runCatching {
            dao.saveUsage(UsageEntity(packageName, key, current))
        }.onFailure { e ->
            android.util.Log.w("FocusBank", "Failed to persist usage for $packageName: ${e.message}")
        }
    }

    private fun dayKey(time: Long): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(time))

    private fun migrateLegacyTasks() {
        if (preferences.getBoolean("room_tasks_migrated", false)) return
        preferences.getStringSet("tasks", emptySet()).orEmpty().mapNotNull { decodeLegacy(it) }.forEach { addTask(it) }
        preferences.edit().putBoolean("room_tasks_migrated", true).apply()
    }

    private fun decodeLegacy(value: String): FocusTask? {
        val parts = value.split("|")
        if (parts.size < 5) return null
        val title = runCatching { String(Base64.decode(parts[1], Base64.DEFAULT)) }.getOrNull() ?: return null
        return FocusTask(parts[0], title, parts[2], parts[3].toIntOrNull() ?: return null, parts[4].toBoolean(), parts.getOrNull(5)?.toLongOrNull() ?: 0L, parts.getOrNull(6)?.toIntOrNull() ?: 0, parts.getOrNull(7)?.toBoolean() ?: false, parts.getOrNull(8)?.toLongOrNull() ?: 0L)
    }

    companion object {
        const val MAX_TITLE_LENGTH = 120
        const val MAX_TASK_MINUTES = 24 * 60
        val PRIORITIES = listOf("HIGH", "MEDIUM", "LOW")
        fun defaultReward(priority: String): Int = when (priority) { "HIGH" -> 30; "LOW" -> 10; else -> 20 }

        // Process-wide: the accessibility service creates a fresh store every second, so an
        // instance-level cache was always empty and every tick paid for a DB read.
        private val usageCache = java.util.concurrent.ConcurrentHashMap<String, Long>()
    }

    private fun toEntity(task: FocusTask) = TaskEntity(task.id, task.title, task.priority, task.rewardMinutes, task.completed, task.deadlineMillis, task.penaltyMinutes, task.penaltyProcessed, task.reminderMillis, task.completedAt)
    private fun fromEntity(task: TaskEntity) = FocusTask(task.id, task.title, task.priority, task.rewardMinutes, task.completed, task.deadlineMillis, task.penaltyMinutes, task.penaltyProcessed, task.reminderMillis, task.completedAt)
}
