package space.boscon.kapex.station

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Physical pin → (label, GPIO or null). Same table as docs/pi5-header.html. */
val HEADER: Map<Int, Pair<String, Int?>> = mapOf(
    1 to ("3V3" to null), 2 to ("5V" to null), 3 to ("SDA1" to 2), 4 to ("5V" to null), 5 to ("SCL1" to 3), 6 to ("GND" to null),
    7 to ("GPIO4" to 4), 8 to ("TXD" to 14), 9 to ("GND" to null), 10 to ("RXD" to 15), 11 to ("GPIO17" to 17), 12 to ("GPIO18" to 18),
    13 to ("GPIO27" to 27), 14 to ("GND" to null), 15 to ("GPIO22" to 22), 16 to ("GPIO23" to 23), 17 to ("3V3" to null), 18 to ("GPIO24" to 24),
    19 to ("MOSI" to 10), 20 to ("GND" to null), 21 to ("MISO" to 9), 22 to ("GPIO25" to 25), 23 to ("SCLK" to 11), 24 to ("CE0" to 8),
    25 to ("GND" to null), 26 to ("CE1" to 7), 27 to ("ID_SD" to 0), 28 to ("ID_SC" to 1), 29 to ("GPIO5" to 5), 30 to ("GND" to null),
    31 to ("GPIO6" to 6), 32 to ("GPIO12" to 12), 33 to ("GPIO13" to 13), 34 to ("GND" to null), 35 to ("GPIO19" to 19), 36 to ("GPIO16" to 16),
    37 to ("GPIO26" to 26), 38 to ("GPIO20" to 20), 39 to ("GND" to null), 40 to ("GPIO21" to 21),
)
/** What the bench expects on each GPIO today (from the wiring in kapex/docs). */
val BENCH_USE = mapOf(2 to "BMP280 SDA", 3 to "BMP280 SCL", 17 to "loopback out → 27", 27 to "loopback in ← 17", 10 to "SPI MOSI → 21", 9 to "SPI MISO ← 19", 11 to "SPI SCLK", 8 to "SPI CE0", 14 to "GPS TX (future)", 15 to "GPS RX (future)")

@Composable
fun PinsPanel(snap: PiSnapshot?) {
    if (snap == null || !snap.reachable) { Unavailable(snap); return }
    Column(Modifier.verticalScroll(rememberScrollState())) {
        Text("40-pin header — live from pinctrl", style = MaterialTheme.typography.titleLarge)
        Text("Pin 1 (square pad) is at the microSD end. Outer row = even pins, inner row = odd. Colour = live level (green high, grey low); label = kernel function.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (snap.pins.isEmpty()) Text("pinctrl returned nothing — is raspi-utils installed on the Pi?", color = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(10.dp))
        Column(Modifier.background(Palette.pcb, RoundedCornerShape(10.dp)).padding(12.dp)) {
            Text("board edge ↑", color = Color.White, style = MaterialTheme.typography.labelSmall)
            Row { for (p in 2..40 step 2) PinCell(p, snap) }
            Row { for (p in 1..39 step 2) PinCell(p, snap) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("◀ microSD end", color = Color.White, style = MaterialTheme.typography.labelSmall); Text("USB / Ethernet end ▶", color = Color.White, style = MaterialTheme.typography.labelSmall) }
        }
        SectionTitle("GPIO lines (kernel view)")
        Row(Modifier.padding(vertical = 2.dp)) { listOf("GPIO" to 60, "pin" to 50, "function" to 110, "level" to 60, "pull" to 60, "kernel says" to 180, "bench use" to 200).forEach { (h, w) -> Text(h, Modifier.width(w.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
        for (g in snap.pins.keys.sorted()) { val pi = snap.pins[g]!!; val phys = HEADER.entries.firstOrNull { it.value.second == g }?.key
            Row(Modifier.padding(vertical = 1.dp)) {
                Text("GPIO$g", Modifier.width(60.dp)); Text(phys?.toString() ?: "—", Modifier.width(50.dp)); Text(fnName(pi.function), Modifier.width(110.dp))
                Text(pi.level, Modifier.width(60.dp), color = if (pi.level == "hi") Palette.good else MaterialTheme.colorScheme.onSurfaceVariant)
                Text(pi.pull.ifBlank { "—" }, Modifier.width(60.dp)); Text(pi.alias, Modifier.width(180.dp), style = MaterialTheme.typography.bodySmall); Text(BENCH_USE[g] ?: "", Modifier.width(200.dp), style = MaterialTheme.typography.bodySmall)
            } }
    }
}

fun fnName(f: String) = when (f) { "ip" -> "input"; "op" -> "output"; "no" -> "none"; else -> if (f.startsWith("a")) "alt ${f.drop(1)}" else f }

@Composable
private fun PinCell(phys: Int, snap: PiSnapshot) {
    val (label, gpio) = HEADER[phys]!!; val pi = gpio?.let { snap.pins[it] }
    val bg = when { label == "GND" -> Palette.gnd; label == "5V" -> Palette.v5; label == "3V3" -> Palette.v33
        pi == null -> Color(0xFF8E98A6); pi.level == "hi" -> Palette.good; else -> Color(0xFF4A5563) }
    val fg = if (label == "3V3") Color.Black else Color.White
    Column(Modifier.padding(2.dp).width(42.dp).height(46.dp).background(bg, RoundedCornerShape(if (phys == 1) 0.dp else 5.dp)).border(if (gpio != null && BENCH_USE.containsKey(gpio)) 2.dp else 0.dp, Palette.warn, RoundedCornerShape(5.dp)), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("$phys", color = fg, style = MaterialTheme.typography.labelMedium); Text(label, color = fg, style = MaterialTheme.typography.labelSmall)
        if (pi != null) Text(fnName(pi.function).take(6), color = fg, style = MaterialTheme.typography.labelSmall)
    }
}
