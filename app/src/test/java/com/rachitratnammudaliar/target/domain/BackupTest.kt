package com.rachitratnammudaliar.target.domain

import com.rachitratnammudaliar.target.backup.Backup
import com.rachitratnammudaliar.target.data.Entry
import com.rachitratnammudaliar.target.data.Habit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BackupTest {
    @Test
    fun roundTrip() {
        val run = Habit(id = 4, name = "Run", emoji = "🏃", kind = "AMOUNT", goalValue = 10.0, unit = "km", days = Days.WEEKDAYS, health = "RUN_DISTANCE", step = 1.0, startDay = 20000, sortOrder = 2, createdAt = 5, weeklyTimes = 3)
        val wake = Habit(id = 7, name = "Wake up", emoji = "⏰", kind = "BY_TIME", timeMinutes = 420, startDay = 20001, createdAt = 6, dayTimes = "2=480;6=540", pauseStart = 20020, pauseEnd = 20025)
        val logs = listOf(
            Entry(targetId = 4, epochDay = 20010, type = "HEALTH", value = 6.4, createdAt = 1),
            Entry(targetId = 4, epochDay = 20010, type = "AMOUNT", value = 2.0, createdAt = 2),
            Entry(targetId = 7, epochDay = 20010, type = "TIME", value = 415.0, createdAt = 3),
            Entry(targetId = 7, epochDay = 20011, type = "SKIP", createdAt = 4),
            Entry(targetId = 7, epochDay = 20012, type = "NOTE", createdAt = 4, note = "Exam week"),
            Entry(targetId = 99, epochDay = 20011, type = "DONE", createdAt = 4),
        )
        val back = Backup.decode(Backup.encode(listOf(run, wake), logs))
        assertEquals(listOf(run, wake), back.targets)
        assertEquals(logs.take(5), back.entries)
    }

    @Test
    fun rejectsOtherFilesAndSkipsBrokenEntries() {
        assertThrows(IllegalArgumentException::class.java) { Backup.decode("{\"format\":\"kitchtally-backup\"}") }
        assertThrows(IllegalArgumentException::class.java) { Backup.decode("not json") }
        val back = Backup.decode(
            """{"format":"target-backup","targets":[{"ref":1,"name":"Gym","kind":"NOPE","days":0,"goalValue":-3},{"ref":2,"name":" "}],
               "logs":[{"target":1,"epochDay":20000,"type":"DONE","value":1},{"target":2,"epochDay":20000,"value":1},{"target":1,"value":1}]}""",
        )
        assertEquals(1, back.targets.size)
        assertEquals("CHECK", back.targets[0].kind)
        assertEquals(Days.ALL, back.targets[0].days)
        assertEquals(1.0, back.targets[0].goalValue, 0.0)
        assertEquals(1, back.entries.size)
    }
}
