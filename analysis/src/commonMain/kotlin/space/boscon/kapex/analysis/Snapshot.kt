package space.boscon.kapex.analysis

/** One reading the agent may see: value, unit, flags. NaN value = not available. */
data class Reading(val name: String, val value: Double, val unit: String, val flags: List<String> = emptyList(), val decimals: Int = 1)

/** What `read_sensors` returns to the model: short, numeric, flagged, never invented. */
fun List<Reading>.toPromptText(): String = joinToString("; ") { r ->
    val v = if (r.value.isNaN()) "unavailable" else "${fmt(r.value, r.decimals)} ${r.unit}"
    if (r.flags.isEmpty()) "${r.name} $v" else "${r.name} $v [${r.flags.joinToString(",")}]"
}

/**
 * Geometric altitude in m from pressure: US Standard Atmosphere 1976, three layers,
 * the same model as TelemetryFrame.baroAltitudeM and the transmitter sketches.
 * Standard sea level only: a QNH offset has no meaning above the first layer.
 */
fun baroAltitudeM(pressurePa: Double): Double {
    val h = when {
        pressurePa >= 22632.06 -> 44330.77 * (1.0 - kotlin.math.exp(0.1902632 * kotlin.math.ln(pressurePa / 101325.0)))
        pressurePa >= 5474.89 -> 11000.0 + 6341.618 * kotlin.math.ln(22632.06 / pressurePa)
        else -> 20000.0 + 216650.0 * (kotlin.math.exp(-0.02927126 * kotlin.math.ln(pressurePa / 5474.89)) - 1.0)
    }
    return 6356766.0 * h / (6356766.0 - h)
}
