package com.rachitratnammudaliar.target.domain

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** How a target is met. Stored by [name]. */
enum class Kind(val label: String, val description: String) {
    /** Just done or not: "Meditate", "Go to the gym". */
    CHECK("Done or not", "Any time of day: tick it when you've done it"),
    /** Done by a time of day: "Wake up by 7:00", "At the library by 9:00". */
    BY_TIME("By a time", "Log the time you did it; on time counts"),
    /** Reach an amount: "Run 10 km", "10,000 steps", "Read 20 pages". */
    AMOUNT("An amount", "Add up to a number, like km, steps or pages");

    companion object {
        fun fromName(name: String): Kind = entries.firstOrNull { it.name == name } ?: CHECK
    }
}

/** Where an amount can come from automatically (Android, through Health Connect / Samsung Health). */
enum class HealthSource(val label: String, val unit: String, val step: Double) {
    NONE("Log it myself", "", 1.0),
    STEPS("Steps", "steps", 1000.0),
    DISTANCE("Walking and running distance", "km", 1.0),
    RUN_DISTANCE("Running distance", "km", 1.0),
    EXERCISE_MINUTES("Active time (workouts)", "min", 15.0),
    ACTIVE_CALORIES("Active calories", "kcal", 100.0);

    companion object {
        fun fromName(name: String): HealthSource = entries.firstOrNull { it.name == name } ?: NONE
    }
}

enum class LogType {
    /** A CHECK target was done. */
    DONE,
    /** A BY_TIME target was done at [Log.value] minutes after midnight. */
    TIME,
    /** Some of an AMOUNT target, added by hand. */
    AMOUNT,
    /** The day's total read from Samsung Health; one per target per day, replaced on every sync. */
    HEALTH,
    /** Rest day, ill, travelling: the day doesn't count either way. */
    SKIP,
    /**
     * BY_TIME: the target time for that day, in minutes. Saved when you change just today's time,
     * and when you log a time, so changing the target later never rewrites past days.
     */
    TARGET,
    /** A note for the day ("exam week"); the text is kept with the log, not in [Log.value]. */
    NOTE;

    companion object {
        fun fromName(name: String): LogType = entries.firstOrNull { it.name == name } ?: DONE
    }
}

/** How a target went on one day. */
enum class Result(val label: String) {
    DONE("Done"),
    /** BY_TIME done, but after the time. */
    LATE("Late"),
    /** AMOUNT started but short of the goal, on a day that's over. */
    PARTIAL("Not quite"),
    MISSED("Missed"),
    SKIPPED("Skipped"),
    /** Today (or later) and not done yet. */
    PENDING("To do"),
    /** Not one of the target's days. */
    OFF("Rest day");

    /** Days that count towards hit rates and streaks. */
    val counts: Boolean get() = this == DONE || this == LATE || this == PARTIAL || this == MISSED
}

/** A target as the rules see it, independent of the database. */
data class Goal(
    val kind: Kind = Kind.CHECK,
    /** AMOUNT: the number to reach. */
    val goalValue: Double = 1.0,
    /** BY_TIME: minutes after midnight, e.g. 420 = 07:00. */
    val timeMinutes: Int = 0,
    /** Days of the week as bits, Monday = 1 … Sunday = 64. */
    val days: Int = Days.ALL,
    /** The first day it counts. */
    val startDay: LocalDate = LocalDate.MIN,
    val health: HealthSource = HealthSource.NONE,
    /** BY_TIME: a different time on some weekdays (1 = Monday … 7 = Sunday). */
    val dayTimes: Map<Int, Int> = emptyMap(),
    /** CHECK and AMOUNT: done this many times a week, on any days. 0 = on set days instead. */
    val weeklyTimes: Int = 0,
    /** Paused from [pauseStart] to [pauseEnd], both included: those days count as skipped. */
    val pauseStart: LocalDate? = null,
    val pauseEnd: LocalDate? = null,
) {
    val weekly: Boolean get() = weeklyTimes > 0 && kind != Kind.BY_TIME
}

data class Log(val type: LogType, val date: LocalDate, val value: Double = 1.0)

object Days {
    const val ALL = 127
    const val WEEKDAYS = 31
    const val WEEKEND = 96

    fun bit(day: DayOfWeek): Int = 1 shl (day.value - 1)
    fun has(days: Int, day: DayOfWeek): Boolean = days and bit(day) != 0
    fun toggle(days: Int, day: DayOfWeek): Int = days xor bit(day)

    /** "Every day", "Weekdays", "Weekends", "Mon, Wed, Fri". */
    fun label(days: Int): String = when (days) {
        ALL -> "Every day"
        WEEKDAYS -> "Weekdays"
        WEEKEND -> "Weekends"
        0 -> "No days"
        else -> DayOfWeek.entries.filter { has(days, it) }.joinToString(", ") { short(it) }
    }

    fun short(day: DayOfWeek): String = day.name.take(3).lowercase().replaceFirstChar { it.uppercase() }
}

/** BY_TIME times that differ by weekday, stored as text: "1=540;2=600" (Monday 09:00, Tuesday 10:00). */
object DayTimes {
    fun parse(text: String): Map<Int, Int> = text.split(';').mapNotNull { part ->
        val (d, m) = part.split('=').takeIf { it.size == 2 } ?: return@mapNotNull null
        val day = d.trim().toIntOrNull()?.takeIf { it in 1..7 } ?: return@mapNotNull null
        val min = m.trim().toIntOrNull()?.takeIf { it in 0 until 24 * 60 } ?: return@mapNotNull null
        day to min
    }.toMap()

    fun format(times: Map<Int, Int>): String = times.entries.sortedBy { it.key }.joinToString(";") { "${it.key}=${it.value}" }
}

object Times {
    /** 420 -> "07:00". */
    fun format(minutes: Int): String {
        val m = Math.floorMod(minutes, 24 * 60)
        return "%02d:%02d".format(m / 60, m % 60)
    }

    /** "7:05", "07:05", "7.05" or "705" -> 425. Null if it isn't a time. */
    fun parse(text: String): Int? {
        val t = text.trim().replace('.', ':')
        val (h, m) = when {
            ':' in t -> t.substringBefore(':').toIntOrNull() to t.substringAfter(':').toIntOrNull()
            t.length in 3..4 -> t.dropLast(2).toIntOrNull() to t.takeLast(2).toIntOrNull()
            t.length in 1..2 -> t.toIntOrNull() to 0
            else -> null to null
        }
        if (h == null || m == null || h !in 0..23 || m !in 0..59) return null
        return h * 60 + m
    }
}

object Amounts {
    fun round(v: Double): Double = BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP).toDouble()

    /** 10000 -> "10,000", 6.25 -> "6.25", 5.0 -> "5". */
    fun format(v: Double): String {
        val r = BigDecimal.valueOf(round(v)).stripTrailingZeros()
        val whole = r.setScale(0, RoundingMode.DOWN)
        val grouped = "%,d".format(java.util.Locale.ENGLISH, whole.toLong())
        val frac = r.subtract(whole).abs()
        return if (frac.signum() == 0) grouped else grouped + frac.toPlainString().removePrefix("0")
    }

    /** "6.2 km", "10,000 steps", "3 glasses". */
    fun withUnit(v: Double, unit: String): String = if (unit.isBlank()) format(v) else "${format(v)} $unit"

    /** "1.5", "1,5", "10 000" -> number. Null if not a positive number. */
    fun parse(text: String): Double? {
        val v = text.trim().replace(" ", "").replace(',', '.').toDoubleOrNull() ?: return null
        return if (v > 0 && v <= 10_000_000 && !v.isNaN()) round(v) else null
    }
}

object Goals {
    /** Before this time, logging an evening target ("in bed by 23:00") counts for the day before. */
    const val NIGHT_CUTOFF = 4 * 60

    fun isPaused(goal: Goal, day: LocalDate): Boolean {
        val from = goal.pauseStart ?: return false
        val to = goal.pauseEnd ?: return false
        return !day.isBefore(from) && !day.isAfter(to)
    }

    /** Due that day: on one of its days (any day for weekly targets), from its start. */
    fun isScheduled(goal: Goal, day: LocalDate): Boolean =
        !day.isBefore(goal.startDay) && (goal.weekly || Days.has(goal.days, day.dayOfWeek))

    /** BY_TIME: the target time for [day]: changed for that day, else that weekday's, else the usual. */
    fun targetTime(goal: Goal, logs: List<Log>, day: LocalDate): Int =
        logs.lastOrNull { it.type == LogType.TARGET }?.value?.toInt()
            ?: goal.dayTimes[day.dayOfWeek.value]
            ?: goal.timeMinutes

    /** The amount reached: what Samsung Health counted, or what you added by hand, whichever is more. */
    fun amount(logs: List<Log>): Double {
        val manual = logs.filter { it.type == LogType.AMOUNT }.sumOf { it.value }
        val health = logs.filter { it.type == LogType.HEALTH }.maxOfOrNull { it.value } ?: 0.0
        return Amounts.round(maxOf(manual, health))
    }

    /** The earliest time logged that day, in minutes after midnight (past 24:00 for after midnight), or null. */
    fun time(logs: List<Log>): Int? = logs.filter { it.type == LogType.TIME }.minOfOrNull { it.value.toInt() }

    fun skipped(logs: List<Log>): Boolean = logs.any { it.type == LogType.SKIP }

    /** 0.0 to 1.0: how far along the day's target is. */
    fun progress(goal: Goal, logs: List<Log>): Double = when (goal.kind) {
        Kind.CHECK -> if (logs.any { it.type == LogType.DONE }) 1.0 else 0.0
        Kind.BY_TIME -> if (time(logs) != null) 1.0 else 0.0
        Kind.AMOUNT -> if (goal.goalValue <= 0) 0.0 else (amount(logs) / goal.goalValue).coerceIn(0.0, 1.0)
    }

    /** Whether the day's own target was reached, ignoring the schedule. */
    fun reached(goal: Goal, logs: List<Log>, day: LocalDate): Boolean = when (goal.kind) {
        Kind.CHECK -> logs.any { it.type == LogType.DONE }
        Kind.BY_TIME -> time(logs)?.let { it <= targetTime(goal, logs, day) } ?: false
        Kind.AMOUNT -> amount(logs) >= goal.goalValue - 1e-9
    }

    /** How [goal] went on [day], given that day's logs. Weekly targets never miss a single day. */
    fun result(goal: Goal, logs: List<Log>, day: LocalDate, today: LocalDate): Result {
        if (!isScheduled(goal, day)) return Result.OFF
        if (skipped(logs) || isPaused(goal, day)) return Result.SKIPPED
        val over = day.isBefore(today)
        when (goal.kind) {
            Kind.CHECK -> if (logs.any { it.type == LogType.DONE }) return Result.DONE
            Kind.BY_TIME -> time(logs)?.let { return if (it <= targetTime(goal, logs, day)) Result.DONE else Result.LATE }
            Kind.AMOUNT -> {
                val got = amount(logs)
                if (got >= goal.goalValue - 1e-9) return Result.DONE
                if (over && got > 0 && !goal.weekly) return Result.PARTIAL
            }
        }
        if (goal.weekly) {
            // Not doing it on a given day is fine; only the week counts.
            return if (weekStart(day) == weekStart(today) && !day.isAfter(today)) Result.PENDING else Result.OFF
        }
        return if (over) Result.MISSED else Result.PENDING
    }

    /** BY_TIME, today, nothing logged, and the target time has passed. */
    fun isOverdue(goal: Goal, logs: List<Log>, day: LocalDate, today: LocalDate, nowMinutes: Int): Boolean =
        goal.kind == Kind.BY_TIME && day == today && result(goal, logs, day, today) == Result.PENDING &&
            nowMinutes > targetTime(goal, logs, day)

    /**
     * Which day a time logged "now" belongs to, and its minutes: after midnight but before
     * [NIGHT_CUTOFF], an evening target (18:00 or later) still counts for yesterday, as 24:30.
     */
    fun nightAdjust(goal: Goal, today: LocalDate, nowMinutes: Int, yesterdayLogs: List<Log>): Pair<LocalDate, Int> {
        val yesterday = today.minusDays(1)
        val target = targetTime(goal, yesterdayLogs, yesterday)
        return if (goal.kind == Kind.BY_TIME && nowMinutes < NIGHT_CUTOFF && target >= 18 * 60 &&
            isScheduled(goal, yesterday) && time(yesterdayLogs) == null
        ) yesterday to nowMinutes + 24 * 60 else today to nowMinutes
    }

    /* ---------- Weeks, for "X times a week" targets ---------- */

    /** Days done in the week starting [monday]. */
    fun timesInWeek(goal: Goal, logsByDay: Map<LocalDate, List<Log>>, monday: LocalDate, today: LocalDate): Int =
        (0L..6L).map { monday.plusDays(it) }.count { d ->
            !d.isAfter(today) && isScheduled(goal, d) && !isPaused(goal, d) && reached(goal, logsByDay[d].orEmpty(), d)
        }

    /** A week of a weekly target: DONE when reached, PENDING while it's still this week, otherwise MISSED. */
    fun weekResult(goal: Goal, logsByDay: Map<LocalDate, List<Log>>, monday: LocalDate, today: LocalDate): Result {
        val end = monday.plusDays(6)
        if (end.isBefore(goal.startDay)) return Result.OFF
        if ((0L..6L).all { isPaused(goal, monday.plusDays(it)) }) return Result.SKIPPED
        if (timesInWeek(goal, logsByDay, monday, today) >= goal.weeklyTimes) return Result.DONE
        return if (!end.isBefore(today)) Result.PENDING else Result.MISSED
    }

    /**
     * Days in a row the target was done, counting back from today (weeks in a row for weekly
     * targets). Today, or this week, only adds once done; skipped and rest days are passed over.
     */
    fun currentStreak(goal: Goal, logsByDay: Map<LocalDate, List<Log>>, today: LocalDate): Int {
        var streak = 0
        if (goal.weekly) {
            var monday = weekStart(today)
            while (!monday.plusDays(6).isBefore(goal.startDay) && streak < 600) {
                when (weekResult(goal, logsByDay, monday, today)) {
                    Result.DONE -> streak++
                    Result.PENDING, Result.SKIPPED, Result.OFF -> {}
                    else -> return streak
                }
                monday = monday.minusWeeks(1)
            }
            return streak
        }
        var day = today
        while (!day.isBefore(goal.startDay)) {
            when (result(goal, logsByDay[day].orEmpty(), day, today)) {
                Result.DONE -> streak++
                Result.OFF, Result.SKIPPED, Result.PENDING -> {}
                else -> return streak
            }
            day = day.minusDays(1)
            if (ChronoUnit.DAYS.between(day, today) > 3660) break
        }
        return streak
    }

    /** The longest run of done days (weeks for weekly targets) ever. */
    fun bestStreak(goal: Goal, logsByDay: Map<LocalDate, List<Log>>, today: LocalDate): Int {
        val first = maxOf(goal.startDay, logsByDay.keys.minOrNull() ?: today, today.minusDays(3660))
        var best = 0
        var run = 0
        if (goal.weekly) {
            var monday = weekStart(first)
            while (!monday.isAfter(today)) {
                when (weekResult(goal, logsByDay, monday, today)) {
                    Result.DONE -> { run++; best = maxOf(best, run) }
                    Result.PENDING, Result.SKIPPED, Result.OFF -> {}
                    else -> run = 0
                }
                monday = monday.plusWeeks(1)
            }
            return best
        }
        var day = first
        while (!day.isAfter(today)) {
            when (result(goal, logsByDay[day].orEmpty(), day, today)) {
                Result.DONE -> { run++; best = maxOf(best, run) }
                Result.OFF, Result.SKIPPED, Result.PENDING -> {}
                else -> run = 0
            }
            day = day.plusDays(1)
        }
        return best
    }

    data class Rate(val done: Int, val counted: Int) {
        /** 0.0 to 1.0, or null if nothing has counted yet. */
        val fraction: Double? get() = if (counted == 0) null else done.toDouble() / counted
        val percent: Int? get() = fraction?.let { Math.round(it * 100).toInt() }
    }

    /**
     * How often it was done from [from] to [to]: days for most targets, whole weeks (Monday to
     * Sunday, within the range) for weekly ones. Today, or this week, counts only once done.
     */
    fun hitRate(goal: Goal, logsByDay: Map<LocalDate, List<Log>>, from: LocalDate, to: LocalDate, today: LocalDate): Rate {
        var done = 0
        var counted = 0
        if (goal.weekly) {
            var monday = weekStart(maxOf(from, goal.startDay))
            while (!monday.isAfter(minOf(to, today))) {
                when (weekResult(goal, logsByDay, monday, today)) {
                    Result.DONE -> { done++; counted++ }
                    Result.MISSED -> counted++
                    else -> {}
                }
                monday = monday.plusWeeks(1)
            }
            return Rate(done, counted)
        }
        var day = maxOf(from, goal.startDay)
        val end = minOf(to, today)
        while (!day.isAfter(end)) {
            val r = result(goal, logsByDay[day].orEmpty(), day, today)
            if (r == Result.DONE) done++
            if (r.counts) counted++
            day = day.plusDays(1)
        }
        return Rate(done, counted)
    }

    data class DayScore(val day: LocalDate, val done: Int, val scheduled: Int) {
        val fraction: Double get() = if (scheduled == 0) 0.0 else done.toDouble() / scheduled
    }

    /**
     * For one day: targets done out of those due (skips left out). A weekly target counts on days
     * it was done, and today while the week's count isn't reached yet.
     */
    fun dayScore(goals: List<Pair<Goal, Map<LocalDate, List<Log>>>>, day: LocalDate, today: LocalDate): DayScore {
        var done = 0
        var scheduled = 0
        goals.forEach { (goal, byDay) ->
            val r = result(goal, byDay[day].orEmpty(), day, today)
            if (goal.weekly) {
                if (r == Result.DONE) { done++; scheduled++ }
                else if (r == Result.PENDING && day == today && weekResult(goal, byDay, weekStart(day), today) == Result.PENDING) scheduled++
                return@forEach
            }
            when (r) {
                Result.OFF, Result.SKIPPED -> {}
                Result.DONE -> { done++; scheduled++ }
                else -> scheduled++
            }
        }
        return DayScore(day, done, scheduled)
    }

    /** "By 07:00", "10 km", "3 times a week", "Any time". */
    fun goalText(goal: Goal, unit: String): String {
        val base = when (goal.kind) {
            Kind.CHECK -> "Any time"
            Kind.BY_TIME -> "By ${Times.format(goal.timeMinutes)}" + if (goal.dayTimes.isNotEmpty()) " (varies)" else ""
            Kind.AMOUNT -> Amounts.withUnit(goal.goalValue, unit)
        }
        return if (goal.weekly) (if (goal.kind == Kind.CHECK) "" else "$base, ") + "${goal.weeklyTimes}× a week" else base
    }

    /** What's been logged so far: "06:52", "6.2 / 10 km", "Done", or "". */
    fun progressText(goal: Goal, logs: List<Log>, unit: String): String = when (goal.kind) {
        Kind.CHECK -> if (logs.any { it.type == LogType.DONE }) "Done" else ""
        Kind.BY_TIME -> time(logs)?.let { Times.format(it) } ?: ""
        Kind.AMOUNT -> "${Amounts.format(amount(logs))} / ${Amounts.withUnit(goal.goalValue, unit)}"
    }

    /** Monday of [day]'s week. */
    fun weekStart(day: LocalDate): LocalDate = day.minusDays((day.dayOfWeek.value - 1).toLong())

    /* ---------- Monthly report ---------- */

    data class Month(
        val done: Int,
        val counted: Int,
        /** The target with the longest streak right now, and that streak. */
        val bestStreak: Pair<Int, Int>?,
        /** The target whose hit rate rose most against the month before, and by how many points. */
        val mostImproved: Pair<Int, Int>?,
    )

    /** Summary of the month containing [today], up to today. Targets are referred to by index. */
    fun month(goals: List<Pair<Goal, Map<LocalDate, List<Log>>>>, today: LocalDate): Month {
        val first = today.withDayOfMonth(1)
        val prevFirst = first.minusMonths(1)
        val prevLast = first.minusDays(1)
        var done = 0
        var counted = 0
        var best: Pair<Int, Int>? = null
        var improved: Pair<Int, Int>? = null
        goals.forEachIndexed { i, (goal, byDay) ->
            val now = hitRate(goal, byDay, first, today, today)
            done += now.done
            counted += now.counted
            val streak = currentStreak(goal, byDay, today)
            if (streak > 0 && (best == null || streak > best!!.second)) best = i to streak
            val before = hitRate(goal, byDay, prevFirst, prevLast, today)
            val a = now.percent
            val b = before.percent
            if (a != null && b != null && a - b > 0 && (improved == null || a - b > improved!!.second)) improved = i to (a - b)
        }
        return Month(done, counted, best, improved)
    }
}

/** Ready-made targets for a quick start. */
data class Template(val emoji: String, val name: String, val kind: Kind, val goalValue: Double = 1.0, val timeMinutes: Int = 0, val unit: String = "", val health: HealthSource = HealthSource.NONE, val days: Int = Days.ALL, val weeklyTimes: Int = 0)

object Templates {
    val all = listOf(
        Template("⏰", "Wake up", Kind.BY_TIME, timeMinutes = 7 * 60),
        Template("📚", "At the library", Kind.BY_TIME, timeMinutes = 9 * 60, days = Days.WEEKDAYS),
        Template("🏃", "Run", Kind.AMOUNT, 5.0, unit = "km", health = HealthSource.RUN_DISTANCE, weeklyTimes = 3),
        Template("👟", "Steps", Kind.AMOUNT, 10_000.0, unit = "steps", health = HealthSource.STEPS),
        Template("🔥", "Burn calories", Kind.AMOUNT, 500.0, unit = "kcal", health = HealthSource.ACTIVE_CALORIES),
        Template("💪", "Be active", Kind.AMOUNT, 60.0, unit = "min", health = HealthSource.EXERCISE_MINUTES),
        Template("🏋️", "Gym", Kind.CHECK, weeklyTimes = 3),
        Template("💧", "Drink water", Kind.AMOUNT, 8.0, unit = "glasses"),
        Template("📖", "Read", Kind.AMOUNT, 20.0, unit = "pages"),
        Template("🧘", "Meditate", Kind.CHECK),
        Template("🌙", "In bed", Kind.BY_TIME, timeMinutes = 23 * 60),
    )

    val emojis = listOf("🎯", "⏰", "📚", "🏃", "👟", "🔥", "💪", "🏋️", "🚴", "🏊", "🧘", "💧", "📖", "🌙", "🥗", "🍎", "🚭", "📵", "✍️", "🙏", "🎸", "💻", "🧹", "☀️")
}
