package com.focusbank.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class FocusBankBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED && intent?.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val store = FocusBankStore(context.getSharedPreferences("focusbank", Context.MODE_PRIVATE), context)
        TaskDeadlineReceiver.rescheduleAll(context, store.tasks())
        ExtraAllowanceReceiver.schedule(context)
    }
}
