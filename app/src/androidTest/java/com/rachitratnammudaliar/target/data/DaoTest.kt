package com.rachitratnammudaliar.target.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/** The database parts the screens rely on, on a real device. */
@RunWith(AndroidJUnit4::class)
class DaoTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun healthTotalsReplaceEachOtherAndDeleteCascades() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val dao = db.dao()
            val id = dao.insert(Habit(name = "Run", kind = "AMOUNT", goalValue = 10.0, unit = "km", health = "RUN_DISTANCE"))
            dao.putHealth(id, 20000, 4.0)
            dao.putHealth(id, 20000, 6.5)
            dao.insert(Entry(targetId = id, epochDay = 20000, type = "AMOUNT", value = 2.0))
            val logs = dao.logs()
            assertEquals(2, logs.size)
            assertEquals(6.5, logs.first { it.type == "HEALTH" }.value, 0.0)
            // Zero removes the day's health total.
            dao.putHealth(id, 20000, 0.0)
            assertNull(dao.entryOfType(id, 20000, "HEALTH"))
            dao.delete(dao.target(id)!!)
            assertEquals(0, dao.logs().size)
        } finally {
            db.close()
        }
    }
}
