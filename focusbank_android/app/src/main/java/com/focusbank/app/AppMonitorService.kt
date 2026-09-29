package com.focusbank.app

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.os.Build
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.InputMethodManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.KeyguardManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import android.util.Log

class AppMonitorService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private val penaltyHandler = Handler(Looper.getMainLooper())
    private var activePackage: String? = null
    private var tickerRunning = false
    private var notificationBalance = -1
    private var lastBlockTimestamp = 0L

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    Log.d("FocusBank", "Screen turned off; pausing allowance ticker")
                    stopAllowanceTicker()
                }
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> {
                    Log.d("FocusBank", "Screen turned on/unlocked; checking foreground app")
                    checkForegroundPackage()
                }
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        storeHeartbeat()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel("enforcement", "FocusBank protection", NotificationManager.IMPORTANCE_LOW)
        )
        val notification = createMonitoringNotification("Selected apps are being monitored.")

        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceCompat.startForeground(
                    this,
                    1001,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(1001, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
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
        registerReceiver(screenReceiver, filter)

        startPenaltyTicker()
        Log.i("FocusBank", "Accessibility enforcement connected")
    }

    private fun createMonitoringNotification(contentText: String): android.app.Notification {
        return NotificationCompat.Builder(this, "enforcement")
            .setSmallIcon(R.drawable.ic_stat_focusbank)
            .setContentTitle("FocusBank protection is active")
            .setContentText(contentText)
            .setOngoing(true)
            .build()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val packageName = event?.packageName?.toString() ?: return
        handlePackageEvent(packageName)
    }

    private fun handlePackageEvent(packageName: String) {
        if (packageName == applicationContext.packageName) {
            // FocusBank or BlockOverlayActivity itself is open
            return
        }

        if (isIgnoredPackage(packageName)) {
            // Virtual keyboards, system overlays, or OEM helper services: do not cancel activePackage!
            return
        }

        val store = FocusBankStore(getSharedPreferences("focusbank", MODE_PRIVATE), this)
        val blockedApps = store.blockedApps()

        if (packageName !in blockedApps) {
            // Only cancel activePackage if the user switched to a genuine launcher or other user app
            if (isLauncherOrUserApp(packageName)) {
                if (activePackage != null) {
                    Log.d("FocusBank", "Exited monitored app to $packageName")
                    activePackage = null
                    stopAllowanceTicker()
                    resetMonitoringNotification()
                }
            }
            return
        }

        // It is a monitored/blocked app
        activePackage = packageName

        if (store.penaltyRemainingSeconds(packageName) > 0L) {
            stopAllowanceTicker()
            blockCurrentApp(packageName, isPenalty = true)
            return
        }

        val specialAllowance = store.appAllowance(packageName)
        val hasAllowance = if (specialAllowance != null) {
            store.appAllowanceSeconds(packageName) > 0L
        } else {
            store.balanceSeconds() > 0L
        }

        if (!hasAllowance && !store.dailyUsageUnlocked()) {
            stopAllowanceTicker()
            blockCurrentApp(packageName, isPenalty = false)
        } else {
            startAllowanceTicker(packageName)
        }
    }

    private fun checkForegroundPackage() {
        val rootPkg = getActiveForegroundPackage()
        if (rootPkg != null && !isIgnoredPackage(rootPkg)) {
            handlePackageEvent(rootPkg)
        }
    }

    private fun isIgnoredPackage(pkg: String): Boolean {
        if (pkg.isBlank()) return true
        if (pkg == applicationContext.packageName) return true
        if (pkg == "com.android.systemui" || pkg.startsWith("com.android.systemui.")) return true
        if (pkg == "android") return true
        if (pkg == "com.google.android.gms" || pkg == "com.google.android.gsf") return true
        if (pkg.contains("permissioncontroller")) return true
        if (pkg.contains("autofill")) return true
        if (pkg.contains("inputmethod") || pkg.contains("keyboard") || pkg.contains("ime")) return true

        val store = FocusBankStore(getSharedPreferences("focusbank", MODE_PRIVATE), this)
        // If the user explicitly added an OEM app (like com.vivo.browser) to blocked apps, do not ignore it
        if (pkg !in store.blockedApps()) {
            if (pkg.startsWith("com.vivo.") || pkg.startsWith("com.bbk.") || pkg.startsWith("com.iqoo.")) return true
            if (pkg.startsWith("com.samsung.") || pkg.startsWith("com.sec.android.")) return true
            if (pkg.startsWith("com.miui.") || pkg.startsWith("com.xiaomi.")) return true
            if (pkg.startsWith("com.oppo.") || pkg.startsWith("com.coloros.")) return true
            if (pkg.startsWith("com.oneplus.")) return true
        }

        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        return imm?.enabledInputMethodList?.any { it.packageName == pkg } == true
    }

    private fun isLauncherOrUserApp(pkg: String): Boolean {
        if (pkg == applicationContext.packageName) return false
        if (isIgnoredPackage(pkg)) return false

        // Check if it's the home launcher
        val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val defaultLauncher = packageManager.resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
        if (pkg == defaultLauncher || pkg.contains("launcher")) return true

        // Check if it's an installed app with a launch intent
        return packageManager.getLaunchIntentForPackage(pkg) != null
    }

    private fun getActiveForegroundPackage(): String? {
        val rootPkg = rootInActiveWindow?.packageName?.toString()
        if (rootPkg != null && !isIgnoredPackage(rootPkg)) {
            return rootPkg
        }

        runCatching {
            val appWindow = windows.firstOrNull { 
                it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isFocused 
            } ?: windows.firstOrNull { 
                it.type == AccessibilityWindowInfo.TYPE_APPLICATION 
            }
            val winPkg = appWindow?.root?.packageName?.toString()
            if (winPkg != null && !isIgnoredPackage(winPkg)) {
                return winPkg
            }
        }

        return rootPkg
    }

    private fun startPenaltyTicker() {
        penaltyHandler.post(object : Runnable {
            override fun run() {
                val store = FocusBankStore(getSharedPreferences("focusbank", MODE_PRIVATE), this@AppMonitorService)
                storeHeartbeat()
                store.penaltyConfiguredApps().forEach { packageName ->
                    if (store.consumePenaltySecond(packageName)) {
                        TaskDeadlineReceiver.onPenaltyExpired(this@AppMonitorService, store, packageName)
                    }
                }
                
                // Continuous 1-second Watchdog: ensures active blocked app is always tracked and counted
                checkForegroundWatchdog(store)

                penaltyHandler.postDelayed(this, 1_000L)
            }
        })
    }

    private fun checkForegroundWatchdog(store: FocusBankStore) {
        val km = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        if (km?.isKeyguardLocked == true) return

        val fgPkg = getActiveForegroundPackage() ?: return
        if (fgPkg == applicationContext.packageName) return
        if (isIgnoredPackage(fgPkg)) return

        val blockedApps = store.blockedApps()
        if (fgPkg in blockedApps) {
            // The user is actively in a blocked app!
            if (activePackage != fgPkg || !tickerRunning) {
                Log.d("FocusBank", "Watchdog recovered foreground blocked app: $fgPkg")
                handlePackageEvent(fgPkg)
            }
        } else if (isLauncherOrUserApp(fgPkg)) {
            if (activePackage != null) {
                Log.d("FocusBank", "Watchdog detected exit to $fgPkg")
                activePackage = null
                stopAllowanceTicker()
                resetMonitoringNotification()
            }
        }
    }

    private val allowanceTickerRunnable = object : Runnable {
        override fun run() {
            val currentPkg = activePackage
            if (!tickerRunning || currentPkg == null) {
                tickerRunning = false
                return
            }

            val km = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
            if (km?.isKeyguardLocked == true) {
                if (tickerRunning && activePackage == currentPkg) {
                    handler.postDelayed(this, 1_000L)
                }
                return
            }

            try {
                // Secondary check: verify if active window changed without an event
                val winPkg = getActiveForegroundPackage()
                if (winPkg != null && !isIgnoredPackage(winPkg) && winPkg != currentPkg) {
                    val store = FocusBankStore(getSharedPreferences("focusbank", MODE_PRIVATE), this@AppMonitorService)
                    if (winPkg in store.blockedApps()) {
                        // Switched straight from one protected app to another. Hand over to the full
                        // check (penalty + allowance). Calling startAllowanceTicker() here used to hit
                        // its early return and leave this runnable unscheduled, so the new app ran free.
                        tickerRunning = false
                        handlePackageEvent(winPkg)
                        return
                    } else if (isLauncherOrUserApp(winPkg)) {
                        activePackage = null
                        stopAllowanceTicker()
                        resetMonitoringNotification()
                        return
                    }
                }

                val store = FocusBankStore(getSharedPreferences("focusbank", MODE_PRIVATE), this@AppMonitorService)
                if (currentPkg !in store.blockedApps()) {
                    activePackage = null
                    stopAllowanceTicker()
                    resetMonitoringNotification()
                    return
                }

                storeHeartbeat()

                // A deadline can pass while the app is open (FR-3.1 Case C): lock immediately.
                if (store.penaltyRemainingSeconds(currentPkg) > 0L) {
                    stopAllowanceTicker()
                    blockCurrentApp(currentPkg, isPenalty = true)
                    return
                }

                val specialAllowance = store.appAllowance(currentPkg)
                val remainingSeconds: Long
                if (specialAllowance != null) {
                    store.consumeAppSecond(currentPkg)
                    remainingSeconds = store.appAllowanceSeconds(currentPkg)
                } else {
                    store.consumeSecond(currentPkg)
                    remainingSeconds = store.balanceSeconds()
                }

                updateNotification(store, currentPkg, remainingSeconds)

                if (remainingSeconds <= 0L) {
                    if (!store.dailyUsageUnlocked()) {
                        stopAllowanceTicker()
                        blockCurrentApp(currentPkg, isPenalty = false)
                        return
                    }
                }

                if (tickerRunning && activePackage == currentPkg) {
                    handler.postDelayed(this, 1_000L)
                }
            } catch (e: Exception) {
                Log.e("FocusBank", "Error in allowance ticker", e)
                if (tickerRunning && activePackage == currentPkg) {
                    handler.postDelayed(this, 1_000L)
                }
            }
        }
    }

    private fun startAllowanceTicker(packageName: String) {
        if (tickerRunning && activePackage == packageName) {
            return
        }
        activePackage = packageName
        tickerRunning = true
        handler.removeCallbacksAndMessages(null)
        handler.post(allowanceTickerRunnable)
    }

    private fun stopAllowanceTicker() {
        tickerRunning = false
        handler.removeCallbacksAndMessages(null)
    }

    private fun updateNotification(store: FocusBankStore, packageName: String, remainingSeconds: Long) {
        val minutes = (remainingSeconds / 60L).toInt()
        if (minutes == notificationBalance) return
        notificationBalance = minutes

        val appLabel = appLabel(this, packageName)

        val contentText = if (remainingSeconds > 0L) {
            val mins = remainingSeconds / 60L
            val secs = remainingSeconds % 60L
            if (mins > 0) "$mins min remaining while using $appLabel" else "$secs sec remaining while using $appLabel"
        } else if (store.dailyUsageUnlocked()) {
            "Daily goal reached! Free time active on $appLabel"
        } else {
            "Allowance exhausted for $appLabel"
        }

        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(1001, createMonitoringNotification(contentText))
    }

    private fun resetMonitoringNotification() {
        notificationBalance = -1
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(1001, createMonitoringNotification("Selected apps are being monitored."))
    }

    private fun storeHeartbeat() {
        getSharedPreferences("focusbank", MODE_PRIVATE).edit().putLong("service_heartbeat", System.currentTimeMillis()).apply()
    }

    private fun blockCurrentApp(packageName: String? = activePackage, isPenalty: Boolean = false) {
        val target = packageName ?: return
        val now = System.currentTimeMillis()
        if (now - lastBlockTimestamp < 1_200L) return
        lastBlockTimestamp = now

        stopAllowanceTicker()

        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(homeIntent)

        val overlayIntent = Intent(this, BlockOverlayActivity::class.java).apply {
            putExtra("package_name", target)
            putExtra("is_penalty", isPenalty)
            putExtra(
                "penalty_seconds",
                FocusBankStore(getSharedPreferences("focusbank", MODE_PRIVATE), this@AppMonitorService).penaltyRemainingSeconds(target)
            )
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivity(overlayIntent)
    }

    override fun onInterrupt() {
        stopAllowanceTicker()
    }

    override fun onDestroy() {
        stopAllowanceTicker()
        penaltyHandler.removeCallbacksAndMessages(null)
        runCatching { unregisterReceiver(screenReceiver) }
        super.onDestroy()
    }
}
