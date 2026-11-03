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
}
