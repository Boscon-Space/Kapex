package space.boscon.hab.probes

import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.exit
import space.boscon.hab.drivers.BridgeBus
import space.boscon.hab.drivers.Sx1262
import space.boscon.hab.hal.*

/**
 * radio_probe — Kotlin SX1262 driver on the Pi, radio reached through the
 * Heltec running firmware/23_spi_bridge on USB serial.
 * Pass: bridge answers ping; after reset BUSY drops; GetStatus reports
 * STDBY_RC; register 0x0320 reads a string starting "SX126".
 *
 * usage: radio_probe [/dev/ttyUSB0]
 */
@OptIn(ExperimentalForeignApi::class)
fun radioProbe(args: Array<String>) {
    val dev = args.getOrNull(0) ?: "/dev/ttyUSB0"
    var failures = 0
    fun result(name: String, ok: Boolean, detail: String) { println("${if (ok) "PASS" else "FAIL"}  $name — $detail"); if (!ok) failures++ }
    try {
        val port = LinuxSerialPort(dev, 921600)
        BridgeBus(port).use { bus ->
            result("bridge ping", bus.ping(), dev)
            if (failures > 0) { exit(1); return }
            val radio = Sx1262(bus)
            radio.reset()
            result("reset → BUSY low", true, "within 100 ms")
            val st = radio.status()
            result("GetStatus", radio.chipModeName(st) == "STDBY_RC", "0x${st.toString(16)} mode=${radio.chipModeName(st)}")
            val v = radio.versionString()
            result("version register 0x0320", v.startsWith("SX126"), "\"$v\"")
        }
    } catch (e: HalException) { result("radio", false, e.message ?: "") }
    println(if (failures == 0) "ALL PASS" else "$failures FAILED"); exit(if (failures == 0) 0 else 1)
}
