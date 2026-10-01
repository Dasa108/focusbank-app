package com.focusbank.app

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import java.util.Calendar

class ExtraAllowanceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val preferences = context.getSharedPreferences("focusbank", Context.MODE_PRIVATE)
        // A reset clears the schedule but not the alarm already queued with AlarmManager.
        if (preferences.getInt("extra_allowance_hour", -1) < 0) return
        val store = FocusBankStore(preferences, context)
        if (store.grantExtraAllowance()) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel("allowance", "Allowance", NotificationManager.IMPORTANCE_DEFAULT))
            manager.notify(1002, NotificationCompat.Builder(context, "allowance")
                .withFocusBankBranding(context)
                .setContentTitle("Extra FocusBank time available")
                .setContentText("Your scheduled 30-minute allowance has been added.")
                .setAutoCancel(true)
                .build())
        }
        schedule(context)
    }

    companion object {
        fun schedule(context: Context) {
            val preferences = context.getSharedPreferences("focusbank", Context.MODE_PRIVATE)
            val hour = preferences.getInt("extra_allowance_hour", -1)
            val minute = preferences.getInt("extra_allowance_minute", -1)
            if (hour < 0 || minute < 0) return
            val calendar = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
            }
            val pending = PendingIntent.getBroadcast(
                context, 3001, Intent(context, ExtraAllowanceReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            context.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP, calendar.timeInMillis, pending
            )
        }
    }
}
