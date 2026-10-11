package space.boscon.hab.drivers

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The raw values RadioLib 7.7.1 computes for the link in firmware/08; if these drift the receiver goes deaf. */
class Sx1262Test {
    @Test fun bitRate38k4() = assertEquals(26666, Sx1262.bitRateRaw(38.4f))
    @Test fun freqDev40kHz() = assertEquals(41943, Sx1262.freqDevRaw(40.0f))
    @Test fun frf866p1() {
        // 866.1 MHz * 2^25 / 32 MHz = 866.1 * 1,048,576 = 908,171,673.6. RadioLib does this in
        // float32 and lands at 908,171,648 (26 LSB = 24 Hz low); we mirror that exactly so the
        // register value equals the C firmware's. 1 LSB = 32e6 / 2^25 = 0.954 Hz.
        val frf = Sx1262.frfRaw(866.1f)
        assertEquals(908_171_648L, frf)
        assertTrue(kotlin.math.abs(frf - 908_171_673L) < 64, "frf=$frf")
    }
    @Test fun paTableTop() {
        assertEquals(Triple(4, 7, 22), Sx1262.PA_OPT[22 + 9])
        assertEquals(Triple(2, 1, 11), Sx1262.PA_OPT[0 + 9])
        assertEquals(32, Sx1262.PA_OPT.size)
    }
}
