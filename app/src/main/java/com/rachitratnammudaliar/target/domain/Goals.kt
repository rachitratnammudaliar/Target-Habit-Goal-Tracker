package com.rachitratnammudaliar.target.domain

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** How a target is met. Stored by [name]. */
enum class Kind(val label: String, val description: String) {
    /** Just done or not: "Meditate", "Go to the gym". */
    CHECK("Done or not", "Tick it when you've done it"),
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
    SKIP;

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
)

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
    fun isScheduled(goal: Goal, day: LocalDate): Boolean = !day.isBefore(goal.startDay) && Days.has(goal.days, day.dayOfWeek)

    /** The amount reached: what Samsung Health counted, or what you added by hand, whichever is more. */
    fun amount(logs: List<Log>): Double {
        val manual = logs.filter { it.type == LogType.AMOUNT }.sumOf { it.value }
        val health = logs.filter { it.type == LogType.HEALTH }.maxOfOrNull { it.value } ?: 0.0
        return Amounts.round(maxOf(manual, health))
    }

    /** The earliest time logged that day, in minutes after midnight, or null. */
    fun time(logs: List<Log>): Int? = logs.filter { it.type == LogType.TIME }.minOfOrNull { it.value.toInt() }

    fun skipped(logs: List<Log>): Boolean = logs.any { it.type == LogType.SKIP }

    /** 0.0 to 1.0: how far along the day's target is. */
    fun progress(goal: Goal, logs: List<Log>): Double = when (goal.kind) {
        Kind.CHECK -> if (logs.any { it.type == LogType.DONE }) 1.0 else 0.0
        Kind.BY_TIME -> if (time(logs) != null) 1.0 else 0.0
        Kind.AMOUNT -> if (goal.goalValue <= 0) 0.0 else (amount(logs) / goal.goalValue).coerceIn(0.0, 1.0)
    }

    /** How [goal] went on [day], given that day's logs. */
    fun result(goal: Goal, logs: List<Log>, day: LocalDate, today: LocalDate): Result {
        if (!isScheduled(goal, day)) return Result.OFF
        if (skipped(logs)) return Result.SKIPPED
        val over = day.isBefore(today)
        when (goal.kind) {
            Kind.CHECK -> if (logs.any { it.type == LogType.DONE }) return Result.DONE
            Kind.BY_TIME -> time(logs)?.let { return if (it <= goal.timeMinutes) Result.DONE else Result.LATE }
            Kind.AMOUNT -> {
                val got = amount(logs)
                if (got >= goal.goalValue - 1e-9) return Result.DONE
                if (over && got > 0) return Result.PARTIAL
            }
        }
        return if (over) Result.MISSED else Result.PENDING
    }

    /**
     * Days in a row the target was done, counting back from today. Today only adds once it's done
     * (it never breaks a streak while there's still time); skipped and rest days are passed over.
     */
    fun currentStreak(goal: Goal, logsByDay: Map<LocalDate, List<Log>>, today: LocalDate): Int {
        var streak = 0
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

    /** The longest run of done days ever. */
    fun bestStreak(goal: Goal, logsByDay: Map<LocalDate, List<Log>>, today: LocalDate): Int {
        val first = maxOf(goal.startDay, logsByDay.keys.minOrNull() ?: today, today.minusDays(3660))
        var best = 0
        var run = 0
        var day = first
        while (!day.isAfter(today)) {
            when (result(goal, logsByDay[day].orEmpty(), day, today)) {
                Result.DONE -> {
                    run++
                    best = maxOf(best, run)
                }
                Result.OFF, Result.SKIPPED, Result.PENDING -> {}
                else -> run = 0
            }
            day = day.plusDays(1)
        }
        return best
    }

    data class Rate(val done: Int, val counted: Int) {
        /** 0.0 to 1.0, or null if no day has counted yet. */
        val fraction: Double? get() = if (counted == 0) null else done.toDouble() / counted
        val percent: Int? get() = fraction?.let { Math.round(it * 100).toInt() }
    }

    /** How often it was done from [from] to [to]. Today counts only once it's done. */
    fun hitRate(goal: Goal, logsByDay: Map<LocalDate, List<Log>>, from: LocalDate, to: LocalDate, today: LocalDate): Rate {
        var done = 0
        var counted = 0
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

    /** For one day: how many targets were done, out of those that were due (skips left out). */
    fun dayScore(goals: List<Pair<Goal, Map<LocalDate, List<Log>>>>, day: LocalDate, today: LocalDate): DayScore {
        var done = 0
        var scheduled = 0
        goals.forEach { (goal, byDay) ->
            when (result(goal, byDay[day].orEmpty(), day, today)) {
                Result.OFF, Result.SKIPPED -> {}
                Result.DONE -> {
                    done++
                    scheduled++
                }
                else -> scheduled++
            }
        }
        return DayScore(day, done, scheduled)
    }

    /** "By 07:00", "10 km", "10,000 steps", "Done". */
    fun goalText(goal: Goal, unit: String): String = when (goal.kind) {
        Kind.CHECK -> "Done"
        Kind.BY_TIME -> "By ${Times.format(goal.timeMinutes)}"
        Kind.AMOUNT -> Amounts.withUnit(goal.goalValue, unit)
    }

    /** What's been logged so far: "06:52", "6.2 / 10 km", "Done", or "". */
    fun progressText(goal: Goal, logs: List<Log>, unit: String): String = when (goal.kind) {
        Kind.CHECK -> if (logs.any { it.type == LogType.DONE }) "Done" else ""
        Kind.BY_TIME -> time(logs)?.let { Times.format(it) } ?: ""
        Kind.AMOUNT -> "${Amounts.format(amount(logs))} / ${Amounts.withUnit(goal.goalValue, unit)}"
    }

    /** Monday of [day]'s week. */
    fun weekStart(day: LocalDate): LocalDate = day.minusDays((day.dayOfWeek.value - 1).toLong())
}

/** Ready-made targets for a quick start. */
data class Template(val emoji: String, val name: String, val kind: Kind, val goalValue: Double = 1.0, val timeMinutes: Int = 0, val unit: String = "", val health: HealthSource = HealthSource.NONE, val days: Int = Days.ALL)

object Templates {
    val all = listOf(
        Template("⏰", "Wake up", Kind.BY_TIME, timeMinutes = 7 * 60),
        Template("📚", "At the library", Kind.BY_TIME, timeMinutes = 9 * 60, days = Days.WEEKDAYS),
        Template("🏃", "Run", Kind.AMOUNT, 5.0, unit = "km", health = HealthSource.RUN_DISTANCE),
        Template("👟", "Steps", Kind.AMOUNT, 10_000.0, unit = "steps", health = HealthSource.STEPS),
        Template("🔥", "Burn calories", Kind.AMOUNT, 500.0, unit = "kcal", health = HealthSource.ACTIVE_CALORIES),
        Template("💪", "Be active", Kind.AMOUNT, 60.0, unit = "min", health = HealthSource.EXERCISE_MINUTES),
        Template("🏋️", "Gym", Kind.CHECK, days = 1 or 4 or 16),
        Template("💧", "Drink water", Kind.AMOUNT, 8.0, unit = "glasses"),
        Template("📖", "Read", Kind.AMOUNT, 20.0, unit = "pages"),
        Template("🧘", "Meditate", Kind.CHECK),
        Template("🌙", "In bed", Kind.BY_TIME, timeMinutes = 23 * 60),
    )

    val emojis = listOf("🎯", "⏰", "📚", "🏃", "👟", "🔥", "💪", "🏋️", "🚴", "🏊", "🧘", "💧", "📖", "🌙", "🥗", "🍎", "🚭", "📵", "✍️", "🙏", "🎸", "💻", "🧹", "☀️")
}
