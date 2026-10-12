package space.boscon.kapex.station

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.dp

/**
 * One series, one axis (dataviz: never a dual axis — one chart per measure).
 * 2 px line, recessive grid, hover crosshair with a tooltip, text in text tokens.
 */
@Composable
fun LineChart(title: String, unit: String, points: List<Pair<Long, Double>>, decimals: Int = 1, modifier: Modifier = Modifier) {
    val series = MaterialTheme.colorScheme.primary      // categorical slot 1 of the validated palette, as the theme primary
    val grid = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    val ink = MaterialTheme.colorScheme.onSurface
    var hover by remember { mutableStateOf<Offset?>(null) }
    Column(modifier) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        if (points.size < 2) { Text("no data yet", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant); return }
        val t0 = points.first().first; val t1 = points.last().first
        val lo = points.minOf { it.second }; val hi = points.maxOf { it.second }
        val (ymin, ymax) = if (hi - lo < 1e-9) lo - 1 to hi + 1 else lo - (hi - lo) * 0.08 to hi + (hi - lo) * 0.08
        val hovered = hover?.let { h -> points.minByOrNull { p -> kotlin.math.abs(((p.first - t0).toDouble() / (t1 - t0).coerceAtLeast(1)) - h.x) } }
        Text(if (hovered != null) "${hovered.second.f(decimals)} $unit  at ${ago(hovered.first)}" else "last ${points.last().second.f(decimals)} $unit · ${points.size} samples · ${ago(t0)} to ${ago(t1)}",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Canvas(Modifier.fillMaxWidth().height(160.dp).pointerInput(Unit) {
            awaitPointerEventScope { while (true) { val e = awaitPointerEvent(); val p = e.changes.firstOrNull()?.position
                hover = if (e.type == PointerEventType.Exit || p == null) null else Offset(p.x / size.width, p.y / size.height) } }
        }) {
            val w = size.width; val h = size.height; val padL = 48f; val padB = 18f
            fun x(t: Long) = padL + (w - padL - 8) * ((t - t0).toFloat() / (t1 - t0).coerceAtLeast(1))
            fun y(v: Double) = (h - padB) - (h - padB - 8) * ((v - ymin) / (ymax - ymin)).toFloat()
            for (i in 0..4) { val v = ymin + (ymax - ymin) * i / 4; val yy = y(v); drawLine(grid, Offset(padL, yy), Offset(w - 8, yy), 1f) }
            val path = Path(); points.forEachIndexed { i, (t, v) -> if (i == 0) path.moveTo(x(t), y(v)) else path.lineTo(x(t), y(v)) }
            drawPath(path, series, style = Stroke(width = 2f))
            hovered?.let { (t, v) -> drawLine(ink.copy(alpha = 0.4f), Offset(x(t), 0f), Offset(x(t), h - padB), 1f); drawCircle(series, 5f, Offset(x(t), y(v))); drawCircle(Color.White, 2f, Offset(x(t), y(v))) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("${ymin.f(decimals)} – ${ymax.f(decimals)} $unit", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("hover for values", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

fun ago(t: Long): String { val s = (System.currentTimeMillis() - t) / 1000; return when { s < 60 -> "${s}s ago"; s < 3600 -> "${s / 60}m ${s % 60}s ago"; else -> "${s / 3600}h ${(s % 3600) / 60}m ago" } }
