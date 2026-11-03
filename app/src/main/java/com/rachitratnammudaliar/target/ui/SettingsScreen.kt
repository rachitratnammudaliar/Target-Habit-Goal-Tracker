@file:OptIn(ExperimentalMaterial3Api::class)

package com.rachitratnammudaliar.target.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rachitratnammudaliar.target.health.Health
import kotlinx.coroutines.launch
import java.time.LocalDate

@Composable
fun SettingsScreen(vm: TargetViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val connected by vm.healthConnected.collectAsStateWithLifecycle()
    val syncNote by vm.syncNote.collectAsStateWithLifecycle()
    val status = remember { vm.health.status() }
    val permissions = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { granted ->
        if (granted.containsAll(Health.PERMISSIONS)) {
            vm.syncHealth(30)
            scope.launch { snackbar.showSnackbar("Connected. Reading the last 30 days…") }
        } else {
            vm.refreshHealthStatus()
            scope.launch { snackbar.showSnackbar(if (granted.isEmpty()) "Not connected. You can still log everything by hand." else "Some data wasn't allowed, so some targets can't tick themselves.") }
        }
    }
    LaunchedEffect(Unit) { vm.refreshHealthStatus() }

    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch {
            snackbar.showSnackbar(runCatching { "Backup saved: ${vm.exportTo(uri)} targets" }.getOrElse { "Couldn't save the backup: ${it.message}" })
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            snackbar.showSnackbar(runCatching { "Added ${vm.importFrom(uri)} targets from the backup" }.getOrElse { it.message ?: "Couldn't read the backup" })
        }
    }
    val version = remember {
        runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: ""
    }
    fun open(pkg: String) {
        val launch = context.packageManager.getLaunchIntentForPackage(pkg)
        if (launch != null) context.startActivity(launch) else scope.launch { snackbar.showSnackbar("That app isn't installed on this phone") }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Samsung Health", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    when (status) {
                        Health.Status.NOT_SUPPORTED -> Text("Health Connect isn't available on this phone, so targets are logged by hand.")
                        Health.Status.NEEDS_UPDATE -> {
                            Text("Health Connect needs an update from the Play Store first.")
                            FilledTonalButton(onClick = {
                                runCatching {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("market://details?id=${Health.HEALTH_CONNECT}")))
                                }
                            }) { Text("Update Health Connect") }
                        }
                        Health.Status.AVAILABLE -> {
                            Text(
                                if (connected) "Connected. Steps, workouts, distance and calories fill in your targets automatically. ${syncNote}"
                                else "Let targets like Run 10 km or 10,000 steps tick themselves. Target only reads; it never writes or sends anything.",
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (connected) {
                                    Button(onClick = { vm.syncHealth(30) }) { Text("Sync now") }
                                    OutlinedButton(onClick = { permissions.launch(Health.PERMISSIONS) }) { Text("Permissions") }
                                } else {
                                    Button(onClick = { permissions.launch(Health.PERMISSIONS) }) { Text("Connect") }
                                }
                            }
                            Text(
                                "One-time setup in Samsung Health: open it → ☰ or profile → Settings → Health Connect, and allow Samsung Health to share " +
                                    "steps, exercise, distance and calories. Target reads it from there, on this phone.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { open(Health.SAMSUNG_HEALTH) }) { Text("Open Samsung Health") }
                            }
                        }
                    }
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            SectionLabel("Backup")
            Text("Save your targets and history to a file, and restore them on a new phone, Android or iPhone.", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilledTonalButton(onClick = { exporter.launch("target-backup-${LocalDate.now()}.json") }) { Text("Export") }
                OutlinedButton(onClick = { importer.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }) { Text("Import") }
            }

            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            SectionLabel("Privacy")
            Text(
                "Target works completely offline. It has no internet permission, no account, no ads and no tracking. " +
                    "Your targets and history stay on this phone and are left out of cloud backups. " +
                    "Health data is only read, through Health Connect on this phone, to tick your targets; it's never written, shared or sent anywhere.",
                style = MaterialTheme.typography.bodyMedium,
            )

            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            Text("Target $version · github.com/rachitratnammudaliar/Target-Habit-Goal-Tracker", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
