package com.rachitratnammudaliar.target.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Update
import com.rachitratnammudaliar.target.domain.Days
import com.rachitratnammudaliar.target.domain.Goal
import com.rachitratnammudaliar.target.domain.HealthSource
import com.rachitratnammudaliar.target.domain.Kind
import com.rachitratnammudaliar.target.domain.Log
import com.rachitratnammudaliar.target.domain.LogType
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

/** A daily target: "Wake up by 7:00", "Run 10 km", "Gym". */
@Entity(tableName = "targets")
data class Habit(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val emoji: String = "🎯",
    val kind: String = Kind.CHECK.name,
    /** AMOUNT: the number to reach. */
    val goalValue: Double = 1.0,
    /** BY_TIME: minutes after midnight. */
    val timeMinutes: Int = 7 * 60,
    /** AMOUNT: "km", "steps", "pages". */
    val unit: String = "",
    /** Days of the week as bits, Monday = 1 … Sunday = 64. */
    val days: Int = Days.ALL,
    /** AMOUNT: filled in from Samsung Health through Health Connect. */
    val health: String = HealthSource.NONE.name,
    /** AMOUNT: what one tap of ＋ adds. */
    val step: Double = 1.0,
    /** The first day it counts, as an epoch day. */
    val startDay: Long = LocalDate.now().toEpochDay(),
    val sortOrder: Int = 0,
    /** Stopped: kept with its history, but no longer shown or counted. */
    val archived: Boolean = false,
    val notes: String = "",
    val createdAt: Long = System.currentTimeMillis(),
)

/** One thing logged for a target on a day. */
@Entity(
    tableName = "logs",
    foreignKeys = [ForeignKey(entity = Habit::class, parentColumns = ["id"], childColumns = ["targetId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("targetId"), Index("epochDay")],
)
data class Entry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val targetId: Long,
    val epochDay: Long,
    val type: String,
    /** TIME: minutes after midnight. AMOUNT and HEALTH: the amount. DONE and SKIP: 1. */
    val value: Double = 1.0,
    val createdAt: Long = System.currentTimeMillis(),
)

fun Habit.kindValue(): Kind = Kind.fromName(kind)
fun Habit.healthValue(): HealthSource = HealthSource.fromName(health)
fun Habit.goal(): Goal = Goal(kindValue(), goalValue, timeMinutes, days, LocalDate.ofEpochDay(startDay), healthValue())
fun Entry.typeValue(): LogType = LogType.fromName(type)
fun Entry.date(): LocalDate = LocalDate.ofEpochDay(epochDay)
fun Entry.toLog(): Log = Log(typeValue(), date(), value)

@Dao
interface TargetDao {
    @Query("SELECT * FROM targets ORDER BY sortOrder, createdAt")
    fun observeTargets(): Flow<List<Habit>>

    @Query("SELECT * FROM logs ORDER BY epochDay DESC, createdAt DESC")
    fun observeLogs(): Flow<List<Entry>>

    @Query("SELECT * FROM targets")
    suspend fun targets(): List<Habit>

    @Query("SELECT * FROM logs")
    suspend fun logs(): List<Entry>

    @Query("SELECT * FROM targets WHERE id = :id")
    suspend fun target(id: Long): Habit?

    @Query("SELECT COUNT(*) FROM targets")
    suspend fun count(): Int

    @Query("SELECT COALESCE(MAX(sortOrder), 0) FROM targets")
    suspend fun maxSort(): Int

    @Insert
    suspend fun insert(target: Habit): Long

    @Update
    suspend fun update(target: Habit)

    @Delete
    suspend fun delete(target: Habit)

    @Insert
    suspend fun insert(entry: Entry): Long

    @Query("DELETE FROM logs WHERE id = :id")
    suspend fun deleteEntry(id: Long)

    @Query("DELETE FROM logs WHERE targetId = :targetId AND epochDay = :day AND type = :type")
    suspend fun deleteOfType(targetId: Long, day: Long, type: String)

    @Query("SELECT * FROM logs WHERE targetId = :targetId AND epochDay = :day AND type = :type LIMIT 1")
    suspend fun entryOfType(targetId: Long, day: Long, type: String): Entry?

    /** Samsung Health's total for a day replaces the last one read. */
    @Transaction
    suspend fun putHealth(targetId: Long, day: Long, value: Double) {
        val old = entryOfType(targetId, day, "HEALTH")
        if (old?.value == value) return
        deleteOfType(targetId, day, "HEALTH")
        if (value > 0) insert(Entry(targetId = targetId, epochDay = day, type = "HEALTH", value = value))
    }

    @Transaction
    suspend fun insertWithEntries(target: Habit, entries: List<Entry>): Long {
        val id = insert(target)
        entries.forEach { insert(it.copy(id = 0, targetId = id)) }
        return id
    }
}

@Database(entities = [Habit::class, Entry::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): TargetDao

    companion object {
        const val DATABASE_NAME = "target.db"

        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, DATABASE_NAME)
                    .build().also { instance = it }
            }
    }
}
