package space.boscon.hab.analysis

import kotlin.math.exp
import kotlin.test.*

class AnalysisTest {
    @Test fun rollingStats() {
        val r = Rolling(4); listOf(1.0, 2.0, 3.0, 4.0, 5.0).forEach { r.add(it) }
        assertEquals(3.5, r.mean, 1e-12); assertEquals(5.0, r.last); assertEquals(2.0, r.min()); assertTrue(r.isFull)
        assertEquals(1.2909944, r.stdDev, 1e-6); assertEquals(listOf(2.0, 3.0, 4.0, 5.0), r.toList())
    }

    /** Synthetic ascent: count rate 0.2 cps at ground rising to a Gaussian peak of 14 cps at 19 km. */
    @Test fun pfotzerPeakFoundOnSyntheticAscent() {
        val p = AltitudeProfile(250.0)
        var alt = 0.0
        while (alt < 30_000) {                       // 5 m/s ascent, one sample per second
            val rate = 0.2 + 14.0 * exp(-((alt - 19_000) / 4_000) * ((alt - 19_000) / 4_000))
            p.add(alt, rate, descending = false); alt += 5.0
        }
        val peak = p.findPeak(); assertNotNull(peak)
        assertEquals(19_000.0, peak.altitudeM, 250.0)
        assertEquals(14.2, peak.value, 0.3); assertTrue(peak.ratio > 50, "ratio ${peak.ratio}")
    }

    @Test fun noPeakWhileStillRising() {
        val p = AltitudeProfile(250.0)
        var alt = 0.0
        while (alt < 10_000) { p.add(alt, 0.2 + alt / 1000.0, false); alt += 5.0 }   // monotonic rise
        assertNull(p.findPeak())
    }

    @Test fun burstThenDescentIsABurst() {
        val d = BurstDetector()
        var t = 0.0
        while (t < 100) { d.add(t, 1.0, 5.0); t += 1.0 }           // steady ascent
        d.add(100.0, 4.5, 5.0)                                        // the pop
        d.add(101.0, 0.1, -2.0); d.add(101.5, 0.1, -10.0); d.add(102.0, 0.1, -20.0)   // free-fall, descending
        d.add(103.0, 0.2, -30.0)
        assertEquals(listOf(BurstDetector.Kind.SPIKE, BurstDetector.Kind.BURST, BurstDetector.Kind.FREE_FALL), d.events.map { it.kind })
        assertEquals(101.0, d.burstTimeS)
    }

    @Test fun spikeWithoutDescentIsNotABurst() {
        val d = BurstDetector(windowS = 5.0)
        d.add(0.0, 4.0, 5.0); for (t in 1..10) d.add(t.toDouble(), 1.0, 5.0)
        assertEquals(listOf(BurstDetector.Kind.SPIKE), d.events.map { it.kind }); assertTrue(d.burstTimeS.isNaN())
    }

    @Test fun channelFlags() {
        val m = ChannelMonitor("uv", maxRatePerS = 1.0, stuckN = 3, staleS = 5.0)
        m.add(0.0, 1.0); m.add(1.0, 1.0); m.add(2.0, 1.0)
        assertTrue(m.stuck); assertEquals(listOf("STUCK"), m.flags(2.0))
        m.add(3.0, 9.0); assertTrue(m.rateViolation); assertFalse(m.stuck)
        assertEquals(listOf("RATE 8.0/s>1.0"), m.flags(3.0)); assertEquals(listOf("STALE", "RATE 8.0/s>1.0"), m.flags(20.0))
    }

    @Test fun promptTextIsHonest() {
        val txt = listOf(Reading("pressure", 937.71, "hPa"), Reading("uv index", Double.NaN, ""), Reading("spin", 0.42, "rev/s", listOf("STUCK"), 2)).toPromptText()
        assertEquals("pressure 937.7 hPa; uv index unavailable; spin 0.42 rev/s [STUCK]", txt)
    }

    @Test fun baroAltitude() { assertEquals(648.86, baroAltitudeM(93771.0), 0.5); assertEquals(0.0, baroAltitudeM(101325.0), 0.01) }
}
