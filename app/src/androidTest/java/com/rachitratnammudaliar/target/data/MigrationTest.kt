package com.rachitratnammudaliar.target.data

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Targets and history saved by version 1.0 must survive the update unchanged. */
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    private val dbName = "migration-test.db"

    @Test
    fun version1DataIsKept() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(dbName)
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(dbName), null).use { db ->
            // The exact tables Room created in version 1.
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `targets` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `emoji` TEXT NOT NULL, " +
                    "`kind` TEXT NOT NULL, `goalValue` REAL NOT NULL, `timeMinutes` INTEGER NOT NULL, `unit` TEXT NOT NULL, `days` INTEGER NOT NULL, " +
                    "`health` TEXT NOT NULL, `step` REAL NOT NULL, `startDay` INTEGER NOT NULL, `sortOrder` INTEGER NOT NULL, `archived` INTEGER NOT NULL, " +
                    "`notes` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)",
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `logs` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `targetId` INTEGER NOT NULL, `epochDay` INTEGER NOT NULL, " +
                    "`type` TEXT NOT NULL, `value` REAL NOT NULL, `createdAt` INTEGER NOT NULL, " +
                    "FOREIGN KEY(`targetId`) REFERENCES `targets`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_logs_targetId` ON `logs` (`targetId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_logs_epochDay` ON `logs` (`epochDay`)")
            db.execSQL("INSERT INTO targets VALUES (1, 'Wake up', '⏰', 'BY_TIME', 1.0, 420, '', 127, 'NONE', 1.0, 20000, 1, 0, '', 1)")
            db.execSQL("INSERT INTO logs VALUES (1, 1, 20001, 'TIME', 415.0, 2)")
            db.version = 1
        }

        val room = Room.databaseBuilder(context, AppDatabase::class.java, dbName).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build()
        try {
            runBlocking {
                val dao = room.dao()
                val t = dao.targets().single()
                assertEquals("Wake up", t.name)
                assertEquals(420, t.timeMinutes)
                assertEquals("", t.dayTimes)
                assertEquals(0, t.weeklyTimes)
                assertEquals(0L, t.pauseEnd)
                assertEquals(false, t.weekTotal)
                val log = dao.logs().single()
                assertEquals(415.0, log.value, 0.0)
                assertEquals("", log.note)
                // The new columns can be written after the update.
                dao.update(t.copy(dayTimes = "2=600", weeklyTimes = 0, weekTotal = true))
                assertEquals("2=600", dao.target(1)!!.dayTimes)
                assertEquals(true, dao.target(1)!!.weekTotal)
                dao.insert(Entry(targetId = 1, epochDay = 20002, type = "NOTE", note = "Exam week"))
                assertEquals("Exam week", dao.logs().first { it.type == "NOTE" }.note)
                assertEquals(2, dao.deleteLogsBefore(20003))
            }
        } finally {
            room.close()
            context.deleteDatabase(dbName)
        }
    }
}
