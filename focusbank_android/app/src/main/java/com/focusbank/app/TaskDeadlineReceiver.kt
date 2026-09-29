package com.focusbank.app

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import kotlin.random.Random

class TaskDeadlineReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val taskId = intent?.getStringExtra("task_id") ?: return
        // Alarms scheduled by 1.1.0 for timed rechecks: expiry is now detected by AppMonitorService (FR-3.10).
        if (intent.getBooleanExtra("penalty_recheck", false)) return
        val store = FocusBankStore(context.getSharedPreferences("focusbank", Context.MODE_PRIVATE), context)
        val task = store.tasks().firstOrNull { it.id == taskId } ?: return
        if (task.completed) return

        if (intent.getBooleanExtra("reminder", false)) {
            showNotification(context, "Task deadline approaching", "${task.title} is due soon.", taskId)
            return
        }

        if (task.deadlineMillis <= System.currentTimeMillis() && !task.penaltyProcessed) {
            applyPenalty(context, store, task, recheck = false)
            store.addTask(task.copy(penaltyProcessed = true))
        }
    }

    companion object {
        /** FR-3.10: called by the service when a penalty has fully counted down. */
        fun onPenaltyExpired(context: Context, store: FocusBankStore, packageName: String) {
            val taskId = store.penaltyTask(packageName)
            if (taskId.isEmpty()) return
            val task = store.tasks().firstOrNull { it.id == taskId } ?: return
            if (task.completed) return
            applyPenalty(context, store, task, recheck = true)
        }

        private fun applyPenalty(context: Context, store: FocusBankStore, task: FocusTask, recheck: Boolean) {
            val apps = store.blockedApps()
            // Nothing would be locked, so do not spend the user's reroll.
            if (apps.isEmpty() || task.penaltyMinutes <= 0) return
            if (store.useReroll()) {
                showNotification(context, "Penalty rerolled", "Your reroll protected you from the penalty for ${task.title}.", task.id)
                return
            }
            val selectedApp = apps[Random.nextInt(apps.size)]
            store.setPenalty(selectedApp, task.penaltyMinutes, task.id)
            val label = appLabel(context, selectedApp)
            if (recheck) {
                showNotification(context, "Penalty activated again", "${task.title} is still incomplete. $label is restricted again.", task.id)
            } else {
                showNotification(context, "Penalty activated", "${task.title} missed its deadline. $label is locked.", task.id)
            }
        }

        private fun showNotification(context: Context, title: String, text: String, taskId: String) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel("deadlines", "Task deadlines", NotificationManager.IMPORTANCE_DEFAULT))
            val notification = NotificationCompat.Builder(context, "deadlines")
                .setSmallIcon(R.drawable.ic_stat_focusbank)
                .setContentTitle(title)
                .setContentText(text)
                .setAutoCancel(true)
                .build()
            manager.notify(taskId.hashCode(), notification)
        }

        fun cancel(context: Context, taskId: String) {
            val alarmManager = context.getSystemService(AlarmManager::class.java)
            listOf(taskId.hashCode(), taskId.hashCode() + 1).forEach { requestCode ->
                PendingIntent.getBroadcast(
                    context,
                    requestCode,
                    Intent(context, TaskDeadlineReceiver::class.java),
                    PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
                )?.let { alarmManager.cancel(it); it.cancel() }
            }
        }

        fun rescheduleAll(context: Context, tasks: List<FocusTask>) {
            tasks.filterNot { it.completed }.forEach { schedule(context, it) }
        }

        fun schedule(context: Context, task: FocusTask) {
            cancel(context, task.id)
            if (task.deadlineMillis <= 0L) return
            val alarmManager = context.getSystemService(AlarmManager::class.java)
            val penaltyIntent = PendingIntent.getBroadcast(
                context, task.id.hashCode(), Intent(context, TaskDeadlineReceiver::class.java).apply {
                    putExtra("task_id", task.id)
                }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, task.deadlineMillis, penaltyIntent)
            val reminderTime = task.reminderMillis.takeIf { it > System.currentTimeMillis() && it < task.deadlineMillis }
                ?: (task.deadlineMillis - 60L * 60L * 1000L)
            if (reminderTime > System.currentTimeMillis()) {
                val reminderIntent = PendingIntent.getBroadcast(
                    context, task.id.hashCode() + 1, Intent(context, TaskDeadlineReceiver::class.java).apply {
                        putExtra("task_id", task.id)
                        putExtra("reminder", true)
                    }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, reminderTime, reminderIntent)
            }
        }
    }
}
