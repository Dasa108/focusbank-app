package com.focusbank.app

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.content.ComponentName
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Build
import android.net.Uri
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.lifecycleScope
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.core.graphics.drawable.toBitmap
import java.util.UUID
import java.util.Calendar
import java.util.Date
import java.text.SimpleDateFormat
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val importBackup = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        lifecycleScope.launch {
            val restored = withContext(Dispatchers.IO) {
                runCatching {
                    contentResolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
                        val store = FocusBankStore(getSharedPreferences("focusbank", MODE_PRIVATE), this@MainActivity)
                        store.restoreJson(reader.readText())
                        TaskDeadlineReceiver.rescheduleAll(this@MainActivity, store.tasks())
                    } ?: error("Unable to read backup")
                }
            }
            restored.onFailure { error ->
                Toast.makeText(this@MainActivity, "Backup not restored: ${error.message}", Toast.LENGTH_LONG).show()
            }
            if (restored.isSuccess) recreate()
        }
    }
    // FR-6.1: write a real .json file so it can be picked again by the import flow.
    private val exportBackup = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri ?: return@registerForActivityResult
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    val json = FocusBankStore(getSharedPreferences("focusbank", MODE_PRIVATE), this@MainActivity).backupJson()
                    contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { writer -> writer.write(json) } ?: error("Unable to write backup")
                }
            }.onSuccess {
                Toast.makeText(this@MainActivity, "Backup saved", Toast.LENGTH_SHORT).show()
            }.onFailure { error ->
                Toast.makeText(this@MainActivity, "Backup not saved: ${error.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 42)
        }
        // State is refreshed by the 1-second loop in FocusBankApp, so onResume no longer calls setContent
        // (that rebuilt the whole UI on every return and reset the selected tab and task form).
        setContent {
            FocusBankRoot(
                this@MainActivity,
                onImportBackup = { importBackup.launch(arrayOf("application/json", "text/plain")) },
                onExportBackup = {
                    val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                    exportBackup.launch("focusbank-backup-$day.json")
                }
            )
        }
    }
}

@Composable
private fun FocusBankRoot(activity: MainActivity, onImportBackup: () -> Unit, onExportBackup: () -> Unit) {
    val preferences = activity.getSharedPreferences("focusbank", Context.MODE_PRIVATE)
    var darkTheme by remember { mutableStateOf(preferences.getBoolean("dark_theme", false)) }
    val store = remember { FocusBankStore(preferences, activity) }
    FocusBankTheme(darkTheme) {
        FocusBankApp(
            store,
            darkTheme = darkTheme,
            onToggleTheme = {
                darkTheme = !darkTheme
                preferences.edit().putBoolean("dark_theme", darkTheme).apply()
                },
            onImportBackup = onImportBackup,
            onExportBackup = onExportBackup
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FocusBankApp(store: FocusBankStore, darkTheme: Boolean, onToggleTheme: () -> Unit, onImportBackup: () -> Unit, onExportBackup: () -> Unit) {
    var selectedTab by remember { mutableStateOf(0) }
    var tasks by remember { mutableStateOf<List<FocusTask>>(emptyList()) }
    var balance by remember { mutableStateOf(store.balanceSeconds()) }
    var blockedApps by remember { mutableStateOf(store.blockedApps()) }
    var appAllowances by remember { mutableStateOf(store.appAllowances()) }
    var appCategories by remember { mutableStateOf(store.appCategories()) }
    var usageToday by remember { mutableStateOf<List<UsageSummary>>(emptyList()) }
    var awayGoalMinutes by remember { mutableStateOf(store.awayGoalMinutes()) }
    var awaySeconds by remember { mutableStateOf(store.awaySecondsToday()) }
    val scope = rememberCoroutineScope()
    // Room is off-limits on the main thread: run a store call on IO, then apply its result on main.
    fun <T> io(block: () -> T, then: (T) -> Unit = {}) {
        scope.launch { then(withContext(Dispatchers.IO) { block() }) }
    }
    var activePenalties by remember { mutableStateOf(store.activePenalties()) }
    var showPenaltyReveal by remember { mutableStateOf(false) }
    var revealedPenaltyPackage by remember { mutableStateOf<String?>(null) }
    var diceFace by remember { mutableStateOf(1) }
    var diceRolling by remember { mutableStateOf(false) }
    val verse = remember { gitaVerses.random() }
    val context = LocalContext.current
    var installedApps by remember { mutableStateOf<List<InstalledApp>>(emptyList()) }
    var accessibilityEnabled by remember { mutableStateOf(isEnforcementRunning(context)) }

    LaunchedEffect(context) {
        installedApps = withContext(Dispatchers.IO) { loadInstalledApps(context) }
        accessibilityEnabled = isEnforcementRunning(context)
        ExtraAllowanceReceiver.schedule(context)
    }

    LaunchedEffect(store) {
        while (true) {
            balance = store.balanceSeconds()
            val (freshTasks, freshUsage) = withContext(Dispatchers.IO) { store.tasks() to store.usageToday() }
            tasks = freshTasks
            usageToday = freshUsage
            awaySeconds = store.awaySecondsToday()
            activePenalties = store.activePenalties()
            accessibilityEnabled = isEnforcementRunning(context)
            delay(1_000L)
        }
    }

    val launchPenalty = activePenalties.firstOrNull()
    LaunchedEffect(launchPenalty?.penaltyId) {
        if (launchPenalty != null && !store.penaltyRollShown(launchPenalty.penaltyId)) {
            revealedPenaltyPackage = launchPenalty.packageName
            store.markPenaltyRollShown(launchPenalty.penaltyId)
            showPenaltyReveal = true
            diceRolling = true
            repeat(12) {
                diceFace = (1..6).random()
                delay(100L)
            }
            delay(900L)
            diceRolling = false
        }
    }

    if (showPenaltyReveal && launchPenalty != null) {
        AlertDialog(
            onDismissRequest = { showPenaltyReveal = false },
            title = { Text("Penalty roll") },
            text = {
                Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("$diceFace", style = MaterialTheme.typography.displayLarge, color = MaterialTheme.colorScheme.secondary)
                    Text(if (diceRolling) "Rolling a protected app penalty..." else "Restricted app", style = MaterialTheme.typography.bodyMedium)
                    Text(appLabel(context, launchPenalty.packageName), style = MaterialTheme.typography.titleMedium)
                    val remaining = ((launchPenalty.untilMillis - System.currentTimeMillis()) / 1000L).coerceAtLeast(0L)
                    Text("${remaining / 60L}m ${remaining % 60L}s remaining", style = MaterialTheme.typography.labelLarge)
                }
            },
            confirmButton = { TextButton(onClick = { showPenaltyReveal = false }) { Text("Continue") } }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(listOf("Vault", "Tasks", "Schedule", "Apps")[selectedTab], style = MaterialTheme.typography.headlineSmall) },
                colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                ),
                actions = {
                    TextButton(onClick = onToggleTheme) { Text(if (darkTheme) "Light" else "Dark") }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                listOf("Vault", "Tasks", "Schedule", "Apps").forEachIndexed { index, title ->
                    NavigationBarItem(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        icon = { Text(listOf("$", "✓", "▤", "▦")[index], style = MaterialTheme.typography.titleMedium) },
                        label = { Text(title) }
                    )
                }
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            when (selectedTab) {
                0 -> VaultScreen(
                    balance = balance,
                    pendingTasks = tasks.count { !it.completed },
                    earnedToday = tasks.filter { it.completed && it.completedAt >= startOfToday() }.sumOf { TaskRules.earnedMinutes(it) },
                    usageToday = usageToday,
                    activePenalties = activePenalties,
                    extraAllowanceHour = store.extraAllowanceHour(),
                    extraAllowanceMinute = store.extraAllowanceMinute(),
                    onSetExtraAllowance = { hour, minute ->
                        store.setExtraAllowanceTime(hour, minute)
                        ExtraAllowanceReceiver.schedule(context)
                    },
                    onExportBackup = onExportBackup,
                    onImportBackup = onImportBackup,
                    onReset = {
                        io({ store.resetAll() }) {
                            context.startActivity(Intent(context, MainActivity::class.java))
                            (context as? MainActivity)?.finish()
                        }
                    },
                    awayGoalMinutes = awayGoalMinutes,
                    awaySecondsToday = awaySeconds,
                    onSetAwayGoal = { minutes ->
                        store.setAwayGoalMinutes(minutes)
                        awayGoalMinutes = store.awayGoalMinutes()
                    },
                    onOpenSettings = { selectedTab = 3 },
                    accessibilityEnabled = accessibilityEnabled,
                    onEnableAccessibility = {
                        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    },
                    onReduceAllowance = { minutes ->
                        store.reduceAllowance(minutes)
                        balance = store.balanceSeconds()
                    }
                )
                1 -> TaskScreen(
                    tasks = tasks,
                    verse = verse,
                    rerolls = store.rerolls(),
                    onAdd = { title, priority, reward, deadlineMillis, penaltyMinutes, reminderMillis ->
                        val task = FocusTask(
                            UUID.randomUUID().toString(), title, priority, reward, false,
                            deadlineMillis, penaltyMinutes, false, reminderMillis
                        )
                        TaskDeadlineReceiver.schedule(context, task)
                        io({ store.addTask(task); store.tasks() }) { tasks = it }
                    },
                    onDelete = { task ->
                        TaskDeadlineReceiver.cancel(context, task.id)
                        io({ store.deleteTask(task.id); store.tasks() }) { tasks = it }
                    },
                    onEdit = { task ->
                        TaskDeadlineReceiver.schedule(context, task)
                        io({ store.addTask(task); store.tasks() }) { tasks = it }
                    },
                    onComplete = { task ->
                        TaskDeadlineReceiver.cancel(context, task.id)
                        io({ store.completeTask(task.id); store.tasks() }) {
                            tasks = it
                            balance = store.balanceSeconds()
                        }
                    }
                )
                2 -> ScheduleScreen(tasks)
                3 -> AppsScreen(
                    apps = installedApps,
                    blockedApps = blockedApps,
                    appCategories = appCategories,
                    appAllowances = appAllowances,
                    onToggle = { packageName, enabled ->
                        if (enabled) store.addBlockedApp(packageName) else store.removeBlockedApp(packageName)
                        blockedApps = store.blockedApps()
                        appAllowances = store.appAllowances()
                    },
                    onSetCategory = { packageName, category ->
                        store.setAppCategory(packageName, category)
                        appCategories = store.appCategories()
                    },
                    onSetAllowance = { packageName, minutes ->
                        store.setAppAllowance(packageName, minutes)
                        appAllowances = store.appAllowances()
                    },
                    onRemoveAllowance = { packageName ->
                        store.removeAppAllowance(packageName)
                        appAllowances = store.appAllowances()
                    }
                )
            }
        }
    }
}

@Composable
private fun VaultScreen(
    balance: Long,
    pendingTasks: Int,
    earnedToday: Int,
    usageToday: List<UsageSummary>,
    activePenalties: List<ActivePenalty>,
    extraAllowanceHour: Int,
    extraAllowanceMinute: Int,
    onSetExtraAllowance: (Int, Int) -> Unit,
    onExportBackup: () -> Unit,
    onImportBackup: () -> Unit,
    onReset: () -> Unit,
    onOpenSettings: () -> Unit,
    accessibilityEnabled: Boolean,
    onEnableAccessibility: () -> Unit,
    onReduceAllowance: (Int) -> Unit,
    awayGoalMinutes: Int,
    awaySecondsToday: Long,
    onSetAwayGoal: (Int) -> Unit
) {
    var showReduceDialog by remember { mutableStateOf(false) }
    var showAwayGoalDialog by remember { mutableStateOf(false) }
    var awayGoalText by remember(awayGoalMinutes) { mutableStateOf(awayGoalMinutes.toString()) }
    var reductionAmount by remember { mutableStateOf("5") }
    var showResetDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val extraTime = if (extraAllowanceHour >= 0) {
        String.format(Locale.getDefault(), "%02d:%02d", extraAllowanceHour, extraAllowanceMinute)
    } else "Not scheduled"

    if (showReduceDialog) {
        AlertDialog(
            onDismissRequest = { showReduceDialog = false },
            title = { Text("Reduce allowance") },
            text = {
                OutlinedTextField(
                    value = reductionAmount,
                    onValueChange = { reductionAmount = it.filter(Char::isDigit) },
                    label = { Text("Minutes to remove") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    reductionAmount.toIntOrNull()?.takeIf { it > 0 }?.let(onReduceAllowance)
                    showReduceDialog = false
                }) { Text("Reduce") }
            },
            dismissButton = { TextButton(onClick = { showReduceDialog = false }) { Text("Cancel") } }
        )
    }
    if (showAwayGoalDialog) {
        AlertDialog(
            onDismissRequest = { showAwayGoalDialog = false },
            title = { Text("Daily screen-free goal") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Minutes your screen must be off each day, counted from your first unlock. Set 0 to turn the goal off.")
                    OutlinedTextField(
                        value = awayGoalText,
                        onValueChange = { awayGoalText = it.filter(Char::isDigit).take(4) },
                        label = { Text("Minutes away from the screen") },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onSetAwayGoal(awayGoalText.toIntOrNull() ?: 0)
                    showAwayGoalDialog = false
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showAwayGoalDialog = false }) { Text("Cancel") } }
        )
    }
    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text("Reset FocusBank data?") },
            text = { Text("This removes tasks, usage history, allowances, categories, and penalties. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = { showResetDialog = false; onReset() }) { Text("Reset everything") }
            },
            dismissButton = { TextButton(onClick = { showResetDialog = false }) { Text("Cancel") } }
        )
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Earn your time back.", style = MaterialTheme.typography.titleLarge)
        Text("Your focus allowance is earned through completed work.", style = MaterialTheme.typography.bodyMedium)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Blocking protection", style = MaterialTheme.typography.titleMedium)
                Text(if (accessibilityEnabled) "Active: FocusBank can monitor selected apps." else "Not active: enable Accessibility access to block selected apps.")
                if (!accessibilityEnabled) {
                    FilledTonalButton(onClick = onEnableAccessibility) { Text("Enable blocking") }
                }
            }
        }
        if (activePenalties.isNotEmpty()) {
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Active penalties", style = MaterialTheme.typography.titleMedium)
                    activePenalties.forEach { penalty ->
                        val remaining = ((penalty.untilMillis - System.currentTimeMillis()) / 1000L).coerceAtLeast(0L)
                        val hours = remaining / 3600L
                        val minutes = (remaining % 3600L) / 60L
                        val seconds = remaining % 60L
                        Text("${appLabel(context, penalty.packageName)}: %02d:%02d:%02d remaining".format(hours, minutes, seconds))
                        if (balance <= 0L) Text("Paused until general allowance is available.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary)
        ) {
            Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("AVAILABLE NOW", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.75f))
                Text(formatAllowance(balance), style = MaterialTheme.typography.displayMedium, color = MaterialTheme.colorScheme.onPrimary)
                    Text(if (balance > 0L) "Your selected apps are unlocked." else "Complete a task to unlock your apps.", color = MaterialTheme.colorScheme.onPrimary)
            }
        }
        OutlinedButton(onClick = { showReduceDialog = true }, modifier = Modifier.fillMaxWidth()) {
            Text("Reduce available time")
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Daily allowance", style = MaterialTheme.typography.titleMedium)
                Text("30 minutes are added each morning. Add another 30 minutes at a time you choose.")
                OutlinedButton(onClick = {
                    val now = Calendar.getInstance()
                    TimePickerDialog(context, { _, hour, minute -> onSetExtraAllowance(hour, minute) }, now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE), false).show()
                }) { Text("Extra 30 minutes: $extraTime") }
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val awayMinutes = awaySecondsToday / 60L
                Text("Screen-free time", style = MaterialTheme.typography.titleMedium)
                if (awayGoalMinutes > 0) {
                    val met = awayMinutes >= awayGoalMinutes
                    Text("$awayMinutes of $awayGoalMinutes min away from the screen today")
                    LinearProgressIndicator(
                        progress = { (awayMinutes.toFloat() / awayGoalMinutes).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        if (met) "Goal reached. Finishing every task now unlocks the rest of the day."
                        else "Finishing every task unlocks the rest of the day only after you reach this goal.",
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    Text("$awayMinutes min away from the screen today")
                    Text("No goal set: finishing every task unlocks the rest of the day straight away.", style = MaterialTheme.typography.bodySmall)
                }
                if (!accessibilityEnabled) {
                    Text("Screen-off time is only counted while blocking protection is active.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                OutlinedButton(onClick = { showAwayGoalDialog = true }) {
                    Text(if (awayGoalMinutes > 0) "Change goal" else "Set a goal")
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onExportBackup, modifier = Modifier.weight(1f)) { Text("Export backup") }
            OutlinedButton(onClick = onImportBackup, modifier = Modifier.weight(1f)) { Text("Import backup") }
        }
        TextButton(onClick = { showResetDialog = true }) { Text("Reset all data") }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MetricCard("$pendingTasks", "Open tasks", Modifier.weight(1f))
                MetricCard(if (balance > 0L) "ON" else "OFF", "Vault status", Modifier.weight(1f))
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Today", style = MaterialTheme.typography.titleMedium)
                Text("Earned: ${earnedToday}m   •   Spent: ${usageToday.sumOf { it.seconds } / 60}m")
                if (usageToday.isNotEmpty()) {
                    usageToday.take(5).forEach { usage ->
                        Text("${appLabel(context, usage.packageName)}: ${usage.seconds / 60}m", style = MaterialTheme.typography.bodySmall)
                    }
                } else {
                    Text("No protected-app usage recorded today.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Your protection", style = MaterialTheme.typography.titleMedium)
                Text("Choose the apps that should require earned allowance.", style = MaterialTheme.typography.bodyMedium)
                FilledTonalButton(onClick = onOpenSettings) { Text("Manage protected apps") }
            }
        }
    }
}

private fun startOfToday(): Long = Calendar.getInstance().apply {
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
}.timeInMillis

private fun formatAllowance(totalSeconds: Long): String {
    val seconds = totalSeconds.coerceAtLeast(0L)
    val hours = seconds / 3600L
    val minutes = (seconds % 3600L) / 60L
    val remainingSeconds = seconds % 60L
    return "%02d:%02d:%02d".format(hours, minutes, remainingSeconds)
}

private fun isAccessibilityEnabled(context: Context): Boolean {
    val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? android.view.accessibility.AccessibilityManager
    if (am != null) {
        val enabledList = am.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        if (enabledList.any { it.resolveInfo?.serviceInfo?.packageName == context.packageName }) {
            return true
        }
    }
    val enabledServices = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ).orEmpty()
    val fullComponent = ComponentName(context, AppMonitorService::class.java).flattenToString()
    val shortComponent = ComponentName(context, AppMonitorService::class.java).flattenToShortString()
    return enabledServices.split(':').any {
        it.equals(fullComponent, ignoreCase = true) || it.equals(shortComponent, ignoreCase = true)
    }
}

private fun isEnforcementRunning(context: Context): Boolean {
    val preferences = context.getSharedPreferences("focusbank", Context.MODE_PRIVATE)
    return isAccessibilityEnabled(context) && System.currentTimeMillis() - preferences.getLong("service_heartbeat", 0L) < 10_000L
}

@Composable
private fun MetricCard(value: String, label: String, modifier: Modifier) {
    Card(modifier) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(value, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
            Text(label, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun TaskScreen(
    tasks: List<FocusTask>,
    verse: GitaVerse,
    rerolls: Int,
    onAdd: (String, String, Int, Long, Int, Long) -> Unit,
    onDelete: (FocusTask) -> Unit,
    onEdit: (FocusTask) -> Unit,
    onComplete: (FocusTask) -> Unit
) {
    var title by remember { mutableStateOf("") }
    var priority by remember { mutableStateOf("MEDIUM") }
    var reward by remember { mutableStateOf("20") }
    var deadlineMillis by remember { mutableStateOf(System.currentTimeMillis() + 24L * 60L * 60L * 1000L) }
    var penaltyMinutes by remember { mutableStateOf("60") }
    var editingTask by remember { mutableStateOf<FocusTask?>(null) }
    var editTitle by remember { mutableStateOf("") }
    var editReward by remember { mutableStateOf("") }
    var editPriority by remember { mutableStateOf("MEDIUM") }
    var editDeadlineMillis by remember { mutableStateOf(0L) }
    val dateFormat = remember { SimpleDateFormat("EEE, MMM d", Locale.getDefault()) }
    val timeFormat = remember { SimpleDateFormat("h:mm a", Locale.getDefault()) }
    val context = LocalContext.current

    editingTask?.let { task ->
        AlertDialog(
            onDismissRequest = { editingTask = null },
            title = { Text("Edit task") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(editTitle, { editTitle = it.take(TaskRules.MAX_TITLE_LENGTH) }, label = { Text("Title") }, singleLine = true)
                    OutlinedTextField(editReward, { editReward = it.filter(Char::isDigit).take(4) }, label = { Text("Reward minutes") }, singleLine = true)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("HIGH", "MEDIUM", "LOW").forEach { value ->
                            FilterChip(editPriority == value, { editPriority = value }, label = { Text(value) })
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            val calendar = Calendar.getInstance().apply { timeInMillis = editDeadlineMillis }
                            DatePickerDialog(context, { _, year, month, day ->
                                calendar.set(year, month, day)
                                editDeadlineMillis = calendar.timeInMillis
                            }, calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH), calendar.get(Calendar.DAY_OF_MONTH)).show()
                        }, modifier = Modifier.weight(1f)) { Text("Date: ${dateFormat.format(editDeadlineMillis)}") }
                        OutlinedButton(onClick = {
                            val calendar = Calendar.getInstance().apply { timeInMillis = editDeadlineMillis }
                            TimePickerDialog(context, { _, hour, minute ->
                                calendar.set(Calendar.HOUR_OF_DAY, hour)
                                calendar.set(Calendar.MINUTE, minute)
                                editDeadlineMillis = calendar.timeInMillis
                            }, calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE), false).show()
                        }, modifier = Modifier.weight(1f)) { Text("Due: ${timeFormat.format(editDeadlineMillis)}") }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (editTitle.isNotBlank()) {
                        val reward = (editReward.toIntOrNull() ?: task.rewardMinutes).coerceIn(0, TaskRules.MAX_TASK_MINUTES)
                        onEdit(task.copy(title = editTitle.trim(), priority = editPriority, rewardMinutes = reward, deadlineMillis = editDeadlineMillis))
                    }
                    editingTask = null
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editingTask = null }) { Text("Cancel") } }
        )
    }
    var reminderMillis by remember { mutableStateOf(System.currentTimeMillis() + 23L * 60L * 60L * 1000L) }
    var sortMode by remember { mutableStateOf(TaskRules.Sort.DUE_DATE) }
    // Keep the reminder ahead of the deadline whenever the deadline moves (FR-1.1b).
    fun updateDeadline(value: Long) {
        deadlineMillis = value
        reminderMillis = value - 60L * 60L * 1000L
    }
    val activeTasks = TaskRules.sort(tasks.filterNot { it.completed }, sortMode, System.currentTimeMillis())
    val completedTasks = tasks.filter { it.completed }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Text("Small wins compound.", style = MaterialTheme.typography.titleLarge)
            Text("Add a task, finish it, and earn time for yourself.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))
            Text("Penalty rerolls available: $rerolls", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        item {
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("TODAY'S VERSE", style = MaterialTheme.typography.labelMedium)
                    Text(verse.reference, style = MaterialTheme.typography.titleMedium)
                    Text(verse.theme, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Text(verse.translation)
                    Text("Translation supplied in the FocusBank verse compendium.", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(verse.url))) }) { Text("Read full verse") }
                }
            }
        }
        item {
            OutlinedTextField(
                title, { title = it.take(TaskRules.MAX_TITLE_LENGTH) }, Modifier.fillMaxWidth(),
                label = { Text("Task title") }, singleLine = true,
                supportingText = { Text("${title.length} / ${TaskRules.MAX_TITLE_LENGTH}") }
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("HIGH", "MEDIUM", "LOW").forEach { value ->
                    FilterChip(selected = priority == value, onClick = {
                        priority = value
                        reward = TaskRules.defaultReward(value).toString()
                    }, label = { Text(value) })
                }
            }
        }
        item {
            OutlinedTextField(reward, { reward = it.filter(Char::isDigit).take(4) }, label = { Text("Reward minutes") }, singleLine = true)
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(penaltyMinutes, { penaltyMinutes = it.filter(Char::isDigit).take(4) }, Modifier.weight(1f), label = { Text("Penalty minutes") }, singleLine = true)
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    val calendar = Calendar.getInstance().apply { timeInMillis = deadlineMillis }
                    DatePickerDialog(context, { _, year, month, day ->
                        calendar.set(year, month, day)
                        updateDeadline(calendar.timeInMillis)
                    }, calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH), calendar.get(Calendar.DAY_OF_MONTH)).show()
                }, modifier = Modifier.weight(1f)) { Text("Date: ${dateFormat.format(deadlineMillis)}") }
                OutlinedButton(onClick = {
                    val calendar = Calendar.getInstance().apply { timeInMillis = deadlineMillis }
                    TimePickerDialog(context, { _, hour, minute ->
                        calendar.set(Calendar.HOUR_OF_DAY, hour)
                        calendar.set(Calendar.MINUTE, minute)
                        updateDeadline(calendar.timeInMillis)
                    }, calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE), false).show()
                }, modifier = Modifier.weight(1f)) { Text("Due: ${timeFormat.format(deadlineMillis)}") }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    val calendar = Calendar.getInstance().apply { timeInMillis = reminderMillis }
                    DatePickerDialog(context, { _, year, month, day ->
                        calendar.set(year, month, day)
                        reminderMillis = calendar.timeInMillis
                    }, calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH), calendar.get(Calendar.DAY_OF_MONTH)).show()
                }, modifier = Modifier.weight(1f)) { Text("Remind: ${dateFormat.format(reminderMillis)}") }
                OutlinedButton(onClick = {
                    val calendar = Calendar.getInstance().apply { timeInMillis = reminderMillis }
                    TimePickerDialog(context, { _, hour, minute ->
                        calendar.set(Calendar.HOUR_OF_DAY, hour)
                        calendar.set(Calendar.MINUTE, minute)
                        reminderMillis = calendar.timeInMillis
                    }, calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE), false).show()
                }, modifier = Modifier.weight(1f)) { Text("At: ${timeFormat.format(reminderMillis)}") }
            }
        }
        item {
            Button(onClick = {
                val now = System.currentTimeMillis()
                when {
                    title.isBlank() -> Toast.makeText(context, "Give the task a title", Toast.LENGTH_SHORT).show()
                    // A past deadline would fire its penalty alarm immediately.
                    deadlineMillis <= now -> Toast.makeText(context, "Pick a deadline in the future", Toast.LENGTH_SHORT).show()
                    reminderMillis >= deadlineMillis -> Toast.makeText(context, "The reminder must be before the deadline", Toast.LENGTH_SHORT).show()
                    else -> {
                        onAdd(
                            title.trim(),
                            priority,
                            (reward.toIntOrNull() ?: TaskRules.defaultReward(priority)).coerceIn(0, TaskRules.MAX_TASK_MINUTES),
                            deadlineMillis,
                            (penaltyMinutes.toIntOrNull() ?: 60).coerceIn(0, TaskRules.MAX_TASK_MINUTES),
                            reminderMillis
                        )
                        title = ""
                    }
                }
            }, modifier = Modifier.fillMaxWidth()) { Text("Add priority task") }
            HorizontalDivider(Modifier.padding(top = 4.dp, bottom = 8.dp))
        }
        if (activeTasks.isNotEmpty()) {
            item {
                Text("Active tasks", style = MaterialTheme.typography.titleMedium)
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TaskRules.Sort.values().forEach { mode ->
                        FilterChip(selected = sortMode == mode, onClick = { sortMode = mode }, label = { Text(mode.label) })
                    }
                }
            }
        }
        items(activeTasks, key = { it.id }) { task ->
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text(task.title, style = MaterialTheme.typography.titleMedium)
                        Text("${task.priority} • +${task.rewardMinutes} min • due ${dateFormat.format(task.deadlineMillis)} ${timeFormat.format(task.deadlineMillis)}")
                        if (sortMode == TaskRules.Sort.EISENHOWER) {
                            Text(TaskRules.quadrant(task, System.currentTimeMillis()).label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = {
                            editingTask = task
                            editTitle = task.title
                            editReward = task.rewardMinutes.toString()
                            editPriority = task.priority
                            editDeadlineMillis = task.deadlineMillis.takeIf { it > 0L } ?: System.currentTimeMillis()
                        }) { Text("Edit") }
                        Button(onClick = { onComplete(task) }) { Text("Complete") }
                    }
                }
            }
        }
        if (completedTasks.isNotEmpty()) {
            item { Text("Completed", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp)) }
        }
        items(completedTasks, key = { it.id }) { task ->
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text(task.title, style = MaterialTheme.typography.titleMedium)
                        val late = TaskRules.isLate(task)
                        Text(if (late) "${task.priority} • completed late, no reward" else "${task.priority} • +${task.rewardMinutes} min earned")
                    }
                    TextButton(onClick = { onDelete(task) }) { Text("Delete") }
                }
            }
        }
    }
}

@Composable
private fun ScheduleScreen(tasks: List<FocusTask>) {
    val dayFormat = remember { SimpleDateFormat("EEE, MMM d", Locale.getDefault()) }
    val timeFormat = remember { SimpleDateFormat("h:mm a", Locale.getDefault()) }
    val today = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    val days = (0..6).map { offset ->
        Calendar.getInstance().apply {
            timeInMillis = today.timeInMillis
            add(Calendar.DAY_OF_YEAR, offset)
        }
    }
    val horizontalScroll = rememberScrollState()
    val cellWidth = 124.dp
    val dayWidth = 112.dp

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Text("Next seven days", style = MaterialTheme.typography.titleLarge)
            Text("Days are rows; due-time windows are columns.", style = MaterialTheme.typography.bodyMedium)
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(
                    Modifier
                        .horizontalScroll(horizontalScroll)
                        .width(dayWidth + cellWidth * 8)
                        .padding(12.dp)
                ) {
                    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                        Text("DAY", Modifier.width(dayWidth), style = MaterialTheme.typography.labelSmall)
                        (0..7).forEach { slot ->
                            val start = today.timeInMillis + slot * 3L * 60L * 60L * 1000L
                            val end = start + 3L * 60L * 60L * 1000L
                            Text(
                                "${timeFormat.format(Date(start))}\n${timeFormat.format(Date(end))}",
                                Modifier.width(cellWidth),
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                    HorizontalDivider()
                    days.forEach { day ->
                        Row(Modifier.fillMaxWidth().height(86.dp)) {
                            Text(dayFormat.format(day.time), Modifier.width(dayWidth).padding(top = 10.dp), style = MaterialTheme.typography.bodySmall)
                            (0..7).forEach { slot ->
                                val start = day.timeInMillis + slot * 3L * 60L * 60L * 1000L
                                val end = start + 3L * 60L * 60L * 1000L
                                val intervalTasks = tasks.filter { it.deadlineMillis in start until end }
                                Card(
                                    Modifier.width(cellWidth).fillMaxSize().padding(2.dp),
                                    colors = CardDefaults.cardColors(
                                        containerColor = if (intervalTasks.isEmpty()) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.secondaryContainer
                                    )
                                ) {
                                    Column(Modifier.padding(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        intervalTasks.forEach { task ->
                                            Text(
                                                "${task.title}\n${timeFormat.format(Date(task.deadlineMillis))}",
                                                style = MaterialTheme.typography.labelSmall,
                                                maxLines = 3,
                                                color = if (task.completed) MaterialTheme.colorScheme.primary else priorityColor(task.priority)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

private fun priorityColor(priority: String): Color = when (priority) {
    "HIGH" -> Color(0xFFC62828)
    "MEDIUM" -> Color(0xFFB28704)
    else -> Color(0xFF2E7D32)
}

private data class InstalledApp(val packageName: String, val label: String)

private data class GitaVerse(
    val reference: String,
    val theme: String,
    val translation: String,
    val url: String
)

private val gitaVerses = listOf(
    GitaVerse("Bhagavad-gita 4.6", "Krishna's transcendental appearance", "Although I am unborn and My transcendental body never deteriorates, and although I am the Lord of all living entities, I still appear in every millennium in My original transcendental form.", "https://prabhupadabooks.com/bg/4/6"),
    GitaVerse("Bhagavad-gita 4.9", "Knowing Krishna's activities", "One who knows the transcendental nature of My appearance and activities does not, upon leaving the body, take his birth again in this material world, but attains My eternal abode, O Arjuna.", "https://prabhupadabooks.com/bg/4/9"),
    GitaVerse("Bhagavad-gita 7.7", "Krishna's supreme position", "O conqueror of wealth, there is no truth superior to Me. Everything rests upon Me, as pearls are strung on a thread.", "https://prabhupadabooks.com/bg/7/7"),
    GitaVerse("Bhagavad-gita 7.19", "Vasudeva is everything", "After many births and deaths, he who is actually in knowledge surrenders unto Me, knowing Me to be the cause of all causes and all that is. Such a great soul is very rare.", "https://prabhupadabooks.com/bg/7/19"),
    GitaVerse("Bhagavad-gita 9.4", "Krishna pervades the universe", "By Me, in My unmanifested form, this entire universe is pervaded. All beings are in Me, but I am not in them.", "https://prabhupadabooks.com/bg/9/4"),
    GitaVerse("Bhagavad-gita 9.10", "Nature works under Krishna", "This material nature, which is one of My energies, is working under My direction, O son of Kuntī, producing all moving and nonmoving beings. Under its rule this manifestation is created and annihilated again and again.", "https://prabhupadabooks.com/bg/9/10"),
    GitaVerse("Bhagavad-gita 9.11", "Krishna's transcendental nature", "Fools deride Me when I descend in the human form. They do not know My transcendental nature as the Supreme Lord of all that be.", "https://prabhupadabooks.com/bg/9/11"),
    GitaVerse("Bhagavad-gita 9.17", "Krishna is the source", "I am the father of this universe, the mother, the support and the grandsire. I am the object of knowledge, the purifier and the syllable oṁ. I am also the Ṛg, the Sāma and the Yajur Vedas.", "https://prabhupadabooks.com/bg/9/17"),
    GitaVerse("Bhagavad-gita 9.24", "Krishna is the enjoyer", "I am the only enjoyer and master of all sacrifices. Therefore, those who do not recognize My true transcendental nature fall down.", "https://prabhupadabooks.com/bg/9/24"),
    GitaVerse("Bhagavad-gita 10.2", "Krishna is the source", "Neither the hosts of demigods nor the great sages know My origin or opulences, for, in every respect, I am the source of the demigods and sages.", "https://prabhupadabooks.com/bg/10/2"),
    GitaVerse("Bhagavad-gita 10.8", "Everything emanates from Krishna", "I am the source of all spiritual and material worlds. Everything emanates from Me. The wise who perfectly know this engage in My devotional service and worship Me with all their hearts.", "https://prabhupadabooks.com/bg/10/8"),
    GitaVerse("Bhagavad-gita 10.39", "Krishna is the generating seed", "Furthermore, O Arjuna, I am the generating seed of all existences. There is no being—moving or nonmoving—that can exist without Me.", "https://prabhupadabooks.com/bg/10/39"),
    GitaVerse("Bhagavad-gita 10.41", "Krishna's splendor", "Know that all opulent, beautiful and glorious creations spring from but a spark of My splendor.", "https://prabhupadabooks.com/bg/10/41"),
    GitaVerse("Bhagavad-gita 11.12", "The universal form", "If hundreds of thousands of suns were to rise at once into the sky, their radiance might resemble the effulgence of the Supreme Person in that universal form.", "https://prabhupadabooks.com/bg/11/12"),
    GitaVerse("Bhagavad-gita 14.27", "Krishna is the basis", "And I am the basis of the impersonal Brahman, which is immortal, imperishable and eternal and is the constitutional position of ultimate happiness.", "https://prabhupadabooks.com/bg/14/27"),
    GitaVerse("Bhagavad-gita 15.15", "Krishna in every heart", "I am seated in everyone's heart, and from Me come remembrance, knowledge and forgetfulness. By all the Vedas, I am to be known. Indeed, I am the compiler of Vedānta, and I am the knower of the Vedas.", "https://prabhupadabooks.com/bg/15/15"),
    GitaVerse("Bhagavad-gita 15.19", "Knowing the Supreme Person", "Whoever knows Me as the Supreme Personality of Godhead, without doubting, is the knower of everything. He therefore engages himself in full devotional service to Me, O son of Bharata.", "https://prabhupadabooks.com/bg/15/19"),
    GitaVerse("Bhagavad-gita 18.66", "Surrender to Krishna", "Abandon all varieties of religion and just surrender unto Me. I shall deliver you from all sinful reactions. Do not fear.", "https://prabhupadabooks.com/bg/18/66"),
    GitaVerse("Bhagavad-gita 2.3", "Rise above weakness", "O son of Pṛthā, do not yield to this degrading impotence. It does not become you. Give up such petty weakness of heart and arise, O chastiser of the enemy.", "https://prabhupadabooks.com/bg/2/3"),
    GitaVerse("Bhagavad-gita 2.31", "Duty and purpose", "Considering your specific duty as a kṣatriya, you should know that there is no better engagement for you than fighting on religious principles; and so there is no need for hesitation.", "https://prabhupadabooks.com/bg/2/31"),
    GitaVerse("Bhagavad-gita 2.37", "Determination", "O son of Kuntī, either you will be killed on the battlefield and attain the heavenly planets, or you will conquer and enjoy the earthly kingdom. Therefore, get up with determination and fight.", "https://prabhupadabooks.com/bg/2/37"),
    GitaVerse("Bhagavad-gita 2.38", "Equanimity in action", "Do thou fight for the sake of fighting, without considering happiness or distress, loss or gain, victory or defeat—and by so doing you shall never incur sin.", "https://prabhupadabooks.com/bg/2/38"),
    GitaVerse("Bhagavad-gita 2.47", "Duty without attachment", "You have a right to perform your prescribed duty, but you are not entitled to the fruits of action. Never consider yourself the cause of the results of your activities, and never be attached to not doing your duty.", "https://prabhupadabooks.com/bg/2/47"),
    GitaVerse("Bhagavad-gita 2.48", "Equanimity is yoga", "Perform your duty equipoised, O Arjuna, abandoning all attachment to success or failure. Such equanimity is called yoga.", "https://prabhupadabooks.com/bg/2/48"),
    GitaVerse("Bhagavad-gita 2.50", "The art of work", "A man engaged in devotional service rids himself of both good and bad actions even in this life. Therefore strive for yoga, which is the art of all work.", "https://prabhupadabooks.com/bg/2/50"),
    GitaVerse("Bhagavad-gita 3.4", "Action and perfection", "Not by merely abstaining from work can one achieve freedom from reaction, nor by renunciation alone can one attain perfection.", "https://prabhupadabooks.com/bg/3/4"),
    GitaVerse("Bhagavad-gita 3.8", "Action over inaction", "Perform your prescribed duty, for action is better than inaction. A man cannot even maintain his physical body without work.", "https://prabhupadabooks.com/bg/3/8"),
    GitaVerse("Bhagavad-gita 3.9", "Work as sacrifice", "Work done as a sacrifice for Viṣṇu has to be performed; otherwise work causes bondage in this material world. Therefore, O son of Kuntī, perform your prescribed duties for His satisfaction, and in that way you will always remain free from bondage.", "https://prabhupadabooks.com/bg/3/9"),
    GitaVerse("Bhagavad-gita 3.19", "Duty without attachment", "Therefore, without being attached to the fruits of activities, one should act as a matter of duty, for by working without attachment one attains the Supreme.", "https://prabhupadabooks.com/bg/3/19"),
    GitaVerse("Bhagavad-gita 3.20", "Work as an example", "Kings such as Janaka attained perfection solely by performance of prescribed duties. Therefore, just for the sake of educating the people in general, you should perform your work.", "https://prabhupadabooks.com/bg/3/20"),
    GitaVerse("Bhagavad-gita 3.21", "Exemplary action", "Whatever action a great man performs, common men follow. And whatever standards he sets by exemplary acts, all the world pursues.", "https://prabhupadabooks.com/bg/3/21"),
    GitaVerse("Bhagavad-gita 3.35", "Your own duty", "It is far better to discharge one's prescribed duties, even though faultily, than another's duties perfectly. Destruction in the course of performing one's own duty is better than engaging in another's duties, for to follow another's path is dangerous.", "https://prabhupadabooks.com/bg/3/35"),
    GitaVerse("Bhagavad-gita 9.27", "Offer work to Krishna", "Whatever you do, whatever you eat, whatever you offer or give away, and whatever austerities you perform—do that, O son of Kuntī, as an offering unto Me.", "https://prabhupadabooks.com/bg/9/27"),
    GitaVerse("Bhagavad-gita 18.46", "Work as worship", "By worship of the Lord, who is the source of all beings and who is all-pervading, a man can attain perfection through the performance of his own duty.", "https://prabhupadabooks.com/bg/18/46")
)

private fun loadInstalledApps(context: Context): List<InstalledApp> {
    val packageManager = context.packageManager
    return packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
        .asSequence()
        .filter { it.packageName != context.packageName }
        .filter { packageManager.getLaunchIntentForPackage(it.packageName) != null }
        .map { InstalledApp(it.packageName, packageManager.getApplicationLabel(it).toString()) }
        .sortedBy { it.label.lowercase() }
        .toList()
}

@Composable
private fun AppsScreen(
    apps: List<InstalledApp>,
    blockedApps: List<String>,
    appCategories: Map<String, String>,
    appAllowances: Map<String, Int>,
    onToggle: (String, Boolean) -> Unit,
    onSetCategory: (String, String) -> Unit,
    onSetAllowance: (String, Int) -> Unit,
    onRemoveAllowance: (String) -> Unit
) {
    val context = LocalContext.current
    val categories = listOf("Social", "Streaming", "Games", "Other")
    var selectedCategory by remember { mutableStateOf("Social") }
    var addingToCategory by remember { mutableStateOf<String?>(null) }
    var searchText by remember { mutableStateOf("") }
    var pendingApps by remember { mutableStateOf<Set<String>>(emptySet()) }

    addingToCategory?.let { targetCategory ->
        val candidates = apps.filter {
            it.label.contains(searchText, ignoreCase = true) || it.packageName.contains(searchText, ignoreCase = true)
        }
        AlertDialog(
            onDismissRequest = { addingToCategory = null },
            title = { Text("Add apps to $targetCategory") },
            text = {
                Column {
                    OutlinedTextField(
                        searchText,
                        { searchText = it },
                        Modifier.fillMaxWidth(),
                        label = { Text("Search installed apps") },
                        singleLine = true
                    )
                    LazyColumn(Modifier.height(320.dp)) {
                        items(candidates, key = { it.packageName }) { app ->
                            val checked = app.packageName in pendingApps
                            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                val icon = remember(app.packageName) {
                                    runCatching { context.packageManager.getApplicationIcon(app.packageName).toBitmap(48, 48).asImageBitmap() }.getOrNull()
                                }
                                if (icon != null) Image(icon, contentDescription = null, modifier = Modifier.padding(end = 10.dp))
                                Text(app.label, Modifier.weight(1f))
                                Switch(checked = checked, onCheckedChange = {
                                    pendingApps = if (it) pendingApps + app.packageName else pendingApps - app.packageName
                                })
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingApps.forEach { packageName -> onSetCategory(packageName, targetCategory); onToggle(packageName, true) }
                    addingToCategory = null
                    pendingApps = emptySet()
                    searchText = ""
                }) { Text("Add selected") }
            },
            dismissButton = { TextButton(onClick = { addingToCategory = null }) { Text("Cancel") } }
        )
    }

    val categoryApps = apps.filter { (appCategories[it.packageName] ?: appCategory(it)) == selectedCategory }

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 12.dp)) {
        Text("Your app boundary", style = MaterialTheme.typography.titleLarge)
        Text("Choose a category, then add several apps at once.", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(12.dp))
        TabRow(selectedTabIndex = categories.indexOf(selectedCategory)) {
            categories.forEach { category ->
                Tab(
                    selected = selectedCategory == category,
                    onClick = { selectedCategory = category },
                    text = { Text(category) }
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(selectedCategory, style = MaterialTheme.typography.titleMedium)
                Text("${categoryApps.size} app(s)", style = MaterialTheme.typography.bodySmall)
            }
            FilledTonalButton(onClick = {
                addingToCategory = selectedCategory
                pendingApps = emptySet()
            }) { Text("Add apps") }
        }
        Spacer(Modifier.height(8.dp))
        if (categoryApps.isEmpty()) {
            Text("No apps added yet.", style = MaterialTheme.typography.bodySmall)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 20.dp)) {
                items(categoryApps, key = { it.packageName }) { app ->
                    AppGridCard(
                        app = app,
                        selected = app.packageName in blockedApps,
                        allowance = appAllowances[app.packageName],
                        category = appCategories[app.packageName] ?: appCategory(app),
                        onToggle = onToggle,
                        onSetAllowance = onSetAllowance,
                        onRemoveAllowance = onRemoveAllowance,
                        onSetCategory = onSetCategory
                    )
                }
            }
        }
    }
}

private fun appCategory(app: InstalledApp): String {
    val value = "${app.label} ${app.packageName}".lowercase()
    return when {
        listOf("instagram", "facebook", "whatsapp", "telegram", "snapchat", "twitter", "tiktok", "reddit", "discord").any(value::contains) -> "Social"
        listOf("youtube", "netflix", "prime video", "hotstar", "spotify", "twitch", "stream").any(value::contains) -> "Streaming"
        listOf("game", "gaming", "pubg", "roblox", "minecraft", "clash", "candy crush").any(value::contains) -> "Games"
        else -> "Other"
    }
}

@Composable
private fun AppGridCard(
    app: InstalledApp,
    selected: Boolean,
    allowance: Int?,
    category: String,
    onToggle: (String, Boolean) -> Unit,
    onSetAllowance: (String, Int) -> Unit,
    onRemoveAllowance: (String) -> Unit,
    onSetCategory: (String, String) -> Unit
) {
    val context = LocalContext.current
    var showAllowanceDialog by remember { mutableStateOf(false) }
    var allowanceText by remember { mutableStateOf(allowance?.toString() ?: "15") }
    var showCategoryDialog by remember { mutableStateOf(false) }
    val icon = remember(app.packageName) {
        runCatching {
            context.packageManager.getApplicationIcon(app.packageName).toBitmap(72, 72).asImageBitmap()
        }.getOrNull()
    }

    if (showAllowanceDialog) {
        AlertDialog(
            onDismissRequest = { showAllowanceDialog = false },
            title = { Text("Special time for ${app.label}") },
            text = {
                OutlinedTextField(
                    value = allowanceText,
                    onValueChange = { allowanceText = it.filter(Char::isDigit) },
                    label = { Text("Minutes for this app") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    allowanceText.toIntOrNull()?.takeIf { it > 0 }?.let { onSetAllowance(app.packageName, it) }
                    showAllowanceDialog = false
                }) { Text("Save") }
            },
            dismissButton = {
                Row {
                    if (allowance != null) {
                        TextButton(onClick = {
                            onRemoveAllowance(app.packageName)
                            showAllowanceDialog = false
                        }) { Text("Revert to Vault", color = MaterialTheme.colorScheme.error) }
                    }
                    TextButton(onClick = { showAllowanceDialog = false }) { Text("Cancel") }
                }
            }
        )
    }
    if (showCategoryDialog) {
        CategoryDialog(app.label, category, { selectedCategory ->
            onSetCategory(app.packageName, selectedCategory)
            showCategoryDialog = false
        }, { showCategoryDialog = false })
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (icon != null) {
                Image(icon, contentDescription = null, modifier = Modifier.padding(bottom = 4.dp))
            }
            Text(app.label, style = MaterialTheme.typography.titleMedium, maxLines = 1)
            Text(if (allowance != null) "$allowance min special" else "General allowance", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { showCategoryDialog = true }) { Text("Category: $category") }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                TextButton(onClick = { showAllowanceDialog = true }) { Text("Set time") }
                Switch(checked = selected, onCheckedChange = { onToggle(app.packageName, it) })
            }
        }
    }
}

@Composable
private fun CategoryDialog(
    appLabel: String,
    currentCategory: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Category for $appLabel") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("Social", "Streaming", "Games", "Other").forEach { category ->
                    FilterChip(
                        selected = currentCategory == category,
                        onClick = { onSelect(category) },
                        label = { Text(category) }
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}
