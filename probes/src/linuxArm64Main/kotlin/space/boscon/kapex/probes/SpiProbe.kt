package space.boscon.kapex.probes

import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.exit
import space.boscon.kapex.hal.*

/**
 * spi_probe — spidev loopback. NEEDS A JUMPER between physical pins 19 (MOSI,
 * GPIO10) and 21 (MISO, GPIO9). Sends 256 bytes 0..255 then 1 KiB of a
 * pseudo-random pattern at 1, 4 and 8 MHz; pass when every byte comes back.
 * Without the jumper MISO floats and this fails.
 *
 * usage: spi_probe [/dev/spidev0.0]
 */
@OptIn(ExperimentalForeignApi::class)
fun spiProbe(args: Array<String>) {
    val dev = args.getOrNull(0) ?: "/dev/spidev0.0"
    var failures = 0
    fun result(name: String, ok: Boolean, detail: String) { println("${if (ok) "PASS" else "FAIL"}  $name — $detail"); if (!ok) failures++ }
    for (hz in listOf(1_000_000, 4_000_000, 8_000_000)) {
        try {
            LinuxSpiBus(dev, speedHz = hz).use { spi ->
                val ramp = ByteArray(256) { it.toByte() }
                val back = spi.transfer(ramp)
                var seed = 0x12345678; val rnd = ByteArray(1024) { seed = seed * 1103515245 + 12345; (seed shr 16).toByte() }
                val back2 = spi.transfer(rnd)
                val ok = back.contentEquals(ramp) && back2.contentEquals(rnd)
                val mism = back.indices.count { back[it] != ramp[it] } + back2.indices.count { back2[it] != rnd[it] }
                result("loopback @${hz / 1_000_000} MHz", ok, if (ok) "1280/1280 bytes matched" else "$mism/1280 bytes wrong (jumper pins 19–21? first rx byte 0x${(back[0].toInt() and 0xFF).toString(16)})")
            }
        } catch (e: HalException) { result("spi @$hz", false, e.message ?: "") }
    }
    println(if (failures == 0) "ALL PASS" else "$failures FAILED"); exit(if (failures == 0) 0 else 1)
}
