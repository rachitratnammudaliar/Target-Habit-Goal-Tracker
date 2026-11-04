@file:OptIn(ExperimentalMaterial3Api::class)

package com.rachitratnammudaliar.target.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import com.rachitratnammudaliar.target.data.Habit
import com.rachitratnammudaliar.target.domain.Amounts
import com.rachitratnammudaliar.target.domain.DayTimes
import com.rachitratnammudaliar.target.domain.Days
import com.rachitratnammudaliar.target.domain.HealthSource
import com.rachitratnammudaliar.target.domain.Kind
import com.rachitratnammudaliar.target.domain.Templates
import com.rachitratnammudaliar.target.domain.Times
import com.rachitratnammudaliar.target.health.Health
import java.time.DayOfWeek
import java.time.LocalDate

private val UNITS = listOf("km", "min", "steps", "kcal", "pages", "glasses", "times")

@Composable
fun EditScreen(vm: TargetViewModel, state: TargetState?, id: Long, onDone: (savedId: Long) -> Unit) {
    val context = LocalContext.current
    val isNew = id == 0L
    val existing = state?.target(id)
    val healthAvailable = vm.health.status() == Health.Status.AVAILABLE

    var loaded by rememberSaveable { mutableStateOf(isNew) }
    var name by rememberSaveable { mutableStateOf("") }
    var emoji by rememberSaveable { mutableStateOf("🎯") }
    var kind by rememberSaveable { mutableStateOf(Kind.CHECK.name) }
    var time by rememberSaveable { mutableIntStateOf(7 * 60) }
    var goalText by rememberSaveable { mutableStateOf("") }
    var unit by rememberSaveable { mutableStateOf("") }
    var stepText by rememberSaveable { mutableStateOf("") }
    var days by rememberSaveable { mutableIntStateOf(Days.ALL) }
    var health by rememberSaveable { mutableStateOf(HealthSource.NONE.name) }
    var notes by rememberSaveable { mutableStateOf("") }
    var dayTimes by rememberSaveable { mutableStateOf("") }
    var weekly by rememberSaveable { mutableIntStateOf(0) }
    var start by rememberSaveable { mutableStateOf(LocalDate.now().toEpochDay()) }
    var showErrors by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(existing?.id) {
        if (!loaded && existing != null) {
            name = existing.name
            emoji = existing.emoji
            kind = existing.kind
            time = existing.timeMinutes
            goalText = Amounts.format(existing.goalValue)
            unit = existing.unit
            stepText = Amounts.format(existing.step)
            days = existing.days
            health = existing.health
            notes = existing.notes
            dayTimes = existing.dayTimes
            weekly = existing.weeklyTimes
            start = existing.startDay
            loaded = true
        }
    }

    val kindValue = Kind.fromName(kind)
    val healthValue = HealthSource.fromName(health)
    val goalValue = Amounts.parse(goalText)
    val stepValue = if (stepText.isBlank()) (if (healthValue != HealthSource.NONE) healthValue.step else 1.0) else Amounts.parse(stepText)
    val weeklyOn = weekly > 0 && kindValue != Kind.BY_TIME
    val times = DayTimes.parse(dayTimes)
    val invalid = name.isBlank() || (days == 0 && !weeklyOn) || (kindValue == Kind.AMOUNT && (goalValue == null || stepValue == null))

    fun save() {
        if (invalid) {
            showErrors = true
            return
        }
        val base = existing ?: Habit(name = "", startDay = LocalDate.now().toEpochDay())
        vm.saveTarget(
            base.copy(
                name = name.trim().take(60),
                emoji = emoji,
                kind = kind,
                timeMinutes = time,
                goalValue = if (kindValue == Kind.AMOUNT) goalValue ?: 1.0 else base.goalValue,
                unit = unit.trim().take(20),
                step = stepValue ?: 1.0,
                days = if (days == 0) Days.ALL else days,
                dayTimes = if (kindValue == Kind.BY_TIME) DayTimes.format(times.filterKeys { Days.has(days, DayOfWeek.of(it)) }) else "",
                weeklyTimes = if (weeklyOn) weekly.coerceIn(1, 7) else 0,
                startDay = start,
                health = if (kindValue == Kind.AMOUNT) health else HealthSource.NONE.name,
                notes = notes.trim().take(500),
            ),
        ) { onDone(it) }
    }

    fun useTemplate(t: com.rachitratnammudaliar.target.domain.Template) {
        name = t.name
        emoji = t.emoji
        kind = t.kind.name
        time = t.timeMinutes.takeIf { t.kind == Kind.BY_TIME } ?: time
        goalText = if (t.kind == Kind.AMOUNT) Amounts.format(t.goalValue) else ""
        unit = t.unit
        stepText = ""
        days = t.days
        weekly = t.weeklyTimes
        dayTimes = ""
        health = if (healthAvailable) t.health.name else HealthSource.NONE.name
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isNew) "New target" else "Edit target") },
                navigationIcon = { IconButton(onClick = { onDone(0) }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                actions = { TextButton(onClick = { save() }) { Text("Save") } },
            )
        },
    ) { padding ->
        if (!loaded) return@Scaffold
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (isNew) {
                SectionLabel("Start from")
                ChipRow(Templates.all, null, { "${it.emoji} ${it.name}" }, { useTemplate(it) })
            }
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(60) },
                label = { Text("Target") },
                placeholder = { Text("Wake up, Run, Library…") },
                singleLine = true,
                isError = showErrors && name.isBlank(),
                supportingText = if (showErrors && name.isBlank()) ({ Text("Give it a name") }) else null,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(),
            )
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Templates.emojis.chunked(8).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        row.forEach { e ->
                            Box(
                                Modifier.size(40.dp).clip(CircleShape)
                                    .background(if (e == emoji) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer)
                                    .clickable { emoji = e },
                                contentAlignment = Alignment.Center,
                            ) { Text(e, style = MaterialTheme.typography.titleMedium) }
                        }
                    }
                }
            }

            SectionLabel("How it's met")
            ChipRow(Kind.entries, kindValue, { it.label }, { kind = it.name })
            Hint(kindValue.description)

            when (kindValue) {
                Kind.BY_TIME -> {
                    OutlinedButton(onClick = { pickTime(context, time) { time = it } }, modifier = Modifier.fillMaxWidth()) {
                        Text("By ${Times.format(time)}")
                    }
                    Hint("Tap ✓ Now when you've done it. On time or earlier counts as hit.")
                }
                Kind.AMOUNT -> {
                    if (healthAvailable) {
                        SectionLabel("Fill in from Samsung Health")
                        ChipRow(HealthSource.entries, healthValue, { it.label }, { h ->
                            health = h.name
                            if (h != HealthSource.NONE) unit = h.unit
                        })
                        Hint(
                            if (healthValue == HealthSource.NONE) "You add to it yourself with ＋."
                            else "Ticks itself when Samsung Health records it. You can still add by hand.",
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        NumberField(goalText, { goalText = it }, "Target amount", isError = showErrors && goalValue == null, error = "A number above 0", modifier = Modifier.weight(1f))
                        OutlinedTextField(
                            value = unit,
                            onValueChange = { unit = it.take(20) },
                            label = { Text("Unit") },
                            singleLine = true,
                            enabled = healthValue == HealthSource.NONE,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (healthValue == HealthSource.NONE) ChipRow(UNITS, unit, { it }, { unit = it })
                    NumberField(stepText, { stepText = it }, "Each ＋ adds", isError = showErrors && stepValue == null, error = "A number above 0")
                    Hint("Leave it empty for ${Amounts.withUnit(if (healthValue != HealthSource.NONE) healthValue.step else 1.0, unit)}.")
                }
                Kind.CHECK -> {}
            }

            if (kindValue != Kind.BY_TIME) {
                SectionLabel("How often")
                ChipRow(listOf(false, true), weeklyOn, { if (it) "Times a week" else "On set days" }, { weekly = if (it) (weekly.takeIf { w -> w > 0 } ?: 3) else 0 })
            }
            if (weeklyOn) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = { weekly = (weekly - 1).coerceAtLeast(1) }, enabled = weekly > 1) { Text("−") }
                    Text("$weekly× a week", style = MaterialTheme.typography.titleMedium)
                    OutlinedButton(onClick = { weekly = (weekly + 1).coerceAtMost(7) }, enabled = weekly < 7) { Text("＋") }
                }
                Hint("Any days you like, Monday to Sunday. Good for runs or the gym when your timetable changes.")
            } else {
            SectionLabel("Which days")
            ChipRow(listOf(Days.ALL, Days.WEEKDAYS, Days.WEEKEND), days, { Days.label(it) }, { days = it })
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                DayOfWeek.entries.forEach { d ->
                    val on = Days.has(days, d)
                    Box(
                        Modifier.size(40.dp).clip(CircleShape)
                            .background(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainer)
                            .clickable { days = Days.toggle(days, d) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(Days.short(d).take(2), color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
            if (showErrors && days == 0) Hint("Choose at least one day", error = true)
            Hint("Other days are rest days: they don't count and don't break a streak.")
            }

            if (kindValue == Kind.BY_TIME) {
                SectionLabel("Different time on some days")
                DayOfWeek.entries.filter { Days.has(days, it) }.forEach { d ->
                    val own = times[d.value]
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(Days.short(d), Modifier.weight(1f))
                        TextButton(onClick = {
                            pickTime(context, own ?: time) { m -> dayTimes = DayTimes.format(times + (d.value to m)) }
                        }) { Text(own?.let { "By ${Times.format(it)}" } ?: "Usual (${Times.format(time)})") }
                        if (own != null) TextButton(onClick = { dayTimes = DayTimes.format(times - d.value) }) { Text("Reset") }
                    }
                }
                Hint("E.g. library by 10:00 on Tuesday and 13:00 on Thursday. To move just today, use ⋮ → Change today's target time.")
            }

            SectionLabel("Starts on")
            OutlinedButton(onClick = { pickDate(context, LocalDate.ofEpochDay(start)) { start = it.toEpochDay() } }, modifier = Modifier.fillMaxWidth()) {
                Text(LocalDate.ofEpochDay(start).format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM yyyy")))
            }
            Hint("Days before this don't count against you.")

            OutlinedTextField(
                value = notes,
                onValueChange = { notes = it.take(500) },
                label = { Text("Notes (optional)") },
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}
