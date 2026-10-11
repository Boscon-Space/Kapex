package space.boscon.hab.analysis

/**
 * Burst: an acceleration spike (|a| ≥ [spikeG]) followed within [windowS] by
 * the vertical rate turning negative. Free-fall: |a| ≤ [freeFallG] for at
 * least [freeFallS]. Inputs are timestamped samples; the detector is a small
 * state machine so it works the same on board and in replay.
 */
class BurstDetector(
    private val spikeG: Double = 3.0,
    private val windowS: Double = 20.0,
    private val freeFallG: Double = 0.3,
    private val freeFallS: Double = 0.5,
) {
    data class Event(val kind: Kind, val timeS: Double, val detail: String)
    enum class Kind { SPIKE, BURST, FREE_FALL }

    private var spikeAt = Double.NaN
    private var freeFallStart = Double.NaN
    private var freeFallReported = false
    var burstTimeS: Double = Double.NaN; private set
    val events = mutableListOf<Event>()

    /** @param accelG magnitude of acceleration in g; @param verticalRateMps from the barometer or GPS. */
    fun add(timeS: Double, accelG: Double, verticalRateMps: Double) {
        if (accelG >= spikeG && spikeAt.isNaN()) { spikeAt = timeS; events += Event(Kind.SPIKE, timeS, "${accelG} g") }
        if (!spikeAt.isNaN() && burstTimeS.isNaN()) {
            if (verticalRateMps < -1.0 && timeS - spikeAt <= windowS) { burstTimeS = timeS; events += Event(Kind.BURST, timeS, "spike at ${spikeAt}s, descending ${verticalRateMps} m/s") }
            else if (timeS - spikeAt > windowS) spikeAt = Double.NaN   // spike without descent: not a burst
        }
        if (accelG <= freeFallG) {
            if (freeFallStart.isNaN()) freeFallStart = timeS
            else if (!freeFallReported && timeS - freeFallStart >= freeFallS) { freeFallReported = true; events += Event(Kind.FREE_FALL, freeFallStart, "${timeS - freeFallStart}s under ${freeFallG} g") }
        } else { freeFallStart = Double.NaN; freeFallReported = false }
    }
}

/**
 * Anomaly flags for one channel: STUCK (same value [stuckN] times), RATE
 * (change per second beyond [maxRatePerS]), STALE (no sample for [staleS]).
 * The agent reads these so it says "my UV sensor is stuck" instead of a number.
 */
class ChannelMonitor(val name: String, private val maxRatePerS: Double, private val stuckN: Int = 20, private val staleS: Double = 10.0) {
    private var last = Double.NaN; private var lastT = Double.NaN; private var sameCount = 0
    var stuck = false; private set
    var rateViolation = false; private set
    var lastRate = 0.0; private set
    fun add(timeS: Double, v: Double) {
        if (!last.isNaN() && !lastT.isNaN() && timeS > lastT) {
            lastRate = (v - last) / (timeS - lastT)
            rateViolation = kotlin.math.abs(lastRate) > maxRatePerS
        }
        sameCount = if (v == last) sameCount + 1 else 0
        stuck = sameCount >= stuckN - 1
        last = v; lastT = timeS
    }
    fun stale(nowS: Double) = lastT.isNaN() || nowS - lastT > staleS
    fun flags(nowS: Double): List<String> = buildList {
        if (stale(nowS)) add("STALE") ; if (stuck) add("STUCK"); if (rateViolation) add("RATE ${fmt(lastRate)}/s>${fmt(maxRatePerS)}")
    }
}

internal fun fmt(v: Double, decimals: Int = 1): String {
    var scale = 1.0; repeat(decimals) { scale *= 10 }
    val r = kotlin.math.round(kotlin.math.abs(v) * scale).toLong()
    val ip = r / scale.toLong(); val fp = r % scale.toLong()
    return (if (v < 0) "-" else "") + if (decimals == 0) "$ip" else "$ip.${fp.toString().padStart(decimals, '0')}"
}
