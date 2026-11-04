@file:OptIn(ExperimentalMaterial3Api::class)

package com.rachitratnammudaliar.target.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rachitratnammudaliar.target.data.Habit
import com.rachitratnammudaliar.target.data.goal
import com.rachitratnammudaliar.target.data.healthValue
import com.rachitratnammudaliar.target.data.kindValue
import com.rachitratnammudaliar.target.domain.Amounts
import com.rachitratnammudaliar.target.domain.Days
import com.rachitratnammudaliar.target.domain.Goals
import com.rachitratnammudaliar.target.domain.HealthSource
import com.rachitratnammudaliar.target.domain.Kind
import com.rachitratnammudaliar.target.domain.LogType
import com.rachitratnammudaliar.target.domain.Result
import com.rachitratnammudaliar.target.domain.Times
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime

/* ============================ Shared ============================ */

fun CoroutineScope.undoSnackbar(host: SnackbarHostState, message: String, onUndo: (() -> Unit)?) {
    launch {
        host.currentSnackbarData?.dismiss()
        val result = host.showSnackbar(message, actionLabel = if (onUndo != null) "Undo" else null, duration = SnackbarDuration.Short)
        if (result == SnackbarResult.ActionPerformed) onUndo?.invoke()
    }
}

/** "Today", "Yesterday", or "Monday, 2 Nov". */
fun dayLabel(day: LocalDate, today: LocalDate): String = when (day) {
    today -> "Today"
    today.minusDays(1) -> "Yesterday"
    today.plusDays(1) -> "Tomorrow"
    else -> day.format(dayTitle)
}

/** Heatmap columns: [weeks] weeks up to this one, Monday first; days after today are empty. */
fun heatWeeks(target: Habit, state: TargetState, today: LocalDate, weeks: Int): List<List<Result?>> {
    val goal = target.goal()
    val byDay = state.logsByDay(target.id)
    val first = Goals.weekStart(today).minusWeeks((weeks - 1).toLong())
    return (0 until weeks).map { w ->
        (0 until 7).map { d ->
            val day = first.plusWeeks(w.toLong()).plusDays(d.toLong())
            if (day.isAfter(today)) null else Goals.result(goal, byDay[day].orEmpty(), day, today)
        }
    }
}

/**
 * @param startScreen "today", "progress", "settings", "detail:ID" or "edit:ID". Only debug builds
 * pass anything but "today" (for screenshots).
 */
@Composable
fun TargetRoot(startScreen: String = "today", demo: Boolean = false, vm: TargetViewModel = viewModel()) {
    var route by rememberSaveable { mutableStateOf(startScreen) }
    var tab by rememberSaveable { mutableStateOf(if (startScreen == "progress") "progress" else "today") }
    var today by remember { mutableStateOf(LocalDate.now()) }
    var dayEpoch by rememberSaveable { mutableLongStateOf(LocalDate.now().toEpochDay()) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val now = LocalDate.now()
                if (now != today) {
                    // A new day: jump to it if you were looking at the old today.
                    if (dayEpoch == today.toEpochDay()) dayEpoch = now.toEpochDay()
                    today = now
                }
                vm.syncHealth()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(today) {
        val now = LocalDateTime.now()
        delay(Duration.between(now, now.toLocalDate().plusDays(1).atStartOfDay()).toMillis() + 1_000)
        if (dayEpoch == today.toEpochDay()) dayEpoch = LocalDate.now().toEpochDay()
        today = LocalDate.now()
    }
    LaunchedEffect(Unit) { if (demo) vm.seedDemo() }

    val state by vm.state.collectAsStateWithLifecycle()
    val parts = route.split(":")
    val id = parts.getOrNull(1)?.toLongOrNull() ?: 0L
    BackHandler(enabled = route != tab) {
        route = if (parts[0] == "edit" && id != 0L) "detail:$id" else tab
    }

    when (parts[0]) {
        "detail" -> DetailScreen(vm, state, id, today, onBack = { route = tab }, onEdit = { route = "edit:$id" })
        "edit" -> EditScreen(vm, state, id, onDone = { saved -> route = if (saved != 0L && id != 0L) "detail:$saved" else tab })
        "settings" -> SettingsScreen(vm, onBack = { route = tab })
        else -> MainTabs(
            vm = vm, state = state, tab = tab, today = today, day = LocalDate.ofEpochDay(dayEpoch).let { if (it.isAfter(today)) today else it },
            onDay = { dayEpoch = it.toEpochDay() },
            onTab = { tab = it; route = it },
            onOpen = { route = "detail:$it" },
            onAdd = { route = "edit:0" },
            onSettings = { route = "settings" },
        )
    }
}

@Composable
private fun MainTabs(
    vm: TargetViewModel,
    state: TargetState?,
    tab: String,
    today: LocalDate,
    day: LocalDate,
    onDay: (LocalDate) -> Unit,
    onTab: (String) -> Unit,
    onOpen: (Long) -> Unit,
    onAdd: () -> Unit,
    onSettings: () -> Unit,
) {
    val snackbar = remember { SnackbarHostState() }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (tab == "progress") "Progress" else "Target", fontWeight = FontWeight.SemiBold) },
                actions = { IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, contentDescription = "Settings") } },
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(selected = tab == "today", onClick = { onTab("today") }, icon = { Icon(Icons.Default.Home, null) }, label = { Text("Today") })
                NavigationBarItem(selected = tab == "progress", onClick = { onTab("progress") }, icon = { Icon(Icons.Default.DateRange, null) }, label = { Text("Progress") })
            }
        },
        floatingActionButton = {
            if (tab == "today") FloatingActionButton(onClick = onAdd) { Icon(Icons.Default.Add, contentDescription = "Add a target") }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val s = state ?: return@Scaffold
        val modifier = Modifier.fillMaxSize().padding(padding)
        if (tab == "progress") ProgressScreen(s, today, onOpen, modifier) else TodayScreen(vm, s, today, day, onDay, onOpen, onAdd, onSettings, snackbar, modifier)
    }
}

/* ============================ Today ============================ */

@Composable
private fun TodayScreen(
    vm: TargetViewModel,
    state: TargetState,
    today: LocalDate,
    day: LocalDate,
    onDay: (LocalDate) -> Unit,
    onOpen: (Long) -> Unit,
    onAdd: () -> Unit,
    onSettings: () -> Unit,
    snackbar: SnackbarHostState,
    modifier: Modifier,
) {
    val syncNote by vm.syncNote.collectAsStateWithLifecycle()
    val connected by vm.healthConnected.collectAsStateWithLifecycle()
    if (state.active.isEmpty()) {
        Column(modifier.padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text("🎯", style = MaterialTheme.typography.displayMedium)
            Spacer(Modifier.height(8.dp))
            Text("Set your first target", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text(
                "Things you want to hit every day: wake up by 7:00, at the library by 9:00, run 5 km, 10,000 steps. Log them with a tap and watch your streaks grow.",
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))
            Button(onClick = onAdd) { Text("Add a target") }
        }
        return
    }
    val due = state.active.filter { Goals.isScheduled(it.goal(), day) }
    val rest = state.active.filter { !Goals.isScheduled(it.goal(), day) }
    val score = Goals.dayScore(state.goals, day, today)
    val streaks = state.active.map { it to Goals.currentStreak(it.goal(), state.logsByDay(it.id), today) }
    val bestNow = streaks.maxByOrNull { it.second }?.takeIf { it.second >= 2 }

    LazyColumn(modifier, contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item(key = "day") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { onDay(day.minusDays(1)) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Day before") }
                Text(dayLabel(day, today), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                IconButton(onClick = { onDay(day.plusDays(1)) }, enabled = day.isBefore(today)) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next day") }
            }
        }
        item(key = "summary") {
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                val on = MaterialTheme.colorScheme.onPrimaryContainer
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    ProgressRing(score.fraction.toFloat()) {
                        Text("${score.done}/${score.scheduled}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = on)
                    }
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            when {
                                score.scheduled == 0 -> "Rest day"
                                score.done == score.scheduled -> "All targets hit 🎉"
                                day == today -> "${score.scheduled - score.done} to go today"
                                else -> "${score.done} of ${score.scheduled} hit"
                            },
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = on,
                        )
                        if (bestNow != null) Text("🔥 ${bestNow.second}-day streak: ${bestNow.first.name}", style = MaterialTheme.typography.bodyMedium, color = on)
                        if (state.usesHealth) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    if (connected) "Samsung Health · ${syncNote.ifEmpty { "syncing…" }}" else "Samsung Health not connected",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = on,
                                    modifier = Modifier.weight(1f, fill = false),
                                )
                                IconButton(onClick = { if (connected) vm.syncHealth() else onSettings() }, modifier = Modifier.size(32.dp)) {
                                    Icon(if (connected) Icons.Default.Refresh else Icons.Default.Settings, contentDescription = if (connected) "Sync now" else "Connect", tint = on, modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
        items(due, key = { it.id }) { t -> TargetCard(vm, state, t, day, today, snackbar, onOpen) }
        if (rest.isNotEmpty()) {
            item(key = "rest") {
                Text(
                    "Not today: " + rest.joinToString { "${it.emoji} ${it.name}" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp, start = 4.dp),
                )
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.items(list: List<Habit>, key: (Habit) -> Any, content: @Composable (Habit) -> Unit) {
    list.forEach { t -> item(key = key(t)) { content(t) } }
}

@Composable
private fun TargetCard(vm: TargetViewModel, state: TargetState, t: Habit, day: LocalDate, today: LocalDate, snackbar: SnackbarHostState, onOpen: (Long) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val goal = t.goal()
    val logs = state.logs(t.id, day)
    val byDay = state.logsByDay(t.id)
    val result = Goals.result(goal, logs, day, today)
    val streak = Goals.currentStreak(goal, byDay, today)
    val kind = t.kindValue()
    val target = Goals.targetTime(goal, logs, day)
    val overdue = Goals.isOverdue(goal, logs, day, today, vm.nowMinutes())
    val note = state.entries(t.id, day).firstOrNull { it.type == LogType.NOTE.name }?.note
    val movedToday = logs.any { it.type == LogType.TARGET } && Goals.time(logs) == null
    val tint = when {
        overdue -> resultColor(Result.LATE)
        result == Result.PENDING -> MaterialTheme.colorScheme.primary
        else -> resultColor(result)
    }
    var menu by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    var noting by remember { mutableStateOf(false) }
    var pausing by remember { mutableStateOf(false) }

    if (adding) {
        AmountDialog(t, onDismiss = { adding = false }) { amount ->
            adding = false
            vm.addAmount(t, day, amount) { id -> scope.undoSnackbar(snackbar, "Added ${Amounts.withUnit(amount, t.unit)} to ${t.name}") { vm.deleteEntry(id) } }
        }
    }
    if (noting) NoteDialog(t, note.orEmpty(), onDismiss = { noting = false }) { text -> noting = false; vm.setNote(t, day, text) }
    if (pausing) PauseDialog(t, today, onDismiss = { pausing = false }) { to -> pausing = false; vm.pause(t, today, to) }

    fun logNow() {
        val (d, m) = Goals.nightAdjust(goal, today, vm.nowMinutes(), state.logs(t.id, today.minusDays(1)))
        vm.logTime(t, d, m) { id ->
            scope.undoSnackbar(snackbar, if (d != today) "${t.name} logged for last night" else "${t.name} logged") { vm.deleteEntry(id) }
        }
    }

    Card(
        onClick = { onOpen(t.id) },
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            EmojiBadge(t.emoji, tint)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(t.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val weekCount = if (goal.weekly) Goals.timesInWeek(goal, byDay, Goals.weekStart(day), today) else 0
                val status = when {
                    Goals.isPaused(goal, day) -> "Paused until ${LocalDate.ofEpochDay(t.pauseEnd).format(shortDate)}"
                    result == Result.SKIPPED -> "Skipped"
                    kind == Kind.BY_TIME && Goals.time(logs) != null ->
                        (if (result == Result.DONE) "Done at " else "Late: ") + Times.format(Goals.time(logs)!!) + " · target ${Times.format(target)}"
                    overdue -> "Overdue · target ${Times.format(target)}"
                    kind == Kind.BY_TIME -> "By ${Times.format(target)}" + (if (movedToday) " today" else "") + if (result == Result.MISSED) " · missed" else ""
                    kind == Kind.AMOUNT -> Goals.progressText(goal, logs, t.unit) + if (goal.weekly) " · $weekCount/${goal.weeklyTimes} this wk" else ""
                    goal.weekly -> (if (result == Result.DONE) "Done · " else "") + "$weekCount of ${goal.weeklyTimes} this week"
                    result == Result.DONE -> "Done"
                    result == Result.MISSED -> "Missed"
                    else -> "Not done yet"
                }
                Text(
                    status + if (streak >= 2) "  ·  🔥 $streak${if (goal.weekly) " wk" else ""}" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = when {
                        overdue -> resultColor(Result.LATE)
                        result == Result.PENDING || result == Result.SKIPPED -> MaterialTheme.colorScheme.onSurfaceVariant
                        else -> resultColor(result)
                    },
                    fontWeight = if (result == Result.DONE || overdue) FontWeight.Medium else null,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (kind == Kind.AMOUNT && result != Result.SKIPPED) {
                    LinearProgressIndicator(
                        progress = { Goals.progress(goal, logs).toFloat() },
                        color = tint,
                        modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                    )
                    if (t.healthValue() != HealthSource.NONE) Text("From Samsung Health", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (note != null) Text("📝 $note", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(8.dp))
            if (result != Result.SKIPPED) {
                when (kind) {
                    Kind.CHECK -> {
                        val done = result == Result.DONE
                        val toggle = {
                            vm.toggleDone(t, day) { nowDone, undo ->
                                scope.undoSnackbar(snackbar, if (nowDone) "${t.name}: done ✓" else "${t.name}: unticked", undo)
                            }
                        }
                        if (done) {
                            FilledIconButton(onClick = toggle, colors = IconButtonDefaults.filledIconButtonColors(containerColor = resultColor(Result.DONE))) {
                                Icon(Icons.Default.Check, contentDescription = "Done. Tap to undo")
                            }
                        } else {
                            OutlinedIconButton(onClick = toggle) { Icon(Icons.Default.Check, contentDescription = "Mark done") }
                        }
                    }
                    Kind.BY_TIME -> {
                        if (Goals.time(logs) == null) {
                            if (day == today) {
                                FilledTonalButton(onClick = { logNow() }, contentPadding = PaddingValues(horizontal = 14.dp)) { Text("✓ Now") }
                            } else {
                                FilledTonalButton(onClick = { pickTime(context, target) { vm.logTime(t, day, it) } }, contentPadding = PaddingValues(horizontal = 14.dp)) { Text("Time…") }
                            }
                        }
                    }
                    Kind.AMOUNT -> FilledIconButton(onClick = {
                        vm.addAmount(t, day, t.step) { id -> scope.undoSnackbar(snackbar, "Added ${Amounts.withUnit(t.step, t.unit)} to ${t.name}") { vm.deleteEntry(id) } }
                    }) { Icon(Icons.Default.Add, contentDescription = "Add ${Amounts.withUnit(t.step, t.unit)}") }
                }
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "More") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (kind == Kind.BY_TIME) {
                        DropdownMenuItem(text = { Text(if (Goals.time(logs) == null) "I did it at…" else "Change the time I did it…") }, onClick = {
                            menu = false
                            pickTime(context, Goals.time(logs)?.let { it % (24 * 60) } ?: target) { vm.logTime(t, day, it) }
                        })
                        if (Goals.time(logs) == null) {
                            DropdownMenuItem(text = { Text(if (day == today) "Change today's target time…" else "Change this day's target time…") }, onClick = {
                                menu = false
                                pickTime(context, target) { vm.setDayTarget(t, day, it) }
                            })
                            if (movedToday) DropdownMenuItem(text = { Text("Back to the usual time") }, onClick = { menu = false; vm.setDayTarget(t, day, null) })
                        }
                    }
                    if (kind == Kind.AMOUNT) DropdownMenuItem(text = { Text("Add an amount…") }, onClick = { menu = false; adding = true })
                    DropdownMenuItem(text = { Text(if (note == null) "Add a note…" else "Edit the note…") }, onClick = { menu = false; noting = true })
                    if (!Goals.isPaused(goal, day)) {
                        DropdownMenuItem(text = { Text(if (logs.any { it.type == LogType.SKIP }) "Don't skip" else "Skip this day") }, onClick = {
                            menu = false
                            vm.toggleSkip(t, day)
                        })
                    }
                    if (Goals.isPaused(goal, today)) {
                        DropdownMenuItem(text = { Text("Resume now") }, onClick = { menu = false; vm.resume(t, today) })
                    } else {
                        DropdownMenuItem(text = { Text("Pause for a while…") }, onClick = { menu = false; pausing = true })
                    }
                    if (logs.any { it.type == LogType.DONE || it.type == LogType.TIME || it.type == LogType.AMOUNT }) {
                        DropdownMenuItem(text = { Text("Clear what I logged") }, onClick = { menu = false; vm.clearDay(t, day) })
                    }
                    DropdownMenuItem(text = { Text("Move up") }, onClick = { menu = false; vm.move(state, t, -1) })
                    DropdownMenuItem(text = { Text("Move down") }, onClick = { menu = false; vm.move(state, t, 1) })
                    DropdownMenuItem(text = { Text("Details and charts") }, onClick = { menu = false; onOpen(t.id) })
                }
            }
        }
    }
}

@Composable
fun NoteDialog(t: Habit, current: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${t.emoji}  Note") },
        text = {
            androidx.compose.material3.OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(300) },
                placeholder = { Text("Exam week, class at 9, felt great…") },
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { Button(onClick = { onSave(text) }) { Text(if (text.isBlank() && current.isNotBlank()) "Remove" else "Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Pause a target: the days in between count as skipped, so the streak is safe. */
@Composable
fun PauseDialog(t: Habit, today: LocalDate, onDismiss: () -> Unit, onPause: (LocalDate) -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pause ${t.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Hint("For holidays, illness or exams. Paused days don't count and don't break your streak.")
                listOf(3L to "3 days", 7L to "1 week", 14L to "2 weeks", 30L to "1 month").forEach { (days, label) ->
                    androidx.compose.material3.OutlinedButton(onClick = { onPause(today.plusDays(days - 1)) }, modifier = Modifier.fillMaxWidth()) {
                        Text("$label · until ${today.plusDays(days - 1).format(shortDate)}")
                    }
                }
                androidx.compose.material3.OutlinedButton(onClick = { pickDate(context, today.plusDays(7), min = today) { onPause(it) } }, modifier = Modifier.fillMaxWidth()) {
                    Text("Until a date…")
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun AmountDialog(t: Habit, onDismiss: () -> Unit, onAdd: (Double) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    val value = Amounts.parse(text)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${t.emoji}  ${t.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField(text, { text = it }, "How much${if (t.unit.isNotBlank()) " (${t.unit})" else ""}", isError = text.isNotBlank() && value == null, error = "A number above 0")
                Hint("Added to what's already logged for the day.")
            }
        },
        confirmButton = { Button(onClick = { value?.let(onAdd) }, enabled = value != null) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/* ============================ Progress ============================ */

@Composable
private fun ProgressScreen(state: TargetState, today: LocalDate, onOpen: (Long) -> Unit, modifier: Modifier) {
    var range by rememberSaveable { mutableStateOf(7) }
    if (state.active.isEmpty()) {
        Text("Add a target on Today and your progress shows up here.", color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = modifier.padding(32.dp))
        return
    }
    val from = today.minusDays((range - 1).toLong())
    val days = (0 until range).map { from.plusDays(it.toLong()) }
    val scores = days.map { Goals.dayScore(state.goals, it, today) }
    val rates = state.active.map { it to Goals.hitRate(it.goal(), state.logsByDay(it.id), from, today, today) }
    val done = rates.sumOf { it.second.done }
    val counted = rates.sumOf { it.second.counted }
    val primary = MaterialTheme.colorScheme.primary
    val doneColor = resultColor(Result.DONE)

    LazyColumn(modifier, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item(key = "range") { ChipRow(listOf(7, 30, 90), range, { "$it days" }, { range = it }) }
        item(key = "overall") {
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                val on = MaterialTheme.colorScheme.onPrimaryContainer
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Targets hit, last $range days", style = MaterialTheme.typography.labelLarge, color = on)
                    Text(if (counted == 0) "–" else "${Math.round(done * 100.0 / counted)}%", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.SemiBold, color = on)
                    Text("$done of $counted", style = MaterialTheme.typography.bodyMedium, color = on)
                    if (range <= 30) {
                        BarChart(
                            values = scores.map { if (it.scheduled == 0) 0.0 else it.fraction },
                            labels = days.map { if (range == 7) Days.short(it.dayOfWeek).take(2) else it.dayOfMonth.toString() },
                            colors = scores.map { if (it.scheduled > 0 && it.done == it.scheduled) doneColor else primary },
                            max = 1.0,
                            labelEvery = if (range == 7) 1 else 5,
                        )
                    } else {
                        // 90 days: one bar per week.
                        val weeks = scores.chunked(7)
                        BarChart(
                            values = weeks.map { w -> w.sumOf { it.done }.toDouble() / w.sumOf { it.scheduled }.coerceAtLeast(1) },
                            labels = weeks.map { it.first().day.format(shortDate) },
                            colors = weeks.map { primary },
                            max = 1.0,
                            labelEvery = 3,
                        )
                    }
                    Hint("Each bar is the share of the day's targets you hit. Green: all of them.")
                }
            }
        }
        item(key = "month") {
            val month = remember(state, today) { Goals.month(state.active.map { it.goal() to state.logsByDay(it.id) }, today) }
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(today.format(java.time.format.DateTimeFormatter.ofPattern("MMMM")) + " so far", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (month.counted == 0) "Nothing counted yet this month."
                        else "${month.done} of ${month.counted} targets hit · ${Math.round(month.done * 100.0 / month.counted)}%",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    month.bestStreak?.let { (i, n) ->
                        val t = state.active[i]
                        Text("🔥 Longest streak: ${t.emoji} ${t.name}, $n ${if (t.goal().weekly) "wk" else if (n == 1) "day" else "days"}", style = MaterialTheme.typography.bodyMedium)
                    }
                    month.mostImproved?.let { (i, pts) ->
                        val t = state.active[i]
                        Text("📈 Most improved: ${t.emoji} ${t.name}, up $pts points on last month", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
        item(key = "targets-title") { SectionLabel("Each target") }
        rates.forEach { (t, rate) ->
            item(key = "t-${t.id}") {
                val streak = Goals.currentStreak(t.goal(), state.logsByDay(t.id), today)
                Card(onClick = { onOpen(t.id) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            EmojiBadge(t.emoji, primary, 40.dp)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(t.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                Text(Goals.goalText(t.goal(), t.unit) + if (t.goal().weekly) "" else " · " + Days.label(t.days), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(rate.percent?.let { "$it%" } ?: "–", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                Text(if (streak > 0) "🔥 $streak" else "${rate.done}/${rate.counted}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Heatmap(heatWeeks(t, state, today, 12), cell = 12.dp)
                    }
                }
            }
        }
        item(key = "key") { HeatmapKey() }
    }
}

/* ============================ Detail ============================ */

@Composable
private fun DetailScreen(vm: TargetViewModel, state: TargetState?, id: Long, today: LocalDate, onBack: () -> Unit, onEdit: () -> Unit) {
    val t = state?.target(id)
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var pausing by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(t?.name ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                actions = {
                    TextButton(onClick = onEdit, enabled = t != null) { Text("Edit") }
                    Box {
                        IconButton(onClick = { menu = true }, enabled = t != null) { Icon(Icons.Default.MoreVert, contentDescription = "More") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            if (t != null) {
                                if (t.goal().let { Goals.isPaused(it, today) }) {
                                    DropdownMenuItem(text = { Text("Resume now") }, onClick = { menu = false; vm.resume(t, today) })
                                } else {
                                    DropdownMenuItem(text = { Text("Pause for a while…") }, onClick = { menu = false; pausing = true })
                                }
                                DropdownMenuItem(text = { Text(if (t.archived) "Bring back" else "Stop tracking (keep history)") }, onClick = { menu = false; vm.setArchived(t, !t.archived) })
                                DropdownMenuItem(text = { Text("Clear this target's history…") }, onClick = { menu = false; confirmClear = true })
                                DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; confirmDelete = true })
                            }
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (state == null) return@Scaffold
        if (t == null) {
            Text("This target was deleted.", modifier = Modifier.padding(padding).padding(24.dp))
            return@Scaffold
        }
        val goal = t.goal()
        val byDay = state.logsByDay(t.id)
        val month = Goals.hitRate(goal, byDay, today.minusDays(29), today, today)
        val all = Goals.hitRate(goal, byDay, goal.startDay, today, today)
        val days = (29 downTo 0).map { today.minusDays(it.toLong()) }
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item(key = "head") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    EmojiBadge(t.emoji, MaterialTheme.colorScheme.primary, 56.dp)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(t.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        Text(Goals.goalText(goal, t.unit) + " · " + Days.label(t.days), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (t.healthValue() != HealthSource.NONE) Hint("Ticks itself from Samsung Health: ${t.healthValue().label.lowercase()}")
                        if (t.archived) Hint("Not tracking any more. Use ⋮ → Bring back to start again.")
                    }
                }
            }
            item(key = "tiles") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatTile("🔥 ${Goals.currentStreak(goal, byDay, today)}", "streak now", Modifier.weight(1f))
                    StatTile("${Goals.bestStreak(goal, byDay, today)}", "best streak", Modifier.weight(1f))
                    StatTile(month.percent?.let { "$it%" } ?: "–", "last 30 days", Modifier.weight(1f))
                    StatTile("${all.done}", "times hit", Modifier.weight(1f))
                }
            }
            item(key = "heat") {
                Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        SectionLabel("Last 17 weeks")
                        Heatmap(heatWeeks(t, state, today, 17))
                        HeatmapKey()
                    }
                }
            }
            item(key = "chart") {
                Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        val labels = days.map { it.format(shortDate) }
                        when (t.kindValue()) {
                            Kind.BY_TIME -> {
                                SectionLabel("Time you logged, last 30 days")
                                TimeChart(
                                    days.map { d -> Goals.time(byDay[d].orEmpty()).takeIf { Goals.isScheduled(goal, d) } },
                                    labels,
                                    days.map { d -> Goals.targetTime(goal, byDay[d].orEmpty(), d) },
                                )
                                Hint("Dashed line: each day's target time. Green dots were on time.")
                            }
                            Kind.AMOUNT -> {
                                SectionLabel("${t.unit.ifBlank { "Amount" }.replaceFirstChar { it.uppercase() }}, last 30 days")
                                val values = days.map { Goals.amount(byDay[it].orEmpty()) }
                                BarChart(
                                    values = values,
                                    labels = labels,
                                    colors = values.map { if (it >= t.goalValue) resultColor(Result.DONE) else MaterialTheme.colorScheme.primary },
                                    max = maxOf(t.goalValue * 1.2, values.maxOrNull() ?: 0.0),
                                    goal = t.goalValue,
                                    labelEvery = 7,
                                )
                                Hint("Dashed line: your target of ${Amounts.withUnit(t.goalValue, t.unit)}. Green: reached.")
                            }
                            Kind.CHECK -> {
                                SectionLabel("Done each week, last 12 weeks")
                                val weeks = (11 downTo 0).map { Goals.weekStart(today).minusWeeks(it.toLong()) }
                                if (goal.weekly) {
                                    val counts = weeks.map { w -> Goals.timesInWeek(goal, byDay, w, today) }
                                    BarChart(
                                        values = counts.map { it.toDouble() },
                                        labels = weeks.map { it.format(shortDate) },
                                        colors = counts.map { if (it >= goal.weeklyTimes) resultColor(Result.DONE) else MaterialTheme.colorScheme.primary },
                                        max = 7.0,
                                        goal = goal.weeklyTimes.toDouble(),
                                        labelEvery = 3,
                                    )
                                    Hint("Dashed line: ${goal.weeklyTimes} times a week. Green: reached.")
                                } else {
                                    val rates = weeks.map { w -> Goals.hitRate(goal, byDay, w, w.plusDays(6), today) }
                                    BarChart(
                                        values = rates.map { it.done.toDouble() },
                                        labels = weeks.map { it.format(shortDate) },
                                        colors = rates.map { if (it.counted > 0 && it.done == it.counted) resultColor(Result.DONE) else MaterialTheme.colorScheme.primary },
                                        max = 7.0,
                                        labelEvery = 3,
                                    )
                                    Hint("Green: every planned day that week.")
                                }
                            }
                        }
                    }
                }
            }
            item(key = "history-title") { SectionLabel("History") }
            // The day's saved target time is shown with the time logged, not as its own row.
            val history = state.entries(t.id).filter { (it.type != LogType.HEALTH.name || it.value > 0) && it.type != LogType.TARGET.name }.take(60)
            if (history.isEmpty()) item(key = "none") { Hint("Nothing logged yet.") }
            history.forEach { e ->
                item(key = "e-${e.id}") {
                    val type = LogType.fromName(e.type)
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                when (type) {
                                    LogType.DONE -> "Done"
                                    LogType.TIME -> {
                                        val dayTarget = Goals.targetTime(t.goal(), state.logs(t.id, LocalDate.ofEpochDay(e.epochDay)), LocalDate.ofEpochDay(e.epochDay))
                                        "At ${Times.format(e.value.toInt())}" + (if (e.value.toInt() <= dayTarget) " ✓" else " (late)") + " · target ${Times.format(dayTarget)}"
                                    }
                                    LogType.TARGET -> "Target time ${Times.format(e.value.toInt())}"
                                    LogType.NOTE -> "📝 ${e.note}"
                                    LogType.AMOUNT -> "+ ${Amounts.withUnit(e.value, t.unit)}"
                                    LogType.HEALTH -> "Samsung Health: ${Amounts.withUnit(e.value, t.unit)}"
                                    LogType.SKIP -> "Skipped"
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(dayLabel(LocalDate.ofEpochDay(e.epochDay), today), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (type != LogType.HEALTH) TextButton(onClick = { vm.deleteEntry(e.id) }) { Text("Remove") }
                    }
                }
            }
        }
    }
    if (pausing && t != null) PauseDialog(t, today, onDismiss = { pausing = false }) { to -> pausing = false; vm.pause(t, today, to) }
    if (confirmClear && t != null) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear ${t.name}'s history?") },
            text = { Text("Everything logged for this target is deleted, and its streaks start again. The target itself stays. Export a backup first in Settings if you might want it back.") },
            confirmButton = { TextButton(onClick = { confirmClear = false; vm.clearHistory(t) {} }) { Text("Clear history") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
    if (confirmDelete && t != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${t.name}?") },
            text = { Text("This deletes the target and its whole history. To stop tracking but keep the history, choose Stop tracking instead.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; vm.deleteTarget(t); onBack() }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}
