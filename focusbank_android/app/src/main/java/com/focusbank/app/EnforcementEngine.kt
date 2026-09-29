package com.focusbank.app

/**
 * What the enforcement engine needs from the device. [AppMonitorService] implements it with
 * accessibility and system APIs; tests implement it with a fake phone.
 */
interface EnforcementHost {
    val ownPackage: String
    fun now(): Long

    /** Package names that may be in front, best guess first (active root, focused app window, …). */
    fun foregroundCandidates(): List<String>
    fun isKeyguardLocked(): Boolean
    fun isInputMethod(packageName: String): Boolean

    /** The home launcher or any installed app with a launch intent. */
    fun isLauncherOrLaunchable(packageName: String): Boolean

    /** Send the user home and show the lockout overlay. */
    fun block(packageName: String, isPenalty: Boolean, penaltySeconds: Long)
    fun showStatus(text: String)
    fun appLabel(packageName: String): String
    fun onPenaltyExpired(packageName: String)
}

/**
 * FocusBank's blocking rules (FR-2.2, FR-2.3, FR-3.1, FR-3.5, FR-3.8). Not thread-safe: the
 * service calls every method from its single enforcement thread.
 *
 * One [tick] per second drives everything. It re-reads the foreground app, so dropped
 * accessibility events and protected→protected switches are caught within a second, then
 * charges one second to the app being tracked.
 */
class EnforcementEngine(private val store: FocusBankStore, private val host: EnforcementHost) {
    /** The protected app currently being charged, or null. */
    var activePackage: String? = null
        private set

    /** Set from PowerManager at start-up, then kept current by the screen broadcasts. */
    var screenOn: Boolean = true

    private var lastBlockAt = Long.MIN_VALUE / 2
    private var shownMinutes = -1

    fun onWindowEvent(packageName: String) = handle(packageName)

    fun onScreenOff() {
        store.onScreenOff(host.now())
        screenOn = false
    }

    fun onScreenOn() {
        store.onScreenOn(host.now())
        screenOn = true
        checkForeground()
    }

    fun onUserPresent() = checkForeground()

    fun tick() {
        store.ensureDailyAllowance()
        if (!screenOn || host.isKeyguardLocked()) return
        checkForeground()
        val packageName = activePackage ?: return
        chargeSecond(packageName)
    }

    /** Keyboards, System UI and OEM helpers take focus without the user leaving the app. */
    fun isIgnored(packageName: String): Boolean {
        if (packageName.isBlank() || packageName == "android") return true
        if (packageName == "com.android.systemui" || packageName.startsWith("com.android.systemui.")) return true
        if (packageName == "com.google.android.gms" || packageName == "com.google.android.gsf") return true
        if ("permissioncontroller" in packageName || "autofill" in packageName) return true
        if ("inputmethod" in packageName || "keyboard" in packageName || "ime" in packageName) return true
        // An OEM app the user explicitly protected (e.g. com.vivo.browser) must still be enforced.
        if (packageName !in store.blockedApps() && OEM_PREFIXES.any(packageName::startsWith)) return true
        return host.isInputMethod(packageName)
    }

    /** The first foreground candidate that is not an ignored overlay, if any. */
    fun foregroundPackage(): String? = host.foregroundCandidates().firstOrNull { it == host.ownPackage || !isIgnored(it) }

    private fun checkForeground() {
        val foreground = foregroundPackage() ?: return
        if (foreground != activePackage) handle(foreground)
    }

    private fun handle(packageName: String) {
        if (packageName == host.ownPackage) {
            // FocusBank or its overlay is in front: stop charging whatever was open before.
            if (activePackage != null) exitTracking()
            return
        }
        if (isIgnored(packageName)) return

        if (packageName !in store.blockedApps()) {
            if (activePackage != null && host.isLauncherOrLaunchable(packageName)) exitTracking()
            return
        }

        if (store.penaltyRemainingSeconds(packageName) > 0L) {
            block(packageName, isPenalty = true)
            return
        }
        if (!hasAllowance(packageName) && !store.dailyUsageUnlocked()) {
            block(packageName, isPenalty = false)
            return
        }
        if (activePackage != packageName) {
            activePackage = packageName
            shownMinutes = -1
        }
    }

    private fun hasAllowance(packageName: String): Boolean =
        if (store.appAllowance(packageName) != null) store.appAllowanceSeconds(packageName) > 0L
        else store.balanceSeconds() > 0L

    private fun chargeSecond(packageName: String) {
        if (packageName !in store.blockedApps()) {
            exitTracking()
            return
        }
        // A deadline can pass while the app is open (FR-3.1 Case C).
        if (store.penaltyRemainingSeconds(packageName) > 0L) {
            block(packageName, isPenalty = true)
            return
        }

        val remaining = if (store.appAllowance(packageName) != null) {
            // The app's own daily bucket: does not draw on the Vault, so penalties stay put.
            store.consumeAppSecond(packageName)
            store.appAllowanceSeconds(packageName)
        } else {
            if (store.chargeVaultSecond(packageName)) {
                store.tickPenaltiesWhileSpending(packageName).forEach(host::onPenaltyExpired)
            }
            store.balanceSeconds()
        }

        showRemaining(packageName, remaining)
        if (remaining <= 0L && !store.dailyUsageUnlocked()) block(packageName, isPenalty = false)
    }

    private fun block(packageName: String, isPenalty: Boolean) {
        activePackage = null
        val now = host.now()
        // The overlay needs a moment to come up; don't stack launches while it does.
        if (now - lastBlockAt < BLOCK_DEBOUNCE_MS) return
        lastBlockAt = now
        host.block(packageName, isPenalty, store.penaltyRemainingSeconds(packageName))
    }

    private fun exitTracking() {
        activePackage = null
        shownMinutes = -1
        host.showStatus(IDLE_STATUS)
    }

    private fun showRemaining(packageName: String, remaining: Long) {
        val minutes = (remaining / 60L).toInt()
        if (minutes == shownMinutes) return
        shownMinutes = minutes
        host.showStatus(statusText(packageName, remaining))
    }

    private fun statusText(packageName: String, remaining: Long): String {
        val label = host.appLabel(packageName)
        return when {
            remaining >= 60L -> "${remaining / 60L} min remaining while using $label"
            remaining > 0L -> "$remaining sec remaining while using $label"
            store.dailyUsageUnlocked() -> "Daily goal reached! Free time active on $label"
            else -> "Allowance exhausted for $label"
        }
    }

    companion object {
        const val IDLE_STATUS = "Selected apps are being monitored."
        const val BLOCK_DEBOUNCE_MS = 1_200L
        private val OEM_PREFIXES = listOf(
            "com.vivo.", "com.bbk.", "com.iqoo.", "com.samsung.", "com.sec.android.",
            "com.miui.", "com.xiaomi.", "com.oppo.", "com.coloros.", "com.oneplus."
        )
    }
}
