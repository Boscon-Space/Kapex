package space.boscon.hab.probes

import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.exit
import platform.posix.usleep
import space.boscon.hab.drivers.Bmp280
import space.boscon.hab.hal.*

/**
 * baro_probe — bench BMP280 on /dev/i2c-1 (pins 1,3,5,9).
 * Pass: chip id 0x58/0x60; 10 readings 0.2 s apart; pressure 300–1100 hPa
 * and within 2 hPa spread; temperature 0–50 °C. Prints the values so they
 * can be compared with the Heltec's BMP280 on the same desk (docs M4: ±2 hPa).
 */
@OptIn(ExperimentalForeignApi::class)
fun baroProbe(args: Array<String>) {
    val addr = args.getOrNull(0)?.removePrefix("0x")?.toIntOrNull(16) ?: 0x76
    var failures = 0
    fun result(name: String, ok: Boolean, detail: String) { println("${if (ok) "PASS" else "FAIL"}  $name — $detail"); if (!ok) failures++ }
    try {
        LinuxI2cBus("/dev/i2c-1").use { bus ->
            val s = Bmp280(bus, addr)
            val id = s.chipId
            result("id @0x${addr.toString(16)}", id == Bmp280.ID_BMP280 || id == Bmp280.ID_BME280, "0x${id.toString(16)}")
            if (failures > 0) { exit(1); return }
            s.begin(); usleep(100_000u)
            val rs = (1..10).map { usleep(200_000u); s.read() }
            rs.forEach { println("  T=${it.temperatureC} °C  P=${it.pressurePa / 100.0} hPa  (rawT=${it.rawT} rawP=${it.rawP})") }
            val ps = rs.map { it.pressurePa / 100.0 }; val ts = rs.map { it.temperatureC }
            result("pressure range", ps.all { it in 300.0..1100.0 }, "mean ${ps.average()} hPa")
            result("pressure spread", (ps.max() - ps.min()) <= 2.0, "spread ${ps.max() - ps.min()} hPa over 2 s")
            result("temperature range", ts.all { it in 0.0..50.0 }, "mean ${ts.average()} °C")
        }
    } catch (e: HalException) { result("i2c", false, e.message ?: "") }
    println(if (failures == 0) "ALL PASS" else "$failures FAILED"); exit(if (failures == 0) 0 else 1)
}
