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
**Decision:** The cache now lives in the store's companion object, so it survives across instances, and `resetAll()` clears it. The Room upsert still runs synchronously; ADR-009 moved it off the main thread.

## ADR-009 — No Room on the main thread (v1.3.0)
**Context:** `allowMainThreadQueries()` let the UI poll, the receivers, and the enforcement ticker all run database work on the main thread every second.
**Decision:** Removed `allowMainThreadQueries()`. The UI uses coroutines on `Dispatchers.IO`, receivers use `goAsync()` through `runAsync {}`, and `AppMonitorService` confines all enforcement state to one `HandlerThread` and keeps a single store. Because the service and UI can now run at the same time, balance, reroll and penalty read-modify-writes hold a shared lock. Legacy task migration runs lazily from `tasks()`, so constructing a store never touches Room.

## ADR-010 — Screen-free goal gates the daily unlock (v1.3.0)
**Context:** Under FR-3.8, completing one trivial task unlocked the whole day.
**Decision:** Add an optional daily screen-free goal (FR-5.6). The daily unlock also requires the goal to be met. Screen-off intervals come from the service's `SCREEN_OFF`/`SCREEN_ON` receiver and count only after the day's first screen-on, so a night's sleep does not satisfy the goal. The default is 0 (off), which keeps existing behaviour until the user opts in.
**Trade-off:** Time is only counted while the service is running, and an interval that crosses midnight is credited only to the part after the new day's first wake.
