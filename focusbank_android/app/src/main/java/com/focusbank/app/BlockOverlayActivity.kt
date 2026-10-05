package com.focusbank.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class BlockOverlayActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val packageName = intent.getStringExtra("package_name").orEmpty()
        val isPenalty = intent.getBooleanExtra("is_penalty", false)
        val initialPenalty = intent.getLongExtra("penalty_seconds", 0L)

        // Prevent bypassing the overlay via the system Back button/gesture:
        // Back will navigate safely to the home launcher instead of revealing the blocked app.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(homeIntent)
                finish()
            }
        })

        enableEdgeToEdge()
        setContent {
            FocusBankTheme(getSharedPreferences("focusbank", MODE_PRIVATE).getBoolean("dark_theme", false)) {
                val store = remember { FocusBankStore(getSharedPreferences("focusbank", MODE_PRIVATE), this@BlockOverlayActivity) }
                var remaining by remember { mutableLongStateOf(initialPenalty) }
                var balance by remember { mutableLongStateOf(store.balanceSeconds()) }
                val label = remember { appLabel(this@BlockOverlayActivity, packageName) }
                var tasks by remember { mutableStateOf<List<FocusTask>>(emptyList()) }
                LaunchedEffect(Unit) { tasks = withContext(Dispatchers.IO) { store.tasks() } }
                val penaltyTask = tasks.firstOrNull { it.id == store.penaltyTask(packageName) }
                // Spec §7.1: spotlight the most important open task, soonest deadline first.
                val spotlight = tasks.filter { !it.completed && !it.isPlan && !TaskRules.isLate(it, System.currentTimeMillis()) }
                    .minWithOrNull(compareBy({ TaskRules.priorityRank(it.priority) }, { it.deadlineMillis }))

                LaunchedEffect(Unit) {
                    while (true) {
                        if (isPenalty) {
                            remaining = store.penaltyRemainingSeconds(packageName)
                            balance = store.balanceSeconds()
                        }
                        delay(1_000L)
                    }
                }

                Surface(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize().safeDrawingPadding().padding(28.dp), Arrangement.Center, Alignment.CenterHorizontally) {
                    Text("FocusBank boundary", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(12.dp))
                    Text(if (isPenalty && initialPenalty > 0L) "Penalty Lockout" else "Allowance Exhausted", style = MaterialTheme.typography.headlineMedium)
                    Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(vertical = 10.dp))
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                        Column(Modifier.padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            if (isPenalty && remaining > 0L) {
                                Text(formatCountdown(remaining), style = MaterialTheme.typography.displaySmall)
                                Text("Penalty time left", style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    if (balance <= 0L) "Paused until the Vault has time"
                                    else "Counts down while the Vault has time",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            } else if (isPenalty) {
                                Text("Penalty complete", style = MaterialTheme.typography.displaySmall)
                                Text("Return to FocusBank to check tasks", style = MaterialTheme.typography.bodyMedium)
                            } else {
                                Text("00:00:00", style = MaterialTheme.typography.displaySmall)
                                Text("Assigned free time consumed", style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                    Text(
                        if (isPenalty) "This app is locked due to a missed deadline${penaltyTask?.let { ": ${it.title}" } ?: "."}"
                        else "Complete tasks in FocusBank to earn more free time.",
                        Modifier.padding(top = 18.dp, bottom = 8.dp),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (!isPenalty && spotlight != null) {
                        Card(Modifier.padding(bottom = 18.dp)) {
                            Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("NEXT UP", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                Text(spotlight.title, style = MaterialTheme.typography.titleMedium)
                                Text("Complete this task now to earn ${spotlight.rewardMinutes}m", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    } else {
                        Spacer(Modifier.height(10.dp))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = {
                            startActivity(Intent(this@BlockOverlayActivity, MainActivity::class.java))
                            finish()
                        }) { Text("Open FocusBank") }
                        OutlinedButton(onClick = {
                            val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                                addCategory(Intent.CATEGORY_HOME)
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            }
                            startActivity(homeIntent)
                            finish()
                        }) { Text("Exit to Home") }
                    }
                }
                }
            }
        }
    }

    private fun formatCountdown(seconds: Long): String {
        return "%02d:%02d:%02d".format(seconds / 3600L, (seconds % 3600L) / 60L, seconds % 60L)
    }
}
