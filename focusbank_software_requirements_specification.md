# Software Requirements Specification (SRS)
## FocusBank: Productivity & App Allowance Vault

---

### Document Control
- **Document Version:** 1.2.0
- **Author:** System Architecture & Product Engineering
- **Status:** Release Candidate / Implementation Baseline
- **Target OS:** Android (Minimum SDK: API 26 / Android 8.0 Oreo; Target SDK: API 34/35+)

### Document Update Summary

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
| `SYSTEM_ALERT_WINDOW` | *Declared but not requested or used.* Blocking always uses the fallback: `FLAG_ACTIVITY_NEW_TASK` launching `BlockOverlayActivity`. | Remove from the manifest before Play review, or implement a true overlay. |
| `PACKAGE_USAGE_STATS` | *Declared but not used.* Foreground detection relies solely on the Accessibility Service. | Remove from the manifest, or implement Usage Access verification. |
| `QUERY_ALL_PACKAGES` | Lists launchable installed apps for the protected-app selector (FR-4.1). | Requires a Play Console permission declaration (§8.4). |
| `POST_NOTIFICATIONS` | Ongoing low-priority notification showing active countdown when distraction apps are in use. | Required on Android 13+ (API 33+) to guarantee foreground service stability. |
| `FOREGROUND_SERVICE_SPECIAL_USE` | Keeps enforcement status visible while the Accessibility Service is connected. | Requires prominent disclosure and Play Console policy review. |
| `RECEIVE_BOOT_COMPLETED` | Restores scheduled task deadline and reminder alarms after reboot or app update. | Tasks remain visible if alarms cannot be restored. |

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
- **FR-3.5:** A penalty shall be stored as remaining seconds and shall pause while general allowance is zero.
- **FR-3.6:** When a pending penalty is discovered at app launch, the UI shall animate a dice roll and end by showing the selected protected app and remaining penalty countdown.
- **FR-3.7:** The Vault shall show the selected app and a live penalty countdown, including a paused state when general allowance is unavailable.
- **FR-3.8:** When all stored tasks have been completed during the current day, protected-app enforcement shall be bypassed for the remainder of that day.
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
- **FR-5.1:** The Tasks screen shall show a persistent daily verse reference card with a source link.
- **FR-5.2:** The Schedule screen shall show seven days as rows and eight three-hour windows as columns.
- **FR-5.3:** Tasks shall appear in the matrix cell matching their deadline window, including exact due time.
- **FR-5.4:** Schedule task text shall be color-coded by priority: high red, medium yellow, low green.
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
- **NFR-2 (Battery Consumption):** Background service CPU footprint must not exceed $2\%$ of daily device battery consumption. Enforcement is driven by `AccessibilityEvent` callbacks plus one 1-second watchdog. The watchdog counts down penalties and recovers foreground events that some OEM builds drop, and it is skipped while the keyguard is locked. No other background polling is allowed. *Open:* the watchdog and the ticker build a new `FocusBankStore` and read Room on the main thread every second, which still needs profiling against this budget.
- **NFR-3 (Offline Resiliency):** 100% of core logic must operate offline without remote server connectivity.
- **NFR-4 (Data Integrity):** Tasks and usage records shall be persisted in Room with conflict-safe primary keys; allowance mutation and task completion require additional transaction hardening before production release.
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
  ├── Persistent verse reference card
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
- **Signing:** Release keystore signed with V2, V3, and V4 signing schemes via Gradle.
- **Minification:** R8 configured with keep rules for Room Entities and Compose runtime.

### 8.2 Testing & Validation Plan
1. **Unit Testing** *(no test sources exist yet)*: Coverage of `TimeBank` state transitions, penalty calculations, and ledger balance aggregation.
2. **Instrumentation Tests:** Accessibility event firing, task-to-reward allocation integration tests.
3. **Hardware Battery Testing:** Run benchmark on physical device for 24-hour cycle with 20+ app swaps to verify minimal power drain.

### 8.3 Current Release Candidate
- **Version:** 1.2.0 (versionCode 3)
- **Debug APK:** `app/build/outputs/apk/debug/app-debug.apk`
- **Unsigned Release APK:** `app/build/outputs/apk/release/app-release-unsigned.apk`
- **Unsigned Release App Bundle:** `app/build/outputs/bundle/release/app-release.aab`
- **Build status:** Debug and release variants compile successfully.
- **Device status:** Debug APK installed and launched on a physical Android device during implementation.

### 8.4 Release Gates
1. Configure and protect a private release keystore; do not commit it or its passwords.
2. Sign the App Bundle and verify Play App Signing configuration.
3. Complete the Accessibility, foreground-service, notification, and `QUERY_ALL_PACKAGES` policy declarations.
4. Add a privacy policy covering app monitoring, installed-app discovery, local usage records, and backup export.
5. Test reboot recovery, alarms, penalties, rerolls, Room migration, backup restore, and Accessibility behavior across supported Android versions and device manufacturers.

---

## 9. Known Gaps & Open Issues (audit, v1.2.0)

These requirements or behaviours still differ from the intent above and are **not** fixed in 1.2.0:

1. **FR-3.8 daily-unlock loophole.** Creating one trivial task and completing it immediately turns off all allowance enforcement for the rest of the day. The rule is implemented exactly as written, so the requirement itself needs a minimum (for example, at least N tasks, or total reward ≥ X minutes).
2. **Self-granted rewards.** Reward minutes are user-editable (up to 1,440). A task created with a deadline more than 24 hours away and completed at once also earns a reroll (FR-1.4). There is no minimum task age or cooldown.
3. **Deadline edits avoid penalties.** FR-1.1c lets an unexpired deadline be moved later without limit.
4. **Restore and reset as escape hatches.** Importing an older backup restores its balance and does not clear active penalties. A reset clears penalties. Both are local-only and unauthenticated (see NFR-5).
5. **FR-3.2 anti-circumvention** (Settings detection, strict mode) is not implemented.
6. **Active tasks cannot be deleted.** Only completed tasks show a delete action, while §7.1 lists delete for all tasks. This may be intentional anti-cheat behaviour; the spec should say so.
7. **Alarms are inexact.** Deadlines and the extra allowance use `setAndAllowWhileIdle`, which Doze can delay by several minutes. An extra allowance missed while the device was off is not granted later.
8. **Main-thread database access.** Room is built with `allowMainThreadQueries()`, and the UI polls tasks and usage every second (NFR-2, NFR-4).
9. **Unused permissions.** `SYSTEM_ALERT_WINDOW` and `PACKAGE_USAGE_STATS` are declared but unused (§3). The Vault health card shows Accessibility status only, not overlay status.
10. **Schedule matrix and DST.** Window boundaries are computed as midnight + n × 3 h, so on daylight-saving change days they shift by one hour.
