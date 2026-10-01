package com.focusbank.app

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.InputMethodManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/**
 * Android shell around [EnforcementEngine]: forwards window events and screen broadcasts, runs the
 * 1-second tick, and implements [EnforcementHost] with accessibility and system APIs. All engine
 * calls happen on one background thread (Room is not allowed on the main thread).
 */
class AppMonitorService : AccessibilityService(), EnforcementHost {
    private val worker = HandlerThread("FocusBankEnforcement").apply { start() }
    private val handler = Handler(worker.looper)
    private val store by lazy { FocusBankStore(getSharedPreferences("focusbank", MODE_PRIVATE), this) }
    private val engine by lazy { EnforcementEngine(store, this) }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> engine.onScreenOff()
                Intent.ACTION_SCREEN_ON -> engine.onScreenOn()
                Intent.ACTION_USER_PRESENT -> engine.onUserPresent()
            }
        }
    }

    private val tickLoop = object : Runnable {
        override fun run() {
            storeHeartbeat()
            runCatching { engine.tick() }.onFailure { Log.e("FocusBank", "Enforcement tick failed", it) }
            handler.postDelayed(this, 1_000L)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        storeHeartbeat()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel("enforcement", "FocusBank protection", NotificationManager.IMPORTANCE_LOW)
        )
        val notification = createMonitoringNotification(EnforcementEngine.IDLE_STATUS)

        runCatching {
            // The specialUse type only exists from Android 14; older versions reject it, so they
            // start without a type (which they don't require).
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceCompat.startForeground(this, 1001, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(1001, notification)
            }
        }.onFailure { error ->
            Log.w("FocusBank", "startForeground failed (fallback to regular notify): ${error.message}")
            manager.notify(1001, notification)
        }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        // Delivered on the worker thread, alongside the tick.
        registerReceiver(screenReceiver, filter, null, handler)

        val interactive = getSystemService(PowerManager::class.java)?.isInteractive ?: true
        handler.post {
            engine.screenOn = interactive
            tickLoop.run()
        }
        Log.i("FocusBank", "Accessibility enforcement connected")
    }

    private fun createMonitoringNotification(contentText: String): android.app.Notification {
        return NotificationCompat.Builder(this, "enforcement")
            .withFocusBankBranding(this)
            .setContentTitle("FocusBank protection is active")
            .setContentText(contentText)
            .setOngoing(true)
            .build()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val packageName = event?.packageName?.toString() ?: return
        handler.post { engine.onWindowEvent(packageName) }
    }

    // --- EnforcementHost ---

    override val ownPackage: String get() = applicationContext.packageName

    override fun now(): Long = System.currentTimeMillis()

    override fun foregroundCandidates(): List<String> {
        val candidates = mutableListOf<String>()
        rootInActiveWindow?.packageName?.toString()?.let(candidates::add)
        runCatching {
            val appWindows = windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            (appWindows.filter { it.isFocused } + appWindows).forEach { window ->
                window.root?.packageName?.toString()?.let(candidates::add)
            }
        }
        return candidates.distinct()
    }

    override fun isKeyguardLocked(): Boolean =
        (getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager)?.isKeyguardLocked == true

    override fun isInputMethod(packageName: String): Boolean {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        return imm?.enabledInputMethodList?.any { it.packageName == packageName } == true
    }

    override fun isLauncherOrLaunchable(packageName: String): Boolean {
        val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val defaultLauncher = packageManager.resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
        if (packageName == defaultLauncher || "launcher" in packageName) return true
        return packageManager.getLaunchIntentForPackage(packageName) != null
    }

    override fun block(packageName: String, isPenalty: Boolean, penaltySeconds: Long) {
        startActivity(Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
        startActivity(Intent(this, BlockOverlayActivity::class.java).apply {
            putExtra("package_name", packageName)
            putExtra("is_penalty", isPenalty)
            putExtra("penalty_seconds", penaltySeconds)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        })
    }

    override fun showStatus(text: String) {
        getSystemService(NotificationManager::class.java).notify(1001, createMonitoringNotification(text))
    }

    override fun appLabel(packageName: String): String = appLabel(this, packageName)

    override fun onPenaltyExpired(packageName: String) {
        TaskDeadlineReceiver.onPenaltyExpired(this, store, packageName)
    }

    private fun storeHeartbeat() {
        getSharedPreferences("focusbank", MODE_PRIVATE).edit().putLong("service_heartbeat", System.currentTimeMillis()).apply()
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        runCatching { unregisterReceiver(screenReceiver) }
        handler.removeCallbacksAndMessages(null)
        worker.quitSafely()
        super.onDestroy()
    }
}
