package space.boscon.hab.analysis

import kotlin.math.sqrt

/** Fixed-size ring of doubles with O(1) mean/variance over the window. */
class Rolling(val capacity: Int) {
    private val buf = DoubleArray(capacity); private var head = 0; private var n = 0
    private var sum = 0.0; private var sumSq = 0.0
    val size: Int get() = n
    val isFull: Boolean get() = n == capacity
    fun add(v: Double) {
        if (n == capacity) { val old = buf[head]; sum -= old; sumSq -= old * old } else n++
        buf[head] = v; sum += v; sumSq += v * v; head = (head + 1) % capacity
    }
    val mean: Double get() = if (n == 0) Double.NaN else sum / n
    val stdDev: Double get() = if (n < 2) Double.NaN else sqrt(((sumSq - sum * sum / n) / (n - 1)).coerceAtLeast(0.0))
    val last: Double get() = if (n == 0) Double.NaN else buf[(head - 1 + capacity) % capacity]
    fun min(): Double = (0 until n).minOf { buf[it] }
    fun max(): Double = (0 until n).maxOf { buf[it] }
    fun toList(): List<Double> = (0 until n).map { buf[(head - n + it + capacity) % capacity] }
}

/** Mean of a quantity per altitude bin (e.g. 250 m), ascent and descent kept apart. */
class AltitudeProfile(val binM: Double = 250.0) {
    class Bin(var sum: Double = 0.0, var n: Int = 0) { val mean get() = if (n == 0) Double.NaN else sum / n }
    private val ascent = HashMap<Int, Bin>(); private val descent = HashMap<Int, Bin>()
    private var lastAlt = Double.NaN
    var phase: Phase = Phase.ASCENT; private set
    enum class Phase { ASCENT, DESCENT }

    fun add(altitudeM: Double, value: Double, descending: Boolean) {
        phase = if (descending) Phase.DESCENT else Phase.ASCENT
        val map = if (descending) descent else ascent
        val b = map.getOrPut(binIndex(altitudeM)) { Bin() }
        b.sum += value; b.n++; lastAlt = altitudeM
    }
    fun binIndex(altitudeM: Double) = kotlin.math.floor(altitudeM / binM).toInt()
    fun binAltitude(index: Int) = (index + 0.5) * binM
    fun bins(phase: Phase = Phase.ASCENT): Map<Int, Bin> = if (phase == Phase.ASCENT) ascent else descent
}

data class Peak(val altitudeM: Double, val value: Double, val groundValue: Double, val ratio: Double)

/**
 * Regener-Pfotzer maximum: the altitude bin with the highest mean count rate,
 * accepted only if it has ≥ [minSamples] samples, exceeds the ground-level
 * rate by ≥ [minRatio], and is not the top bin seen (a rising edge is not a peak).
 */
fun AltitudeProfile.findPeak(phase: AltitudeProfile.Phase = AltitudeProfile.Phase.ASCENT, minSamples: Int = 5, minRatio: Double = 3.0): Peak? {
    val b = bins(phase).filterValues { it.n >= minSamples }
    if (b.size < 3) return null
    val ground = b.minByOrNull { it.key }!!.value.mean
    val top = b.maxByOrNull { it.key }!!.key
    val (idx, bin) = b.maxByOrNull { it.value.mean } ?: return null
    if (idx == top) return null
    val ratio = if (ground > 0) bin.mean / ground else Double.POSITIVE_INFINITY
    if (ratio < minRatio) return null
    return Peak(binAltitude(idx), bin.mean, ground, ratio)
}
