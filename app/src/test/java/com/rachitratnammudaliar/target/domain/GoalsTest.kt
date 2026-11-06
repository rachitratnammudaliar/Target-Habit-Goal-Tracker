package com.rachitratnammudaliar.target.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

class GoalsTest {
    private val today = LocalDate.of(2026, 11, 4) // a Wednesday
    private fun d(daysAgo: Long) = today.minusDays(daysAgo)
    private val wake = Goal(Kind.BY_TIME, timeMinutes = 7 * 60, startDay = d(60))
    private val run = Goal(Kind.AMOUNT, goalValue = 10.0, startDay = d(60), health = HealthSource.RUN_DISTANCE)
    private val gym = Goal(Kind.CHECK, days = Days.bit(DayOfWeek.MONDAY) or Days.bit(DayOfWeek.WEDNESDAY), startDay = d(60))

    @Test
    fun byTimeIsDoneOnlyOnTime() {
        assertEquals(Result.DONE, Goals.result(wake, listOf(Log(LogType.TIME, d(1), 415.0)), d(1), today))
        assertEquals(Result.DONE, Goals.result(wake, listOf(Log(LogType.TIME, d(1), 420.0)), d(1), today))
        assertEquals(Result.LATE, Goals.result(wake, listOf(Log(LogType.TIME, d(1), 425.0)), d(1), today))
        // The earliest time counts.
        assertEquals(Result.DONE, Goals.result(wake, listOf(Log(LogType.TIME, d(1), 500.0), Log(LogType.TIME, d(1), 410.0)), d(1), today))
        assertEquals(Result.MISSED, Goals.result(wake, emptyList(), d(1), today))
        assertEquals(Result.PENDING, Goals.result(wake, emptyList(), today, today))
    }

    @Test
    fun amountsUseHealthOrManualWhicheverIsMore() {
        val samsung = Log(LogType.HEALTH, today, 10.2)
        assertEquals(Result.DONE, Goals.result(run, listOf(samsung), today, today))
        assertEquals(Result.PENDING, Goals.result(run, listOf(Log(LogType.HEALTH, today, 6.0)), today, today))
        assertEquals(Result.PARTIAL, Goals.result(run, listOf(Log(LogType.HEALTH, d(1), 6.0)), d(1), today))
        // Logged by hand: 4 + 6 = 10, more than Samsung Health's 7.
        val mixed = listOf(Log(LogType.AMOUNT, d(2), 4.0), Log(LogType.AMOUNT, d(2), 6.0), Log(LogType.HEALTH, d(2), 7.0))
        assertEquals(10.0, Goals.amount(mixed), 0.0)
        assertEquals(Result.DONE, Goals.result(run, mixed, d(2), today))
        assertEquals(0.6, Goals.progress(run, listOf(Log(LogType.HEALTH, today, 6.0))), 1e-9)
        assertEquals("6 / 10 km", Goals.progressText(run, listOf(Log(LogType.HEALTH, today, 6.0)), "km"))
    }

    @Test
    fun scheduleSkipsAndStart() {
        assertEquals(Result.OFF, Goals.result(gym, emptyList(), d(1), today)) // Tuesday
        assertEquals(Result.MISSED, Goals.result(gym, emptyList(), d(2), today)) // Monday
        assertEquals(Result.SKIPPED, Goals.result(gym, listOf(Log(LogType.SKIP, d(2))), d(2), today))
        assertEquals(Result.OFF, Goals.result(gym.copy(startDay = today), emptyList(), d(2), today))
        assertFalse(Goals.isScheduled(gym, d(1)))
        assertTrue(Goals.isScheduled(gym, today))
    }

    @Test
    fun streaksIgnoreRestDaysSkipsAndToday() {
        val logs = mutableMapOf<LocalDate, List<Log>>()
        // Woke on time for the last 5 days, but day 3 was skipped and day 6 was late.
        for (i in 1L..5L) logs[d(i)] = listOf(Log(LogType.TIME, d(i), 410.0))
        logs[d(3)] = listOf(Log(LogType.SKIP, d(3)))
        logs[d(6)] = listOf(Log(LogType.TIME, d(6), 450.0))
        for (i in 7L..15L) logs[d(i)] = listOf(Log(LogType.TIME, d(i), 400.0))
        // Today not logged yet: doesn't break the streak.
        assertEquals(4, Goals.currentStreak(wake, logs, today))
        logs[today] = listOf(Log(LogType.TIME, today, 405.0))
        assertEquals(5, Goals.currentStreak(wake, logs, today))
        assertEquals(9, Goals.bestStreak(wake, logs, today))

        // Gym on Mondays and Wednesdays: rest days between don't break it.
        val gymLogs = mapOf(d(2) to listOf(Log(LogType.DONE, d(2))), d(7) to listOf(Log(LogType.DONE, d(7))), d(9) to listOf(Log(LogType.DONE, d(9))))
        assertEquals(3, Goals.currentStreak(gym, gymLogs, today))
    }

    @Test
    fun hitRateAndDayScore() {
        val logs = mapOf(
            d(1) to listOf(Log(LogType.TIME, d(1), 410.0)),
            d(2) to listOf(Log(LogType.TIME, d(2), 480.0)),
            d(3) to listOf(Log(LogType.SKIP, d(3))),
        )
        // d(1) done, d(2) late, d(3) skipped, d(4) missed; today pending isn't counted.
        val rate = Goals.hitRate(wake, logs, d(4), today, today)
        assertEquals(1, rate.done)
        assertEquals(3, rate.counted)
        assertEquals(33, rate.percent)
        assertNull(Goals.hitRate(wake, emptyMap(), today, today, today).fraction)

        val score = Goals.dayScore(listOf(wake to logs, gym to emptyMap(), run to mapOf(d(1) to listOf(Log(LogType.HEALTH, d(1), 12.0)))), d(1), today)
        // Tuesday: wake done, gym rest day, run done.
        assertEquals(2, score.done)
        assertEquals(2, score.scheduled)
        assertEquals(1.0, score.fraction, 0.0)
    }

    @Test
    fun formatting() {
        assertEquals("07:00", Times.format(420))
        assertEquals("23:59", Times.format(1439))
        assertEquals(425, Times.parse("7:05"))
        assertEquals(425, Times.parse("07.05"))
        assertEquals(425, Times.parse("705"))
        assertEquals(1380, Times.parse("23"))
        assertNull(Times.parse("25:00"))
        assertNull(Times.parse("abc"))
        assertEquals("10,000", Amounts.format(10_000.0))
        assertEquals("6.25", Amounts.format(6.25))
        assertEquals("5 km", Amounts.withUnit(5.0, "km"))
        assertEquals(1.5, Amounts.parse("1,5")!!, 0.0)
        assertEquals(10000.0, Amounts.parse("10 000")!!, 0.0)
        assertNull(Amounts.parse("-2"))
        assertEquals("By 07:00", Goals.goalText(wake, ""))
        assertEquals("10,000 steps", Goals.goalText(Goal(Kind.AMOUNT, 10_000.0), "steps"))
        assertEquals("Every day", Days.label(Days.ALL))
        assertEquals("Weekdays", Days.label(Days.WEEKDAYS))
        assertEquals("Mon, Wed", Days.label(gym.days))
        assertEquals(LocalDate.of(2026, 11, 2), Goals.weekStart(today))
    }

    @Test
    fun timesPerWeekdayAndForOneDay() {
        // Library: usually 9:00, Tuesdays 10:00 (today is a Wednesday, d(1) a Tuesday).
        val library = wake.copy(timeMinutes = 540, dayTimes = mapOf(2 to 600))
        assertEquals(600, Goals.targetTime(library, emptyList(), d(1)))
        assertEquals(540, Goals.targetTime(library, emptyList(), today))
        assertEquals(Result.DONE, Goals.result(library, listOf(Log(LogType.TIME, d(1), 590.0)), d(1), today))
        assertEquals(Result.LATE, Goals.result(library, listOf(Log(LogType.TIME, today, 590.0)), today, today))
        // Changed just for today because of a class: 11:00.
        val moved = listOf(Log(LogType.TARGET, today, 660.0), Log(LogType.TIME, today, 650.0))
        assertEquals(Result.DONE, Goals.result(library, moved, today, today))
        // The day's saved target wins over a later change to the usual time.
        val logged = listOf(Log(LogType.TARGET, d(2), 420.0), Log(LogType.TIME, d(2), 415.0))
        assertEquals(Result.DONE, Goals.result(wake.copy(timeMinutes = 400), logged, d(2), today))
        assertEquals(mapOf(1 to 540, 2 to 600), DayTimes.parse(DayTimes.format(mapOf(2 to 600, 1 to 540))))
        assertEquals(mapOf(3 to 60), DayTimes.parse("3=60;x;9=10;2=9999"))
    }

    @Test
    fun overdueAndAfterMidnight() {
        assertTrue(Goals.isOverdue(wake, emptyList(), today, today, 7 * 60 + 1))
        assertFalse(Goals.isOverdue(wake, emptyList(), today, today, 7 * 60))
        assertFalse(Goals.isOverdue(wake, listOf(Log(LogType.TIME, today, 400.0)), today, today, 600))
        // "In bed by 23:00", tapped at 00:30: counts for yesterday, as late.
        val bed = wake.copy(timeMinutes = 23 * 60)
        val (day, minutes) = Goals.nightAdjust(bed, today, 30, emptyList())
        assertEquals(d(1), day)
        assertEquals(24 * 60 + 30, minutes)
        assertEquals(Result.LATE, Goals.result(bed, listOf(Log(LogType.TIME, d(1), minutes.toDouble())), d(1), today))
        assertEquals("00:30", Times.format(minutes))
        // Morning targets and later hours stay on today.
        assertEquals(today to 30, Goals.nightAdjust(wake, today, 30, emptyList()))
        assertEquals(today to 300, Goals.nightAdjust(bed, today, 300, emptyList()))
    }

    @Test
    fun timesAWeek() {
        val gym3 = Goal(Kind.CHECK, startDay = d(60), weeklyTimes = 3)
        val logs = mutableMapOf<LocalDate, List<Log>>()
        fun done(vararg ago: Long) = ago.forEach { logs[d(it)] = listOf(Log(LogType.DONE, d(it))) }
        // Week before last (Mon 19 – Sun 25 Oct): 3 times. Last week: 2. This week: 1 so far.
        done(16, 14, 12, 8, 6, 0)
        assertEquals(Result.DONE, Goals.weekResult(gym3, logs, LocalDate.of(2026, 10, 19), today))
        assertEquals(Result.MISSED, Goals.weekResult(gym3, logs, LocalDate.of(2026, 10, 26), today))
        assertEquals(Result.PENDING, Goals.weekResult(gym3, logs, Goals.weekStart(today), today))
        // Not going on a day is never "missed".
        assertEquals(Result.OFF, Goals.result(gym3, emptyList(), d(5), today))
        assertEquals(Result.PENDING, Goals.result(gym3, emptyList(), d(1), today))
        assertEquals(Result.DONE, Goals.result(gym3, logs[today].orEmpty(), today, today))
        assertEquals(0, Goals.currentStreak(gym3, logs, today))
        assertEquals(1, Goals.bestStreak(gym3, logs, today))
        val rate = Goals.hitRate(gym3, logs, LocalDate.of(2026, 10, 19), today, today)
        assertEquals(1, rate.done)
        assertEquals(2, rate.counted)
        done(1, 2)
        assertEquals(Result.DONE, Goals.weekResult(gym3, logs, Goals.weekStart(today), today))
        assertEquals(1, Goals.currentStreak(gym3, logs, today))
        assertEquals("3× a week", Goals.goalText(gym3, ""))
        assertEquals("5 km, 3× a week", Goals.goalText(run.copy(goalValue = 5.0, weeklyTimes = 3), "km"))
        // By-a-time targets keep their days.
        assertFalse(wake.copy(weeklyTimes = 3).weekly)
    }

    @Test
    fun pausedDaysAreSkipped() {
        val paused = wake.copy(pauseStart = d(3), pauseEnd = d(1))
        assertEquals(Result.SKIPPED, Goals.result(paused, emptyList(), d(2), today))
        assertEquals(Result.MISSED, Goals.result(paused, emptyList(), d(4), today))
        val logs = mapOf(d(4) to listOf(Log(LogType.TIME, d(4), 400.0)), d(5) to listOf(Log(LogType.TIME, d(5), 400.0)))
        assertEquals(2, Goals.currentStreak(paused, logs, today))
    }

    @Test
    fun monthlyReport() {
        // This month: wake done on 1–3 Nov. October: done only on 31st.
        val wakeLogs = (1..3).associate { LocalDate.of(2026, 11, it) to listOf(Log(LogType.TIME, LocalDate.of(2026, 11, it), 400.0)) } +
            mapOf(LocalDate.of(2026, 10, 31) to listOf(Log(LogType.TIME, LocalDate.of(2026, 10, 31), 400.0)))
        val m = Goals.month(listOf(wake to wakeLogs, gym to emptyMap()), today)
        assertEquals(3, m.done)
        assertEquals(0, m.bestStreak?.first)
        assertEquals(4, m.bestStreak?.second)
        assertEquals(0, m.mostImproved?.first)
    }

    @Test
    fun amountLevels() {
        fun day(km: Double) = Goals.result(run, listOf(Log(LogType.HEALTH, d(1), km)), d(1), today)
        assertEquals(Result.OVER, day(13.0))
        assertEquals(Result.DONE, day(10.0))
        assertEquals(Result.DONE, day(12.9))
        assertEquals(Result.GOOD, day(8.0))
        assertEquals(Result.GOOD, day(9.9))
        assertEquals(Result.PARTIAL, day(6.0))
        assertEquals(Result.PARTIAL, day(7.9))
        assertEquals(Result.MISSED, day(5.9))
        // Today it can still be reached, except that going well over counts straight away.
        assertEquals(Result.PENDING, Goals.result(run, listOf(Log(LogType.HEALTH, today, 8.0)), today, today))
        assertEquals(Result.OVER, Goals.result(run, listOf(Log(LogType.HEALTH, today, 14.0)), today, today))
        assertTrue(Result.GOOD.hit && Result.OVER.hit && !Result.PARTIAL.hit)
        assertEquals(Result.OVER, Goals.tier(18.2, 14.0))
    }

    @Test
    fun movementCountsDistanceOrStepsWhicheverIsMore() {
        assertEquals(10.0, Goals.movementKm(3.0, 13_500.0), 1e-9)
        assertEquals(10.0, Goals.movementKm(10.0, 4_000.0), 1e-9)
        assertEquals(6.0, Goals.movementKm(2.0, 8_100.0), 1e-9)
        val move = Goal(kind = Kind.AMOUNT, goalValue = 10.0, health = HealthSource.MOVEMENT)
        assertEquals("10 km or 13,500 steps", Goals.goalText(move, "km"))
        // 13,500 steps and little distance recorded: achieved. 18,000 steps: overachieved.
        assertEquals(Result.DONE, Goals.result(move, listOf(Log(LogType.HEALTH, d(1), Goals.movementKm(2.0, 13_500.0))), d(1), today))
        assertEquals(Result.OVER, Goals.result(move, listOf(Log(LogType.HEALTH, d(1), Goals.movementKm(2.0, 18_000.0))), d(1), today))
        assertEquals(Result.GOOD, Goals.result(move, listOf(Log(LogType.HEALTH, d(1), Goals.movementKm(8.0, 5_000.0))), d(1), today))
    }

    @Test
    fun partlyDaysKeepAStreakAliveButDontAddToIt() {
        val move = Goal(kind = Kind.AMOUNT, goalValue = 10.0, startDay = d(30))
        val logs = mapOf(
            d(1) to listOf(Log(LogType.HEALTH, d(1), 11.0)),
            d(2) to listOf(Log(LogType.HEALTH, d(2), 6.5)),
            d(3) to listOf(Log(LogType.HEALTH, d(3), 9.0)),
            d(4) to listOf(Log(LogType.HEALTH, d(4), 10.0)),
            d(5) to listOf(Log(LogType.HEALTH, d(5), 3.0)),
        )
        // 11, (6.5 keeps it alive), 9 (satisfied counts), 10, then a 3 km day breaks it.
        assertEquals(3, Goals.currentStreak(move, logs, today))
        val rate = Goals.hitRate(move, logs, d(5), d(1), today)
        assertEquals(3, rate.done)
        assertEquals(5, rate.counted)
    }

    @Test
    fun hoursForTheWeek() {
        val study = Goal(kind = Kind.AMOUNT, goalValue = 15.0, weekTotal = true, startDay = d(60))
        assertTrue(study.weekly)
        val monday = Goals.weekStart(today) // 2 Nov
        val logs = mapOf(
            monday to listOf(Log(LogType.AMOUNT, monday, 3.0)),
            monday.plusDays(1) to listOf(Log(LogType.AMOUNT, monday.plusDays(1), 2.5), Log(LogType.AMOUNT, monday.plusDays(1), 1.0)),
        )
        assertEquals(6.5, Goals.weekAmount(study, logs, monday, today), 1e-9)
        assertEquals(Result.PENDING, Goals.weekResult(study, logs, monday, today))
        assertEquals("6.5 / 15 h".let { it }, "${Amounts.format(Goals.weekAmount(study, logs, monday, today))} / ${Amounts.withUnit(study.goalValue, "h")}")
        assertEquals("15 h a week", Goals.goalText(study, "h"))
        val lastMonday = monday.minusWeeks(1)
        fun week(total: Double) = mapOf(lastMonday to listOf(Log(LogType.AMOUNT, lastMonday, total)))
        assertEquals(Result.OVER, Goals.weekResult(study, week(20.0), lastMonday, today))
        assertEquals(Result.DONE, Goals.weekResult(study, week(15.0), lastMonday, today))
        assertEquals(Result.GOOD, Goals.weekResult(study, week(12.0), lastMonday, today))
        assertEquals(Result.PARTIAL, Goals.weekResult(study, week(9.0), lastMonday, today))
        assertEquals(Result.MISSED, Goals.weekResult(study, week(4.0), lastMonday, today))
        assertEquals(1, Goals.currentStreak(study, week(15.0), today))
        // Hours put in on a day make it an active day on the heatmap; no hours is a rest day.
        assertEquals(Result.DONE, Goals.result(study, logs.getValue(monday), monday, today))
        assertEquals(Result.OFF, Goals.result(study, emptyList(), d(10), today))
    }

    @Test
    fun parsingHours() {
        assertEquals(1.5, Amounts.parseHours("1.5")!!, 1e-9)
        assertEquals(1.5, Amounts.parseHours("1,5")!!, 1e-9)
        assertEquals(1.5, Amounts.parseHours("1:30")!!, 1e-9)
        assertEquals(0.75, Amounts.parseHours("0:45")!!, 1e-9)
        assertNull(Amounts.parseHours("0:00"))
        assertNull(Amounts.parseHours("1:75"))
        assertNull(Amounts.parseHours("30"))
        assertNull(Amounts.parseHours("abc"))
        assertEquals("30 min", Amounts.hoursText(0.5))
        assertEquals("1 h 30", Amounts.hoursText(1.5))
        assertEquals("2 h", Amounts.hoursText(2.0))
    }

    @Test
    fun aTimePickedAfterMidnightForABedtimeIsLateForThatNight() {
        val bed = Goal(kind = Kind.BY_TIME, timeMinutes = 23 * 60 + 45, startDay = d(30))
        val target = { _: LocalDate -> 23 * 60 + 45 }
        // Picked 02:00 from today's card: last night, 26:00, late.
        val (day, min) = Goals.pickAdjust(bed, today, today, 2 * 60, target)
        assertEquals(d(1), day)
        assertEquals(26 * 60, min)
        assertEquals(Result.LATE, Goals.result(bed, listOf(Log(LogType.TIME, day, min.toDouble())), day, today))
        // Picked 02:00 from an earlier day's card: that day's night.
        assertEquals(d(3) to 26 * 60, Goals.pickAdjust(bed, d(3), today, 2 * 60, target))
        // 23:30 is on time and untouched; so is 04:00 or later.
        assertEquals(today to (23 * 60 + 30), Goals.pickAdjust(bed, today, today, 23 * 60 + 30, target))
        assertEquals(today to (4 * 60), Goals.pickAdjust(bed, today, today, 4 * 60, target))
        // A morning target (wake up by 07:00) is never moved.
        val wake = Goal(kind = Kind.BY_TIME, timeMinutes = 7 * 60)
        assertEquals(today to 90, Goals.pickAdjust(wake, today, today, 90) { 7 * 60 })
        // Already adjusted times pass through unchanged, so Now and the picker agree.
        assertEquals(d(1) to 26 * 60, Goals.pickAdjust(bed, d(1), today, 26 * 60, target))
    }

    @Test
    fun weeklySummary() {
        val move = Goal(kind = Kind.AMOUNT, goalValue = 10.0, startDay = d(40))
        val km = listOf(9.0, 12.0, 14.0, 6.5) // Sun, Mon … for the days before Wednesday 4 Nov
        val byDay = mapOf(
            d(3) to listOf(Log(LogType.HEALTH, d(3), km[0])), // Sun 1
            d(2) to listOf(Log(LogType.HEALTH, d(2), km[1])), // Mon 2
            d(1) to listOf(Log(LogType.HEALTH, d(1), km[2])), // Tue 3
        )
        val monday = Goals.weekStart(today)
        val s = Goals.summary(listOf(move to byDay), listOf("km"), monday, monday.plusDays(6), monday.minusWeeks(1), monday.minusDays(1), today)
        assertEquals(2, s.done) // Mon 12 and Tue 14
        assertEquals(2, s.counted)
        assertEquals(1, s.overachieved) // 14 km is 140%
        assertEquals(listOf("km" to 26.0), s.totals)
        assertEquals(100, s.percent)
        // The week before: Sunday 1 Nov was 9 km (satisfied) and counted as a hit.
        assertEquals(14, s.previousPercent) // 1 hit (Sunday, 9 km) in 7 days
    }
}
