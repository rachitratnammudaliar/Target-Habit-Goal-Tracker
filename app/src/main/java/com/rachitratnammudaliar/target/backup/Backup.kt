package com.rachitratnammudaliar.target.backup

import com.rachitratnammudaliar.target.data.Entry
import com.rachitratnammudaliar.target.data.Habit
import com.rachitratnammudaliar.target.domain.Days
import com.rachitratnammudaliar.target.domain.HealthSource
import com.rachitratnammudaliar.target.domain.Kind
import com.rachitratnammudaliar.target.domain.LogType
import org.json.JSONArray
import org.json.JSONObject

/**
 * Plain JSON backups that the Android and iPhone apps both read. Targets are numbered within the
 * file and logs refer to those numbers.
 */
object Backup {
    private const val FORMAT = "target-backup"
    private const val VERSION = 1

    data class Contents(val targets: List<Habit>, val entries: List<Entry>)

    fun encode(targets: List<Habit>, entries: List<Entry>): String {
        val t = JSONArray()
        targets.forEach { h ->
            t.put(
                JSONObject()
                    .put("ref", h.id).put("name", h.name).put("emoji", h.emoji).put("kind", h.kind)
                    .put("goalValue", h.goalValue).put("timeMinutes", h.timeMinutes).put("unit", h.unit)
                    .put("days", h.days).put("health", h.health).put("step", h.step).put("startDay", h.startDay)
                    .put("sortOrder", h.sortOrder).put("archived", h.archived).put("notes", h.notes).put("createdAt", h.createdAt),
            )
        }
        val ids = targets.map { it.id }.toSet()
        val l = JSONArray()
        entries.filter { it.targetId in ids }.forEach { e ->
            l.put(JSONObject().put("target", e.targetId).put("epochDay", e.epochDay).put("type", e.type).put("value", e.value).put("createdAt", e.createdAt))
        }
        return JSONObject().put("format", FORMAT).put("version", VERSION).put("targets", t).put("logs", l).toString(2)
    }

    /** Throws IllegalArgumentException if it isn't a Target backup. Broken entries are skipped. */
    fun decode(text: String): Contents {
        val root = try {
            JSONObject(text)
        } catch (e: Exception) {
            throw IllegalArgumentException("This file isn't a Target backup.")
        }
        if (root.optString("format") != FORMAT) throw IllegalArgumentException("This file isn't a Target backup.")
        val targets = mutableListOf<Habit>()
        val t = root.optJSONArray("targets") ?: JSONArray()
        for (i in 0 until t.length()) {
            val o = t.optJSONObject(i) ?: continue
            val name = o.optString("name").trim()
            if (name.isEmpty() || !o.has("ref")) continue
            fun num(key: String, fallback: Double, max: Double = 10_000_000.0) = o.optDouble(key, fallback).let { if (it.isNaN() || it < 0) fallback else it.coerceAtMost(max) }
            targets += Habit(
                id = o.optLong("ref"),
                name = name.take(60),
                emoji = o.optString("emoji", "🎯").ifBlank { "🎯" }.take(8),
                kind = Kind.fromName(o.optString("kind")).name,
                goalValue = num("goalValue", 1.0).takeIf { it > 0 } ?: 1.0,
                timeMinutes = o.optInt("timeMinutes", 420).coerceIn(0, 1439),
                unit = o.optString("unit").take(20),
                days = o.optInt("days", Days.ALL).coerceIn(0, Days.ALL).takeIf { it != 0 } ?: Days.ALL,
                health = HealthSource.fromName(o.optString("health")).name,
                step = num("step", 1.0).takeIf { it > 0 } ?: 1.0,
                startDay = o.optLong("startDay", 0),
                sortOrder = o.optInt("sortOrder", i),
                archived = o.optBoolean("archived", false),
                notes = o.optString("notes").take(500),
                createdAt = o.optLong("createdAt", System.currentTimeMillis()),
            )
        }
        val refs = targets.map { it.id }.toSet()
        val entries = mutableListOf<Entry>()
        val l = root.optJSONArray("logs") ?: JSONArray()
        for (i in 0 until l.length()) {
            val o = l.optJSONObject(i) ?: continue
            val ref = o.optLong("target", -1)
            val value = o.optDouble("value", Double.NaN)
            if (ref !in refs || !o.has("epochDay") || value.isNaN() || value < 0) continue
            entries += Entry(
                targetId = ref,
                epochDay = o.getLong("epochDay"),
                type = LogType.fromName(o.optString("type")).name,
                value = value,
                createdAt = o.optLong("createdAt", System.currentTimeMillis()),
            )
        }
        return Contents(targets, entries)
    }
}
