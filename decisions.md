# FocusBank Decision Log

Short architecture decision records. Newest last.

## ADR-001 — Ignore OEM gesture and helper packages in the accessibility filter
**Context:** On a Vivo device (Android 14), swipe gestures emit events from `com.vivo.upslide`, which the service treated as leaving the protected app, so it stopped the ticker.
**Decision:** `isIgnoredPackage()` ignores System UI, IMEs, autofill, permission controller, and the OEM prefixes (`com.vivo.`, `com.bbk.`, `com.samsung.`, `com.miui.`, `com.oppo.`, `com.oneplus.`, …), unless the user explicitly protected that package.

## ADR-002 — Back from the block overlay goes Home
**Context:** Pressing Back on `BlockOverlayActivity` showed the blocked app again.
**Decision:** An `OnBackPressedCallback` sends the user to `CATEGORY_HOME` and finishes the overlay.

## ADR-003 — 1-second watchdog alongside accessibility events
**Context:** Some builds stop sending window events while the user stays inside one app, so a stopped ticker never restarted.
**Decision:** A 1-second watchdog in `AppMonitorService` re-reads the foreground window, restarts tracking when needed, and counts down penalties. It is skipped while the keyguard is locked. NFR-2 now describes this watchdog.

## ADR-004 — Hand protected→protected switches to the full check (v1.2.0)
**Context:** When the ticker itself noticed a switch from one protected app to another, it called `startAllowanceTicker()`. That function returned early (`tickerRunning` was still true), so the runnable never rescheduled itself and the second app ran with no countdown and no penalty check.
**Decision:** The ticker clears `tickerRunning` and calls `handlePackageEvent()`, which checks the penalty and allowance and then restarts the ticker. The ticker also checks each second for a penalty that landed while the app was open.

## ADR-005 — Penalty recheck on countdown expiry, not on a wall-clock alarm (v1.2.0)
**Context:** Penalties pause while the Vault balance is zero (FR-3.5), but the recheck alarm fired `penaltyMinutes` after the penalty started. That could stack a new penalty while the first one was still running. The alarm was also lost on reboot.
**Decision:** `consumePenaltySecond()` reports the second that finishes a penalty. The service then calls `TaskDeadlineReceiver.onPenaltyExpired()`. Recheck intents still queued by 1.1.0 are ignored. Rerolls are only consumed when a penalty would actually apply.

## ADR-006 — Late completion earns no reward (v1.2.0)
**Context:** Spec §1.3 pays a reward only for "Task Completed in Time", but the code credited minutes for any completion.
**Decision:** `completeTask()` credits minutes only when `completedAt <= deadlineMillis`. A late completion still marks the task done, which stops further penalty rechecks.

## ADR-007 — Reset keeps today's grant markers (v1.2.0)
**Context:** `resetAll()` cleared `daily_allowance_day`, so the next launch granted another 30 minutes, which made reset a way to refill an empty Vault.
**Decision:** A reset keeps today's daily and extra grant markers, the theme, and the migration flag.

## ADR-008 — Process-wide usage cache (v1.2.0)
**Context:** `usageCache` belonged to each `FocusBankStore` instance. The service creates a new store every tick, so the cache was always empty and every second did a Room read plus a write.
**Decision:** The cache now lives in the store's companion object, so it survives across instances, and `resetAll()` clears it. The Room upsert still runs synchronously; moving it off the main thread is still open (SRS §9).
