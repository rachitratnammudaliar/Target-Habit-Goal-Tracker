package com.rachitratnammudaliar.target.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import com.rachitratnammudaliar.target.MainActivity
import com.rachitratnammudaliar.target.R
import com.rachitratnammudaliar.target.data.AppDatabase
import com.rachitratnammudaliar.target.data.Entry
import com.rachitratnammudaliar.target.data.goal
import com.rachitratnammudaliar.target.data.toLog
import com.rachitratnammudaliar.target.domain.Goals
import com.rachitratnammudaliar.target.domain.Kind
import com.rachitratnammudaliar.target.domain.LogType
import com.rachitratnammudaliar.target.domain.Result
import com.rachitratnammudaliar.target.domain.Times
import com.rachitratnammudaliar.target.ui.TargetState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime

/**
 * Home-screen widget: today's targets with a one-tap ✓ (done, log the time now, or add one step).
 * Everything stays on the phone; it reads and writes the same database as the app.
 */
class TodayWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                render(context, manager, ids)
            } finally {
                pending.finish()
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_TICK) return super.onReceive(context, intent)
        val id = intent.getLongExtra(EXTRA_ID, 0)
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                tick(context, id)
                val manager = AppWidgetManager.getInstance(context)
                render(context, manager, manager.getAppWidgetIds(ComponentName(context, TodayWidget::class.java)))
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val ACTION_TICK = "com.rachitratnammudaliar.target.widget.TICK"
        private const val EXTRA_ID = "id"
        private const val MAX_ROWS = 8

        /** Redraws every Today widget; the app calls this after anything changes. */
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, TodayWidget::class.java))
            if (ids.isEmpty()) return
            context.sendBroadcast(
                Intent(context, TodayWidget::class.java)
                    .setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids),
            )
        }

        private fun nowMinutes(): Int = LocalTime.now().let { it.hour * 60 + it.minute }

        private suspend fun tick(context: Context, id: Long) {
            val dao = AppDatabase.get(context).dao()
            val target = dao.target(id) ?: return
            val goal = target.goal()
            val today = LocalDate.now()
            val logs = dao.logs().filter { it.targetId == id }.map { it.toLog() }
            fun on(day: LocalDate) = logs.filter { it.date == day }
            when (goal.kind) {
                Kind.CHECK -> if (dao.entryOfType(id, today.toEpochDay(), LogType.DONE.name) == null) {
                    dao.insert(Entry(targetId = id, epochDay = today.toEpochDay(), type = LogType.DONE.name))
                }
                Kind.BY_TIME -> {
                    val (day, minutes) = Goals.nightAdjust(goal, today, nowMinutes(), on(today.minusDays(1)))
                    val d = day.toEpochDay()
                    if (dao.entryOfType(id, d, LogType.TIME.name) != null) return
                    if (dao.entryOfType(id, d, LogType.TARGET.name) == null) {
                        dao.insert(Entry(targetId = id, epochDay = d, type = LogType.TARGET.name, value = Goals.targetTime(goal, on(day), day).toDouble()))
                    }
                    dao.insert(Entry(targetId = id, epochDay = d, type = LogType.TIME.name, value = minutes.toDouble()))
                }
                Kind.AMOUNT -> dao.insert(Entry(targetId = id, epochDay = today.toEpochDay(), type = LogType.AMOUNT.name, value = target.step))
            }
        }

        private suspend fun render(context: Context, manager: AppWidgetManager, ids: IntArray) {
            if (ids.isEmpty()) return
            val dao = AppDatabase.get(context).dao()
            val state = TargetState(dao.targets(), dao.logs())
            val today = LocalDate.now()
            val now = nowMinutes()
            val due = state.active.filter { val g = it.goal(); Goals.isScheduled(g, today) && !Goals.isPaused(g, today) }
            val done = due.count { t -> Goals.result(t.goal(), state.logs(t.id, today), today, today) == Result.DONE }

            val views = RemoteViews(context.packageName, R.layout.widget_today)
            views.setTextViewText(R.id.widget_count, if (due.isEmpty()) "" else "$done of ${due.size}")
            views.setOnClickPendingIntent(R.id.widget_header, openApp(context))
            views.removeAllViews(R.id.widget_list)
            views.setViewVisibility(R.id.widget_empty, if (due.isEmpty()) View.VISIBLE else View.GONE)

            // Still to do first, then what's done.
            val ordered = due.sortedBy { t -> Goals.result(t.goal(), state.logs(t.id, today), today, today) == Result.DONE }
            ordered.take(MAX_ROWS).forEach { t ->
                val goal = t.goal()
                val logs = state.logs(t.id, today)
                val result = Goals.result(goal, logs, today, today)
                val row = RemoteViews(context.packageName, R.layout.widget_row)
                row.setTextViewText(R.id.row_title, "${t.emoji}  ${t.name}")
                val status = when {
                    goal.weekly -> {
                        val n = Goals.timesInWeek(goal, state.logsByDay(t.id), Goals.weekStart(today), today)
                        "$n of ${goal.weeklyTimes} this week" + if (goal.kind == Kind.AMOUNT) " · " + Goals.progressText(goal, logs, t.unit) else ""
                    }
                    goal.kind == Kind.BY_TIME -> Goals.time(logs)?.let { "Logged ${Times.format(it)}" }
                        ?: (if (Goals.isOverdue(goal, logs, today, today, now)) "Overdue · " else "By ") + Times.format(Goals.targetTime(goal, logs, today))
                    goal.kind == Kind.AMOUNT -> Goals.progressText(goal, logs, t.unit)
                    else -> if (result == Result.DONE) "Done" else "Any time"
                }
                row.setTextViewText(R.id.row_status, status)
                val finished = result == Result.DONE || result == Result.LATE || (goal.kind == Kind.CHECK && Goals.progress(goal, logs) >= 1.0)
                if (finished) {
                    row.setTextViewText(R.id.row_tick, "✓")
                    row.setInt(R.id.row_tick, "setBackgroundResource", R.drawable.widget_tick_done)
                    row.setOnClickPendingIntent(R.id.row_tick, openApp(context))
                } else {
                    row.setTextViewText(R.id.row_tick, if (goal.kind == Kind.AMOUNT) "＋" else "✓")
                    row.setInt(R.id.row_tick, "setBackgroundResource", R.drawable.widget_tick)
                    row.setOnClickPendingIntent(
                        R.id.row_tick,
                        PendingIntent.getBroadcast(
                            context, t.id.toInt(),
                            Intent(context, TodayWidget::class.java).setAction(ACTION_TICK).putExtra(EXTRA_ID, t.id),
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                        ),
                    )
                }
                row.setOnClickPendingIntent(R.id.row_name, openApp(context))
                views.addView(R.id.widget_list, row)
            }
            if (due.size > MAX_ROWS) {
                val more = RemoteViews(context.packageName, R.layout.widget_more)
                more.setTextViewText(R.id.more_text, "+${due.size - MAX_ROWS} more in the app")
                more.setOnClickPendingIntent(R.id.more_text, openApp(context))
                views.addView(R.id.widget_list, more)
            }
            manager.updateAppWidget(ids, views)
        }

        private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
