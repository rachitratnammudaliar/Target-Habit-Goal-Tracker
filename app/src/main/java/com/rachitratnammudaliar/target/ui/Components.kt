package com.rachitratnammudaliar.target.ui

import android.app.TimePickerDialog
import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.rachitratnammudaliar.target.domain.Result
import java.time.format.DateTimeFormatter

/* ============================ Theme ============================ */

private val Light = lightColorScheme(
    primary = Color(0xFF4F46E5),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE4E3FF),
    onPrimaryContainer = Color(0xFF1C1772),
    secondaryContainer = Color(0xFFFFE9B8),
    onSecondaryContainer = Color(0xFF4A3300),
    background = Color(0xFFF7F7FC),
    surface = Color(0xFFF7F7FC),
    surfaceContainerLow = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFFF0F0F8),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFB4B0FF),
    onPrimary = Color(0xFF1C1772),
    primaryContainer = Color(0xFF2F2A8F),
    onPrimaryContainer = Color(0xFFE4E3FF),
    secondaryContainer = Color(0xFF5A4300),
    onSecondaryContainer = Color(0xFFFFE9B8),
    background = Color(0xFF0F1020),
    surface = Color(0xFF0F1020),
    surfaceContainerLow = Color(0xFF181A2E),
    surfaceContainer = Color(0xFF1E2036),
)

@Composable
fun TargetTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}

val dayTitle: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE, d MMM")
val shortDate: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM")
val longDate: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy")

/** Colours for each result, the same in the charts, heatmaps and cards. */
@Composable
fun resultColor(r: Result): Color {
    val dark = isSystemInDarkTheme()
    return when (r) {
        Result.DONE -> if (dark) Color(0xFF4ADE80) else Color(0xFF16A34A)
        Result.LATE, Result.PARTIAL -> if (dark) Color(0xFFFBBF24) else Color(0xFFD97706)
        Result.MISSED -> if (dark) Color(0xFFF87171) else Color(0xFFDC2626)
        Result.SKIPPED -> if (dark) Color(0xFF64748B) else Color(0xFF94A3B8)
        Result.PENDING -> MaterialTheme.colorScheme.outlineVariant
        Result.OFF -> MaterialTheme.colorScheme.surfaceContainer
    }
}

/* ============================ Bits ============================ */

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold, modifier = modifier.padding(top = 4.dp))
}

@Composable
fun Hint(text: String, error: Boolean = false) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
fun <T> ChipRow(options: List<T>, selected: T?, label: (T) -> String, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { o ->
            FilterChip(
                selected = o == selected,
                onClick = { onSelect(o) },
                label = { Text(label(o)) },
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer),
            )
        }
    }
}

@Composable
fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
fun NumberField(value: String, onChange: (String) -> Unit, label: String, isError: Boolean = false, error: String = "", modifier: Modifier = Modifier.fillMaxWidth()) {
    OutlinedTextField(
        value = value,
        onValueChange = { input -> onChange(input.filter { it.isDigit() || it == '.' || it == ',' }.take(10)) },
        label = { Text(label) },
        singleLine = true,
        isError = isError,
        supportingText = if (isError && error.isNotEmpty()) ({ Text(error) }) else null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier,
    )
}

/** A number with a label underneath. */
@Composable
fun StatTile(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier.clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)).padding(horizontal = 12.dp, vertical = 10.dp)) {
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

/** The target's emoji on a soft circle, coloured by how today is going. */
@Composable
fun EmojiBadge(emoji: String, tint: Color, size: Dp = 46.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(tint.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
        Text(emoji, style = MaterialTheme.typography.titleLarge)
    }
}

fun pickTime(context: Context, minutes: Int, onPicked: (Int) -> Unit) {
    TimePickerDialog(context, { _, h, m -> onPicked(h * 60 + m) }, minutes / 60, minutes % 60, true).show()
}

/* ============================ Charts ============================ */

/** A ring that fills as targets are done. */
@Composable
fun ProgressRing(fraction: Float, size: Dp = 84.dp, stroke: Dp = 10.dp, label: @Composable () -> Unit) {
    val track = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
    val fill = MaterialTheme.colorScheme.primary
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val s = stroke.toPx()
            val box = Size(this.size.width - s, this.size.height - s)
            drawArc(track, 0f, 360f, false, Offset(s / 2, s / 2), box, style = Stroke(s))
            drawArc(fill, -90f, 360f * fraction.coerceIn(0f, 1f), false, Offset(s / 2, s / 2), box, style = Stroke(s, cap = StrokeCap.Round))
        }
        label()
    }
}

/**
 * Vertical bars from 0 to [max], with an optional dashed goal line. [colors] colours each bar;
 * labels are shown under every [labelEvery]th bar.
 */
@Composable
fun BarChart(
    values: List<Double>,
    labels: List<String>,
    colors: List<Color>,
    max: Double,
    goal: Double? = null,
    height: Dp = 150.dp,
    labelEvery: Int = 1,
) {
    val goalColor = MaterialTheme.colorScheme.onSurfaceVariant
    val empty = MaterialTheme.colorScheme.surfaceContainer
    Column {
        Canvas(Modifier.fillMaxWidth().height(height)) {
            if (values.isEmpty()) return@Canvas
            val gap = 4.dp.toPx()
            val w = (size.width - gap * (values.size - 1)) / values.size
            val top = if (max <= 0) 1.0 else max
            values.forEachIndexed { i, v ->
                val x = i * (w + gap)
                drawRoundRect(empty, Offset(x, 0f), Size(w, size.height), androidx.compose.ui.geometry.CornerRadius(w / 3))
                val h = (v / top).coerceIn(0.0, 1.0).toFloat() * size.height
                if (h > 0) drawRoundRect(colors.getOrElse(i) { colors.last() }, Offset(x, size.height - h), Size(w, h), androidx.compose.ui.geometry.CornerRadius(w / 3))
            }
            if (goal != null && goal > 0 && goal <= top) {
                val y = size.height - (goal / top).toFloat() * size.height
                drawLine(goalColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
            }
        }
        Spacer(Modifier.height(4.dp))
        AxisLabels(labels, labelEvery)
    }
}

/**
 * Times of day as dots joined by a line, with the target time dashed. Earlier is higher, so a
 * line above the dashes means on time. Null means nothing logged that day.
 */
@Composable
fun TimeChart(minutes: List<Int?>, labels: List<String>, target: Int, height: Dp = 160.dp, labelEvery: Int = 7) {
    val logged = minutes.filterNotNull()
    val lo = (minOf(logged.minOrNull() ?: target, target) - 30).coerceAtLeast(0)
    val hi = (maxOf(logged.maxOrNull() ?: target, target) + 30).coerceAtMost(24 * 60)
    val line = MaterialTheme.colorScheme.primary
    val ok = resultColor(Result.DONE)
    val late = resultColor(Result.LATE)
    val goalColor = MaterialTheme.colorScheme.onSurfaceVariant
    Column {
        Row {
            Column(Modifier.height(height), verticalArrangement = Arrangement.SpaceBetween) {
                Text(com.rachitratnammudaliar.target.domain.Times.format(lo), style = MaterialTheme.typography.labelSmall, color = goalColor)
                Text(com.rachitratnammudaliar.target.domain.Times.format(hi), style = MaterialTheme.typography.labelSmall, color = goalColor)
            }
            Spacer(Modifier.size(8.dp))
            Canvas(Modifier.weight(1f).height(height)) {
                if (minutes.isEmpty()) return@Canvas
                fun y(m: Int) = (m - lo).toFloat() / (hi - lo).coerceAtLeast(1) * size.height
                val step = if (minutes.size > 1) size.width / (minutes.size - 1) else 0f
                val ty = y(target)
                drawLine(goalColor, Offset(0f, ty), Offset(size.width, ty), strokeWidth = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
                var last: Offset? = null
                minutes.forEachIndexed { i, m ->
                    if (m == null) {
                        last = null
                        return@forEachIndexed
                    }
                    val p = Offset(i * step, y(m))
                    last?.let { drawLine(line.copy(alpha = 0.5f), it, p, strokeWidth = 2.dp.toPx()) }
                    last = p
                }
                minutes.forEachIndexed { i, m ->
                    if (m != null) drawCircle(if (m <= target) ok else late, 4.dp.toPx(), Offset(i * step, y(m)))
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Box(Modifier.padding(start = 44.dp)) { AxisLabels(labels, labelEvery) }
    }
}

/** Labels under a chart: one per bar when there's room, otherwise a few spread across. */
@Composable
private fun AxisLabels(labels: List<String>, every: Int) {
    if (every <= 1) {
        Row(Modifier.fillMaxWidth()) {
            labels.forEach { l ->
                Text(l, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, maxLines = 1, modifier = Modifier.weight(1f))
            }
        }
    } else {
        val shown = labels.indices.filter { it % every == 0 || it == labels.lastIndex }.distinct()
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            shown.forEach { i -> Text(labels[i], style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) }
        }
    }
}

/** A calendar heatmap: one column per week, Monday at the top. */
@Composable
fun Heatmap(weeks: List<List<Result?>>, cell: Dp = 14.dp) {
    val colors = Result.entries.associateWith { resultColor(it) }
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        weeks.forEach { week ->
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                week.forEach { r ->
                    Box(
                        Modifier.size(cell).clip(RoundedCornerShape(3.dp))
                            .background(if (r == null) Color.Transparent else colors.getValue(r)),
                    )
                }
            }
        }
    }
}

/** Colour key for heatmaps. */
@Composable
fun HeatmapKey() {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
        listOf(Result.DONE, Result.LATE, Result.MISSED, Result.SKIPPED, Result.OFF).forEach { r ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(RoundedCornerShape(2.dp)).background(resultColor(r)))
                Spacer(Modifier.size(4.dp))
                Text(if (r == Result.LATE) "Late / not quite" else r.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
