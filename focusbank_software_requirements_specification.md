# Software Requirements Specification (SRS)
## FocusBank: Productivity & App Allowance Vault

---

### Document Control
- **Document Version:** 1.5.0
- **Author:** System Architecture & Product Engineering
- **Status:** Release Candidate / Implementation Baseline
- **Target OS:** Android (Minimum SDK: API 26 / Android 8.0 Oreo; Target/Compile SDK: API 36 / Android 16)

### Document Update Summary

Version 1.5.0 prepares for Google Play. It targets and compiles against API 36 (AGP 8.9.1, Gradle 8.11.1) with edge-to-edge layouts, and it shows an in-app Accessibility disclosure with a consent step before opening Accessibility settings. `QUERY_ALL_PACKAGES` is replaced by `<queries>` entries for launcher and home intents. The verse card and all verse text are removed. Android 10–13 now start the enforcement notification as a plain foreground service, because the special-use type only exists from Android 14.

Version 1.4.0 confirms the penalty rule (FR-3.5): a penalty counts down in real time whenever the Vault holds more than 0 seconds and pauses while it is empty. Serving it costs no Vault time, and the penalized app stays locked throughout. The blocking rules moved out of `AppMonitorService` into a plain `EnforcementEngine` driven by one 1-second tick, and opening FocusBank itself now stops charging the app that was open before. The store, engine and penalty recheck are covered by Robolectric tests, for 69 tests in all.

Version 1.3.0 adds an optional daily screen-free goal (FR-5.6) that gates the FR-3.8 daily unlock. It also removes the unused `SYSTEM_ALERT_WINDOW` and `PACKAGE_USAGE_STATS` permissions, moves all database work off the main thread (Room no longer allows main-thread queries, and enforcement runs on its own thread), and adds JVM unit tests for the core rules in `TaskRules`.

Version 1.2.0 comes from a spec-versus-code audit. Enforcement fixes: a switch between two protected apps no longer stops the allowance ticker; a penalty that starts while its app is open now locks it at once; penalty rechecks now happen when the paused countdown actually runs out (not on a wall-clock alarm), so they also survive a reboot; rerolls are only used up when a penalty would really apply. Rule changes: a late task completion earns no minutes (§1.3), a reset no longer grants a fresh daily allowance, and backup imports validate priority and value ranges. New in the UI: FR-1.2 sorting, priority-based default rewards, the 120-character title limit, reminder date and time with validation, a real JSON file export, a scrollable Vault, app names instead of package names, the overlay task spotlight, and a new launcher and notification icon. Section 9 lists what is still open.

Version 1.1.0 records the implemented Android release candidate. The current build includes Room persistence, second-level allowance tracking with an `HH:MM:SS` Vault display, a daily 30-minute allowance, configurable extra 30-minute allowance scheduling, task deadlines and reminders, random protected-app penalties, paused penalty countdowns, penalty rerolls, category-based app selection, a weekly schedule matrix, theme switching, backup and restore, reboot alarm recovery, and foreground accessibility enforcement.

The release candidate builds successfully as a debug APK and unsigned release APK/App Bundle. Release signing, Play Console policy approval, and device-manufacturer compatibility testing remain release gates.

---

## 1. Executive Summary & Brand Identity

### 1.1 Application Title
**FocusBank** *(Working Title: The Incentive-Driven App Allowance Vault)*

### 1.2 Core Value Proposition
Traditional digital wellbeing solutions rely on passive tracking or static daily limits that users easily dismiss or override. **FocusBank** transforms screen time from an unearned baseline into an earned currency. Distraction applications (social media, video streaming, games) are locked by default and unlocked strictly through active task execution, hard deadline adherence, and positive behavioral reinforcement.

### 1.3 Behavioral Loop Engine
```
                  ┌───────────────────────────────┐
                  │    Task Creation & Priority   │
                  │   (Reward / Penalty Staked)   │
                  └───────────────┬───────────────┘
                                  │
                                  ▼
        ┌──────────────────────────────────────────────────┐
        │                 Execution Window                 │
        └─────────┬──────────────────────────────┬─────────┘
                  │                              │
     [Task Completed in Time]          [Deadline Breached]
                  │                              │
                  ▼                              ▼
        ┌──────────────────┐           ┌──────────────────┐
        │  Positive Yield  │           │ Negative Penalty │
        │ (+ Earned Mins)  │           │ (Strict Lockout) │
        └─────────┬────────┘           └─────────┬────────┘
                  │                              │
                  ▼                              ▼
        ┌──────────────────┐           ┌──────────────────┐
        │ Unlocked Vault   │           │ Full Overdrive   │
        │ (Active Ticker)  │           │   Shield Active  │
        └──────────────────┘           └──────────────────┘
```

---

## 2. Technical Architecture & System Design

### 2.1 Architectural Pattern
The current release candidate uses a Compose-based Android architecture with a centralized local store:
- **Presentation Layer:** Jetpack Compose and Material 3 screens hosted by `MainActivity`.
- **Application/data layer:** Room stores tasks and daily per-app usage; SharedPreferences stores allowance balances, protected apps, categories, theme choice, penalties, and rerolls.
- **System bridges:** `AccessibilityService` monitors foreground package changes; `AlarmManager` delivers reminders and deadlines; a foreground notification keeps enforcement visible.
- **Offline operation:** No server or network connection is required for core behavior.

MVVM, Hilt, DataStore, and dedicated domain use-case classes remain future refactoring targets rather than current implementation requirements.

### 2.2 System Architecture Diagram
```
┌────────────────────────────────────────────────────────────────────────┐
│                          PRESENTATION LAYER                            │
│  MainActivity (Compose)  │  BlockOverlayActivity  │  AppSelectionSheet  │
└───────────────────▲───────────────────────────────────▲────────────────┘
                    │                                   │
┌───────────────────▼───────────────────────────────────▼────────────────┐
│                STATE LAYER  (ViewModel = planned, §2.1)                │
│       Compose state       │    FocusBankStore   │   Enforcement state  │
└───────────────────▲───────────────────────────────────▲────────────────┘
                    │                                   │
┌───────────────────▼───────────────────────────────────▼────────────────┐
│          DOMAIN LOGIC  (inside FocusBankStore; use cases planned)      │
│  completeTask · applyPenalty · consumeSecond · onPenaltyExpired        │
└───────────────────▲───────────────────────────────────▲────────────────┘
                    │                                   │
┌───────────────────▼───────────────────────────────────▼────────────────┐
│                             DATA LAYER                                 │
│  Room DB (Tasks, Usage) │ SharedPreferences      │ PackageManager       │
└───────────────────▲───────────────────────────────────▲────────────────┘
                    │                                   │
┌───────────────────▼───────────────────────────────────▼────────────────┐
│                   SYSTEM ENFORCEMENT & BACKGROUND                     │
│  AccessibilityService  │  AlarmManager         │  Foreground notice   │
└────────────────────────────────────────────────────────────────────────┘
```

---

## 3. System Permissions & OS Interoperability

FocusBank requires high-privilege Android permissions to enforce distraction blocking reliably across modern Android power-saving and sandboxing profiles.

| Permission Identifier | Usage Reason | Fallback / Graceful Failure |
| :--- | :--- | :--- |
| `BIND_ACCESSIBILITY_SERVICE` | Real-time interceptor when target package comes to foreground (`TYPE_WINDOW_STATE_CHANGED`). | Hard blocker: core app features cannot run without this active. |
| `POST_NOTIFICATIONS` | Ongoing low-priority notification showing active countdown when distraction apps are in use. | Required on Android 13+ (API 33+) to guarantee foreground service stability. |
| `FOREGROUND_SERVICE_SPECIAL_USE` | Keeps enforcement status visible while the Accessibility Service is connected. | Requires prominent disclosure and Play Console policy review. |
| `RECEIVE_BOOT_COMPLETED` | Restores scheduled task deadline and reminder alarms after reboot or app update. | Tasks remain visible if alarms cannot be restored. |

*Package visibility (1.5.0):* instead of `QUERY_ALL_PACKAGES`, the manifest declares `<queries>` for `MAIN`/`LAUNCHER` (the app selector) and `MAIN`/`HOME` (detecting the launcher). No permission declaration is needed.

*Accessibility disclosure (1.5.0):* before opening Accessibility settings, FocusBank shows an in-app dialog. It explains what the service observes (the foreground app's package name and screen on/off), why (allowance, penalties, screen-free time), that nothing leaves the device, and how to turn it off. The user must tap "Agree and continue", and the time of consent is stored.

*Removed in 1.3.0:* `SYSTEM_ALERT_WINDOW` (blocking uses `BlockOverlayActivity` launched with `FLAG_ACTIVITY_NEW_TASK`) and `PACKAGE_USAGE_STATS` (foreground detection uses only the Accessibility Service). Neither was ever requested or used.

---

## 4. Functional Requirements

### 4.1 Module 1: Task Management & Priority Matrix
- **FR-1.1:** System shall support task creation with fields:
  - `Title` (String, required, max 120 chars)
  - `Description` (String, optional) — *not implemented; absent from the §6 schema.*
  - `Priority Level` (`HIGH`, `MEDIUM`, `LOW`)
  - `Deadline` (Unix Epoch timestamp)
  - `Reward Allowance` (Calculated or custom minutes: High = 30m, Medium = 20m, Low = 10m; choosing a priority fills in its default, capped at 1,440)
  - `Penalty Stake` (Lockout duration if deadline missed: e.g., 60m strict blackout)
- **FR-1.1a:** Users shall select the deadline date from a calendar and deadline time from a clock picker.
- **FR-1.1b:** Users shall select a custom reminder date and time independently from the deadline. The reminder defaults to one hour before the deadline and must be earlier than it; the deadline must be in the future.
- **FR-1.1c:** Users shall edit title, priority, reward, deadline date, and deadline time after creation.
- **FR-1.2:** System shall allow inline sorting by Eisenhower Matrix, Priority, and Due Date. For the Eisenhower sort, importance means `HIGH` or `MEDIUM` priority and urgency means a deadline within 24 hours. Quadrants are ordered Do, Schedule, Delegate, Drop, with the deadline breaking ties.
- **FR-1.3:** On task completion, the system marks the task `COMPLETED` and saves the timestamp. The reward minutes are credited only when the task is completed on or before its deadline. A late completion earns nothing, but it still stops further penalty rechecks for that task (FR-3.10).
- **FR-1.4:** Tasks completed at least 24 hours before their deadline shall grant one penalty reroll.

### 4.2 Module 2: The Allowance Vault ("Bank")
- **FR-2.1:** The Vault maintains `active_balance_seconds` in local application storage; encryption and biometric protection are release follow-up requirements.
- **FR-2.2:** Real-time burn-down loop:
  - When user is inside an allowed restricted app, allowance decrements per second ($1 \text{ s} = 1 \text{ s}$).
  - Ticker stops immediately when user returns to home screen or an unlisted application.
- **FR-2.3:** Overdraft Protection: When `active_balance_seconds <= 0`, blocking kicks in within $\le 250\text{ ms}$.
- **FR-2.4:** The system shall grant 30 minutes (`1,800` seconds) of general allowance once per calendar day when the daily period begins or FocusBank next initializes.
- **FR-2.5:** The user shall be able to schedule one additional 30-minute general allowance at a selected time of day.
- **FR-2.6:** The scheduled extra allowance shall be granted at most once per calendar day and restored after reboot or app update.

### 4.3 Module 3: App Interceptor & Enforcement Engine
- **FR-3.1:** The `AppMonitorService` (Accessibility Service) inspects package changes:
  - If package matches `blocked_package_list`:
    - **Case A: `balance > 0` and `penalty_lockout == false`**: Allow access, launch active foreground timer notification.
    - **Case B: `balance == 0`**: Intercept. Send `Intent.ACTION_MAIN` (`CATEGORY_HOME`) and launch `BlockOverlayActivity`.
    - **Case C: `penalty_lockout == true`**: Hard Lock. Prevent access completely until penalty duration expires; no task completion can override during penalty.
- **FR-3.2:** Anti-Circumvention Protection — **not implemented (planned):**
  - Detect attempts to open Android System Settings to force-stop FocusBank or disable Accessibility.
  - Optional "Strict Lockdown Mode" prompts for confirmation password or prevents uninstalling during an active penalty.
- **FR-3.3:** A missed task deadline shall randomly select one package from the current protected-app set only. It shall never select an unselected installed app.
- **FR-3.4:** A penalty shall lock the selected protected app until its stored remaining penalty seconds reach zero (see FR-3.5; no wall-clock expiry timestamp is stored). A penalty that starts while its app is open locks it on the next tick.
- **FR-3.5:** A penalty shall be stored as remaining seconds. It shall count down in real time, one second per second, whenever the general Vault holds more than 0 seconds, whether or not the phone is in use. It shall pause while the Vault is empty and resume as soon as time is added. Serving a penalty does not take time from the Vault, and the penalized app stays locked until it is served. Example: with 60 minutes in the Vault and a 20-minute penalty on Instagram, Instagram unlocks 20 minutes later and the Vault still holds 60 minutes, minus anything spent in other apps.
- **FR-3.6:** When a pending penalty is discovered at app launch, the UI shall animate a dice roll and end by showing the selected protected app and remaining penalty countdown.
- **FR-3.7:** The Vault shall show the selected app and a live penalty countdown, shown as paused while the Vault is empty.
- **FR-3.8:** When all stored tasks are complete, at least one was completed today, and the screen-free goal (FR-5.6) is met or not set, allowance enforcement shall be bypassed for the rest of that day. Penalties still apply.
- **FR-3.9:** The dice reveal shall be shown once per unique penalty instance and shall not repeat on every app launch while that same penalty remains active.
- **FR-3.10:** When a penalty's countdown reaches zero, the enforcement service shall recheck the associated task. Only if the task is still incomplete may another protected-app penalty be created or a reroll consumed. A reroll is never consumed when no penalty would apply (no protected apps, or zero penalty minutes).
- **FR-3.11:** The lockout overlay shall identify the restricted package, show a live `HH:MM:SS` countdown, explain the lockout state, and provide a route back to FocusBank.

### 4.4 Module 4: Dynamic Distraction App Selector
- **FR-4.1:** App queries device `PackageManager` using `getInstalledApplications()`.
- **FR-4.2:** The selector shall exclude FocusBank and only show launchable installed applications.
- **FR-4.3:** Apps shall be grouped into Social, Streaming, Games, and Other subtabs.
- **FR-4.4:** Each category shall provide a searchable multi-select `Add apps` flow with app icons.
- **FR-4.5:** Users shall be able to assign an app manually to a category; manual assignment overrides automatic keyword categorization.
- **FR-4.6:** Users may give a protected app its own daily minute allowance, which refills each calendar day. While an app has its own allowance, using it draws from that allowance instead of the general Vault balance.

### 4.5 Module 5: Schedule and Dashboard
- **FR-5.1:** *Removed in 1.5.0.* The verse card was dropped because the translations it quoted are copyrighted.
- **FR-5.2:** The Schedule screen shall show seven days as rows and eight three-hour windows as columns.
- **FR-5.3:** Tasks shall appear in the matrix cell matching their deadline window, including exact due time.
- **FR-5.4:** Schedule task text shall be color-coded by priority: high red, medium yellow, low green.
- **FR-5.6 (Screen-free goal):** Users may set a daily goal of 0 to 960 minutes with the screen off; 0 turns the goal off. Screen-off time counts only after the day's first screen-on, so overnight sleep is excluded, and only while the enforcement service is running. The Vault shall show today's progress. The goal is included in backups.
- **FR-5.5:** The Vault shall show current balance, minutes earned today (on-time completions only), protected-app minutes spent today, and per-app usage summaries labelled with app names. The Vault screen shall scroll so every section is reachable.

### 4.6 Module 6: Backup and Recovery
- **FR-6.1:** Users shall export tasks, deadlines, reminders, balances, rerolls, protected apps, per-app allowances, the extra-allowance schedule, and categories as a JSON file saved through the system document picker.
- **FR-6.2:** Imports shall be size-limited and shall validate task count, title length, priority values, and reward/penalty ranges before replacing local tasks.
- **FR-6.3:** Users shall receive a confirmation before resetting local data. A reset keeps today's daily and extra allowance grant markers, so it cannot be used to claim another 30 minutes the same day.
- **FR-6.4:** Deadline alarms shall be recreated after device reboot or app update.
- **FR-6.5:** Daily allowance and scheduled extra allowance state shall be preserved across process restarts and restored after reboot.

---

## 5. Non-Functional Requirements

- **NFR-1 (Latency):** The detection-to-block interception latency must not exceed $250\text{ ms}$ upon foreground package state change.
- **NFR-2 (Battery Consumption):** Background service CPU footprint must not exceed $2\%$ of daily device battery consumption. Enforcement is driven by `AccessibilityEvent` callbacks plus one 1-second tick in `EnforcementEngine`. The tick re-reads the foreground app (recovering events that some OEM builds drop), charges the tracked app, and counts down penalties, and it does nothing while the screen is off or locked. No other background polling is allowed. The service keeps one long-lived `FocusBankStore` and does its work on a background thread. *Open:* battery profiling against this budget.
- **NFR-3 (Offline Resiliency):** 100% of core logic must operate offline without remote server connectivity.
- **NFR-4 (Data Integrity):** Tasks and usage records shall be persisted in Room with conflict-safe primary keys. Room shall not run on the main thread: the UI uses coroutines on `Dispatchers.IO`, receivers use `goAsync()`, and the enforcement service uses its own `HandlerThread`. Balance, reroll and penalty read-modify-writes are serialized by one lock, and backup restore replaces all tasks in a single Room transaction.
- **NFR-5 (Release Security):** Backup imports shall be validated before replacement; release encryption, biometric protection, and keystore-backed secrets remain required for a production security target.
- **NFR-6 (Enforcement Availability):** Blocking and allowance consumption shall operate only while the user has enabled FocusBank in Android Accessibility settings and the service is actually bound. The UI shall distinguish enabled permission from a running service heartbeat.
- **NFR-7 (Visible Time State):** The Vault shall refresh the exact second balance while visible and shall display it as hours, minutes, and seconds so consumption is observable during testing.

---

## 6. Data Models & Entity Relationship

```sql
-- Room Database Schema

CREATE TABLE tasks (
  id TEXT PRIMARY KEY NOT NULL,
    title TEXT NOT NULL,
    priority TEXT NOT NULL, -- 'HIGH', 'MEDIUM', 'LOW'
  reward_minutes INTEGER NOT NULL,
  completed INTEGER NOT NULL DEFAULT 0,
  deadline_millis INTEGER NOT NULL,
  penalty_minutes INTEGER NOT NULL,
  penalty_processed INTEGER NOT NULL DEFAULT 0,
  reminder_millis INTEGER NOT NULL,
  completed_at INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE usage_records (
  package_name TEXT NOT NULL,
  day_key TEXT NOT NULL,
  seconds INTEGER NOT NULL,
  PRIMARY KEY (package_name, day_key)
);

-- SharedPreferences settings include balance_seconds, blocked_apps,
-- per-app allowances, app categories, penalty expiry timestamps,
-- penalty rerolls, and dark_theme.
```

---

## 7. User Interface Flow & Wireframe Blueprint

### 7.1 Screen Overview
```
[ 1. Main Dashboard / Vault ]
   ├── Available Vault Balance Card (Hero Element)
  ├── Earned and spent usage summary
  ├── Active penalty status
  ├── Backup, restore, and reset actions
   ├── Quick Permission Health Indicator (Accessibility & Overlay Status)
   ├── Pending Tasks List (Sorted by Urgency)
  └── Theme toggle

[ 2. Tasks ]
  ├── Calendar deadline and clock due-time controls
  ├── User-selected reminder time
  ├── Active and completed task sections
  └── Edit, complete, and delete actions

[ 3. Schedule ]
  └── Seven day by eight-column, three-hour time-window matrix

[ 4. Apps ]
  ├── Category subtabs: Social, Streaming, Games, Other
  ├── Searchable multi-select "Add apps" flow
  └── App icon, name, protection switch, and manual category assignment

[ 5. Block Overlay View ] (Full Screen Shield)
   ├── Warning Icon & "Vault Depleted" Status
   ├── Current Active Penalty Countdown (if applicable)
   ├── High-Priority Task Spotlight: "Complete this task now to earn 20m"
   ├── Button: "Go to FocusBank"
   └── Button: "Exit to Home Screen"
```

---

## 8. Release & Deployment Strategy

### 8.1 Standalone APK Distribution
- **Target File:** Universal Release APK (`focusbank-release.apk`)
- **Signing:** `signingConfigs.release` reads the upload keystore from the git-ignored `focusbank_android/keystore.properties` (template: `keystore.properties.example`) or from the `FOCUSBANK_KEYSTORE_FILE` / `FOCUSBANK_KEYSTORE_PASSWORD` / `FOCUSBANK_KEY_ALIAS` / `FOCUSBANK_KEY_PASSWORD` environment variables. Without them, release builds are unsigned and Gradle prints a warning. For Google Play this key is the *upload* key, and Play App Signing holds the app signing key. APKs are signed with scheme v2, which covers every version from minSdk 26 up.
- **Minification:** *Not enabled yet* (`isMinifyEnabled = false`). Turning on R8 needs keep-rule verification and a device test first.

### 8.2 Testing & Validation Plan
1. **Unit Testing** (`app/src/test`, `./gradlew testDebugUnitTest`): `TaskRulesTest` covers the pure rules. 69 tests in total. `FocusBankStoreTest` (Robolectric, in-memory Room) covers allowances, completion, charging, penalties, the screen-free goal, backup/restore and reset. `EnforcementEngineTest` drives the blocking engine through a fake phone. `PenaltyRecheckTest` covers FR-3.10 and alarm scheduling.
2. **Instrumentation Tests:** Accessibility event firing, task-to-reward allocation integration tests.
3. **Hardware Battery Testing:** Run benchmark on physical device for 24-hour cycle with 20+ app swaps to verify minimal power drain.

### 8.3 Current Release Candidate
- **Version:** 1.5.0 (versionCode 6), targetSdk 36
- **Debug APK:** `app/build/outputs/apk/debug/app-debug.apk`
- **Unsigned Release APK:** `app/build/outputs/apk/release/app-release-unsigned.apk`
- **Unsigned Release App Bundle:** `app/build/outputs/bundle/release/app-release.aab`
- **Build status:** Debug and release variants compile successfully.
- **Device status:** Debug APK installed and launched on a physical Android device during implementation.

### 8.4 Release Gates
1. Create the upload keystore and fill in `keystore.properties` (the Gradle signing config exists as of 1.5.0). Keep the keystore and passwords backed up outside the repository, and never commit them.
2. Sign the App Bundle and verify Play App Signing configuration.
3. Complete the Accessibility API declaration (with a short video of the in-app disclosure and blocking), the foreground-service special-use justification, and the Data safety form. `QUERY_ALL_PACKAGES` is no longer requested.
4. Add a privacy policy covering app monitoring, installed-app discovery, local usage records, and backup export.
5. Test reboot recovery, alarms, penalties, rerolls, Room migration, backup restore, and Accessibility behavior across supported Android versions and device manufacturers.

---

## 9. Known Gaps & Open Issues (updated for v1.3.0)

These requirements or behaviours still differ from the intent above:

1. **FR-3.8 daily unlock.** *Partly addressed in 1.3.0:* with a screen-free goal set, a trivial task no longer unlocks the day until the goal is met. With the goal at 0 (the default), the original loophole remains. Screen-off time is only recorded while the enforcement service runs, and the goal can be lowered at any time.
2. **Self-granted rewards.** Reward minutes are user-editable (up to 1,440). A task created with a deadline more than 24 hours away and completed at once also earns a reroll (FR-1.4). There is no minimum task age or cooldown.
3. **Deadline edits avoid penalties.** FR-1.1c lets an unexpired deadline be moved later without limit.
4. **Restore and reset as escape hatches.** Importing an older backup restores its balance and does not clear active penalties. A reset clears penalties. Both are local-only and unauthenticated (see NFR-5).
5. **FR-3.2 anti-circumvention** (Settings detection, strict mode) is not implemented.
6. **Active tasks cannot be deleted.** Only completed tasks show a delete action, while §7.1 lists delete for all tasks. This may be intentional anti-cheat behaviour; the spec should say so.
7. **Alarms are inexact.** Deadlines and the extra allowance use `setAndAllowWhileIdle`, which Doze can delay by several minutes. An extra allowance missed while the device was off is not granted later.
8. ~~Main-thread database access~~ (fixed in 1.3.0). The UI still polls tasks and usage every second, now on `Dispatchers.IO`.
9. ~~Unused permissions~~ (removed in 1.3.0). The §7.1 health indicator shows Accessibility status only; there is no overlay permission left to show.
10. **Schedule matrix and DST.** Window boundaries are computed as midnight + n × 3 h, so on daylight-saving change days they shift by one hour.
