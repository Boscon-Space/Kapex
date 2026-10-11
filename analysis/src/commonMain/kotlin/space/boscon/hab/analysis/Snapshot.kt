package space.boscon.hab.analysis

/** One reading the agent may see: value, unit, flags. NaN value = not available. */
data class Reading(val name: String, val value: Double, val unit: String, val flags: List<String> = emptyList(), val decimals: Int = 1)

/** What `read_sensors` returns to the model: short, numeric, flagged, never invented. */
fun List<Reading>.toPromptText(): String = joinToString("; ") { r ->
    val v = if (r.value.isNaN()) "unavailable" else "${fmt(r.value, r.decimals)} ${r.unit}"
    if (r.flags.isEmpty()) "${r.name} $v" else "${r.name} $v [${r.flags.joinToString(",")}]"
}

/** Barometric altitude from pressure, ISA: the same formula the telemetry frame uses. */
fun baroAltitudeM(pressurePa: Double, seaLevelHpa: Double = 1013.25): Double =
    44330.0 * (1.0 - kotlin.math.exp(0.1903 * kotlin.math.ln(pressurePa / 100.0 / seaLevelHpa)))
