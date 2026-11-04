package com.rachitratnammudaliar.target.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.rachitratnammudaliar.target.backup.Backup
import com.rachitratnammudaliar.target.data.AppDatabase
import com.rachitratnammudaliar.target.data.Entry
import com.rachitratnammudaliar.target.data.Habit
import com.rachitratnammudaliar.target.data.goal
import com.rachitratnammudaliar.target.data.healthValue
import com.rachitratnammudaliar.target.data.toLog
import com.rachitratnammudaliar.target.domain.Goal
import com.rachitratnammudaliar.target.domain.Goals
import com.rachitratnammudaliar.target.domain.HealthSource
import com.rachitratnammudaliar.target.domain.Kind
import com.rachitratnammudaliar.target.domain.Log
import com.rachitratnammudaliar.target.domain.LogType
import com.rachitratnammudaliar.target.health.Health
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalTime

/** Everything the screens show, kept together so every number agrees. */
data class TargetState(val targets: List<Habit>, val entries: List<Entry>) {
    val active: List<Habit> = targets.filter { !it.archived }
    private val byTarget: Map<Long, List<Entry>> = entries.groupBy { it.targetId }
    private val logsByTarget: Map<Long, Map<LocalDate, List<Log>>> =
        byTarget.mapValues { (_, l) -> l.map { it.toLog() }.groupBy { it.date } }

    fun target(id: Long): Habit? = targets.firstOrNull { it.id == id }
    fun entries(id: Long): List<Entry> = byTarget[id].orEmpty()
    fun logsByDay(id: Long): Map<LocalDate, List<Log>> = logsByTarget[id].orEmpty()
    fun logs(id: Long, day: LocalDate): List<Log> = logsByDay(id)[day].orEmpty()
    fun entries(id: Long, day: LocalDate): List<Entry> = entries(id).filter { it.epochDay == day.toEpochDay() }

    /** Active targets with their logs, for day scores. */
    val goals: List<Pair<Goal, Map<LocalDate, List<Log>>>> get() = active.map { it.goal() to logsByDay(it.id) }
    val usesHealth: Boolean get() = active.any { it.healthValue() != HealthSource.NONE }
}

class TargetViewModel(app: Application) : AndroidViewModel(app) {
    private val db = AppDatabase.get(app)
    private val dao = db.dao()
    val health = Health(app)

    val state: StateFlow<TargetState?> = combine(dao.observeTargets(), dao.observeLogs()) { t, l -> TargetState(t, l) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        // Keep the home-screen widget in step with the app.
        viewModelScope.launch {
            combine(dao.observeTargets(), dao.observeLogs()) { _, _ -> }
                .collect { com.rachitratnammudaliar.target.widget.TodayWidget.refresh(app) }
        }
    }

    /** "Synced 10:42", an error, or "" before the first sync. */
    private val _syncNote = MutableStateFlow("")
    val syncNote: StateFlow<String> = _syncNote.asStateFlow()

    private val _healthConnected = MutableStateFlow(false)
    val healthConnected: StateFlow<Boolean> = _healthConnected.asStateFlow()

    fun saveTarget(target: Habit, onSaved: (Long) -> Unit = {}) {
        viewModelScope.launch {
            val id = if (target.id == 0L) dao.insert(target.copy(sortOrder = dao.maxSort() + 1)) else {
                dao.update(target)
                target.id
            }
            onSaved(id)
            if (target.healthValue() != HealthSource.NONE) syncHealth()
        }
    }

    fun deleteTarget(target: Habit) {
        viewModelScope.launch { dao.delete(target) }
    }

    fun setArchived(target: Habit, archived: Boolean) {
        viewModelScope.launch { dao.update(target.copy(archived = archived)) }
    }

    /** Moves a target up or down the Today list. */
    fun move(state: TargetState, target: Habit, by: Int) {
        val list = state.active.toMutableList()
        val i = list.indexOfFirst { it.id == target.id }
        val j = i + by
        if (i < 0 || j !in list.indices) return
        list.add(j, list.removeAt(i))
        viewModelScope.launch { db.withTransaction { list.forEachIndexed { index, t -> dao.update(t.copy(sortOrder = index)) } } }
    }

    /** Logs something and returns its id through [onLogged], for Undo. */
    private fun add(entry: Entry, onLogged: (Long) -> Unit) {
        viewModelScope.launch { onLogged(dao.insert(entry)) }
    }

    /** CHECK: done, or undone if it already was. [onDone] gets whether it's now done and an Undo action. */
    fun toggleDone(target: Habit, day: LocalDate, onDone: (done: Boolean, undo: () -> Unit) -> Unit = { _, _ -> }) {
        viewModelScope.launch {
            val existing = dao.entryOfType(target.id, day.toEpochDay(), LogType.DONE.name)
            if (existing != null) {
                dao.deleteOfType(target.id, day.toEpochDay(), LogType.DONE.name)
                onDone(false) { viewModelScope.launch { dao.insert(existing.copy(id = 0)) } }
            } else {
                val id = dao.insert(Entry(targetId = target.id, epochDay = day.toEpochDay(), type = LogType.DONE.name))
                onDone(true) { deleteEntry(id) }
            }
        }
    }

    /**
     * BY_TIME: done at [minutes] after midnight on [day], replacing an earlier time for that day. The
     * day's target time is saved with it, so changing the target later never rewrites this day.
     */
    fun logTime(target: Habit, day: LocalDate, minutes: Int, onLogged: (Long) -> Unit = {}) {
        viewModelScope.launch {
            val d = day.toEpochDay()
            if (dao.entryOfType(target.id, d, LogType.TARGET.name) == null) {
                val logs = dao.logs().filter { it.targetId == target.id && it.epochDay == d }.map { it.toLog() }
                dao.insert(Entry(targetId = target.id, epochDay = d, type = LogType.TARGET.name, value = Goals.targetTime(target.goal(), logs, day).toDouble()))
            }
            dao.deleteOfType(target.id, d, LogType.TIME.name)
            onLogged(dao.insert(Entry(targetId = target.id, epochDay = d, type = LogType.TIME.name, value = minutes.toDouble())))
        }
    }

    /** BY_TIME: a different target time just for [day] (a class moved the library to 11:00), or back to normal with null. */
    fun setDayTarget(target: Habit, day: LocalDate, minutes: Int?) {
        viewModelScope.launch {
            dao.deleteOfType(target.id, day.toEpochDay(), LogType.TARGET.name)
            if (minutes != null) dao.insert(Entry(targetId = target.id, epochDay = day.toEpochDay(), type = LogType.TARGET.name, value = minutes.toDouble()))
        }
    }

    /** A note for the day, or none if [text] is blank. */
    fun setNote(target: Habit, day: LocalDate, text: String) {
        viewModelScope.launch {
            dao.deleteOfType(target.id, day.toEpochDay(), LogType.NOTE.name)
            val clean = text.trim().take(300)
            if (clean.isNotEmpty()) dao.insert(Entry(targetId = target.id, epochDay = day.toEpochDay(), type = LogType.NOTE.name, note = clean))
        }
    }

    /** Pauses from [from] to [to], both included; those days count as skipped. */
    fun pause(target: Habit, from: LocalDate, to: LocalDate) {
        viewModelScope.launch { dao.update(target.copy(pauseStart = from.toEpochDay(), pauseEnd = maxOf(from, to).toEpochDay())) }
    }

    /** Ends a pause: from today on it counts again; days already paused stay skipped. */
    fun resume(target: Habit, today: LocalDate) {
        viewModelScope.launch {
            val t = dao.target(target.id) ?: return@launch
            dao.update(if (t.pauseStart >= today.toEpochDay()) t.copy(pauseStart = 0, pauseEnd = 0) else t.copy(pauseEnd = today.minusDays(1).toEpochDay()))
        }
    }

    /** Deletes everything logged before [day], for all targets. Returns how many entries went. */
    fun clearHistoryBefore(day: LocalDate, onDone: (Int) -> Unit) {
        viewModelScope.launch { onDone(dao.deleteLogsBefore(day.toEpochDay())) }
    }

    /** Deletes one target's history, keeping the target. */
    fun clearHistory(target: Habit, onDone: (Int) -> Unit) {
        viewModelScope.launch { onDone(dao.deleteLogsFor(target.id)) }
    }

    /** AMOUNT: adds [amount] by hand. */
    fun addAmount(target: Habit, day: LocalDate, amount: Double, onLogged: (Long) -> Unit = {}) {
        if (amount <= 0) return
        add(Entry(targetId = target.id, epochDay = day.toEpochDay(), type = LogType.AMOUNT.name, value = amount), onLogged)
    }

    /** Skips a day (rest, ill, travelling) so it doesn't count, or un-skips it. */
    fun toggleSkip(target: Habit, day: LocalDate) {
        viewModelScope.launch {
            if (dao.entryOfType(target.id, day.toEpochDay(), LogType.SKIP.name) != null) {
                dao.deleteOfType(target.id, day.toEpochDay(), LogType.SKIP.name)
            } else {
                dao.insert(Entry(targetId = target.id, epochDay = day.toEpochDay(), type = LogType.SKIP.name))
            }
        }
    }

    /** Clears what was logged by hand that day (Samsung Health's total stays; it comes back on sync). */
    fun clearDay(target: Habit, day: LocalDate) {
        viewModelScope.launch {
            listOf(LogType.DONE, LogType.TIME, LogType.AMOUNT).forEach { dao.deleteOfType(target.id, day.toEpochDay(), it.name) }
        }
    }

    /** Minutes after midnight right now. */
    fun nowMinutes(): Int = LocalTime.now().let { it.hour * 60 + it.minute }

    fun deleteEntry(id: Long) {
        viewModelScope.launch { dao.deleteEntry(id) }
    }

    /** Reads the last [days] days from Samsung Health (through Health Connect) for targets linked to it. */
    fun syncHealth(days: Long = 7) {
        viewModelScope.launch {
            if (health.status() != Health.Status.AVAILABLE) {
                _healthConnected.value = false
                return@launch
            }
            val connected = withContext(Dispatchers.IO) { health.hasAll() }
            _healthConnected.value = connected
            if (!connected) return@launch
            val linked = dao.targets().filter { !it.archived && it.kind == Kind.AMOUNT.name && it.healthValue() != HealthSource.NONE }
            if (linked.isEmpty()) return@launch
            val today = LocalDate.now()
            var failed = false
            withContext(Dispatchers.IO) {
                for (d in 0 until days) {
                    val day = today.minusDays(d)
                    linked.filter { Goals.isScheduled(it.goal(), day) }.forEach { t ->
                        val total = health.total(t.healthValue(), day)
                        if (total == null) failed = true else dao.putHealth(t.id, day.toEpochDay(), total)
                    }
                }
            }
            val now = LocalTime.now()
            _syncNote.value = if (failed) "Couldn't read some data from Samsung Health" else "Synced %02d:%02d".format(now.hour, now.minute)
        }
    }

    fun refreshHealthStatus() {
        viewModelScope.launch { _healthConnected.value = health.status() == Health.Status.AVAILABLE && withContext(Dispatchers.IO) { health.hasAll() } }
    }

    suspend fun exportTo(uri: Uri): Int = withContext(Dispatchers.IO) {
        val targets = dao.targets()
        val text = Backup.encode(targets, dao.logs())
        getApplication<Application>().contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray()) }
            ?: throw IllegalStateException("Couldn't write the file")
        targets.size
    }

    suspend fun importFrom(uri: Uri): Int = withContext(Dispatchers.IO) {
        val text = getApplication<Application>().contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
            ?: throw IllegalStateException("Couldn't read the file")
        val contents = Backup.decode(text)
        val start = dao.maxSort() + 1
        db.withTransaction {
            contents.targets.forEachIndexed { i, t ->
                dao.insertWithEntries(t.copy(id = 0, sortOrder = start + i), contents.entries.filter { it.targetId == t.id })
            }
        }
        contents.targets.size
    }

    /** Sample data for screenshots (debug builds only). */
    fun seedDemo() {
        viewModelScope.launch {
            if (dao.count() > 0) return@launch
            val today = LocalDate.now()
            val start = today.minusDays(40).toEpochDay()
            fun day(ago: Long) = today.minusDays(ago).toEpochDay()
            val rnd = java.util.Random(7)
            suspend fun add(t: Habit, entries: (Long) -> List<Entry>) {
                db.withTransaction {
                    dao.insertWithEntries(t.copy(startDay = start), (1L..40L).flatMap { entries(it) })
                }
            }
            add(Habit(name = "Wake up", emoji = "⏰", kind = Kind.BY_TIME.name, timeMinutes = 7 * 60, sortOrder = 1)) { ago ->
                val m = 6 * 60 + 35 + rnd.nextInt(45)
                if (ago % 9 == 0L) emptyList() else listOf(Entry(targetId = 0, epochDay = day(ago), type = "TIME", value = m.toDouble()))
            }
            val libraryTimes = mapOf(2 to 10 * 60, 4 to 13 * 60)
            add(Habit(name = "At the library", emoji = "📚", kind = Kind.BY_TIME.name, timeMinutes = 9 * 60, days = 31, dayTimes = com.rachitratnammudaliar.target.domain.DayTimes.format(libraryTimes), sortOrder = 2)) { ago ->
                val m = (libraryTimes[today.minusDays(ago).dayOfWeek.value] ?: (9 * 60)) - 20 + rnd.nextInt(35)
                listOf(Entry(targetId = 0, epochDay = day(ago), type = "TIME", value = m.toDouble()))
            }
            add(Habit(name = "Run", emoji = "🏃", kind = Kind.AMOUNT.name, goalValue = 5.0, unit = "km", health = HealthSource.RUN_DISTANCE.name, weeklyTimes = 3, sortOrder = 3)) { ago ->
                if (ago % 2 == 1L) emptyList() else listOf(Entry(targetId = 0, epochDay = day(ago), type = "HEALTH", value = (30 + rnd.nextInt(35)) / 10.0 + 1.0))
            }
            add(Habit(name = "Steps", emoji = "👟", kind = Kind.AMOUNT.name, goalValue = 10_000.0, unit = "steps", step = 1000.0, health = HealthSource.STEPS.name, sortOrder = 4)) { ago ->
                listOf(Entry(targetId = 0, epochDay = day(ago), type = "HEALTH", value = (6000 + rnd.nextInt(7000)).toDouble()))
            }
            add(Habit(name = "Drink water", emoji = "💧", kind = Kind.AMOUNT.name, goalValue = 8.0, unit = "glasses", sortOrder = 5)) { ago ->
                List(5 + rnd.nextInt(4)) { Entry(targetId = 0, epochDay = day(ago), type = "AMOUNT", value = 1.0) }
            }
            add(Habit(name = "Gym", emoji = "🏋️", kind = Kind.CHECK.name, weeklyTimes = 3, sortOrder = 6)) { ago ->
                if (rnd.nextInt(7) >= 3) emptyList() else listOf(Entry(targetId = 0, epochDay = day(ago), type = "DONE"))
            }
            // Today: some done, some still to do.
            val ids = dao.targets().associateBy { it.name }
            ids["Wake up"]?.let { dao.insert(Entry(targetId = it.id, epochDay = today.toEpochDay(), type = "TIME", value = 412.0)) }
            ids["Steps"]?.let { dao.insert(Entry(targetId = it.id, epochDay = today.toEpochDay(), type = "HEALTH", value = 6420.0)) }
            ids["Drink water"]?.let { t -> repeat(3) { dao.insert(Entry(targetId = t.id, epochDay = today.toEpochDay(), type = "AMOUNT", value = 1.0)) } }
            ids["Run"]?.let { dao.insert(Entry(targetId = it.id, epochDay = today.toEpochDay(), type = "HEALTH", value = 5.4)) }
            ids["Wake up"]?.let { dao.insert(Entry(targetId = it.id, epochDay = today.toEpochDay(), type = "NOTE", note = "Slept early, felt great")) }
        }
    }
}
