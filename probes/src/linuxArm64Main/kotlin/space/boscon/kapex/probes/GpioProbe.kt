package space.boscon.kapex.probes

import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.exit
import platform.posix.usleep
import space.boscon.kapex.hal.*

/**
 * gpio_probe — first Kotlin/Native code to touch the Pi 5.
 *
 * Pass conditions (each can fail):
 *   1. /dev/gpiochip0 reports label "pinctrl-rp1" (we are on the header chip)
 *   2. loopback: GPIO17 (pin 11) driven, GPIO27 (pin 13) read back, 20 toggles,
 *      every sample matches. NEEDS A JUMPER WIRE between physical pins 11 and 13.
 *      Without the jumper this fails — that is the point.
 *   3. /dev/i2c-1 scan lists every ACKing address; if 0x76 or 0x77 answers,
 *      read BMP280 id register 0xD0 and expect 0x58 (0x60 = BME280).
 *
 * usage: gpio_probe [out=17] [in=27]
 */
@OptIn(ExperimentalForeignApi::class)
fun gpioProbe(args: Array<String>) {
    val outLine = args.getOrNull(0)?.toIntOrNull() ?: 17
    val inLine = args.getOrNull(1)?.toIntOrNull() ?: 27
    var failures = 0
    fun result(name: String, ok: Boolean, detail: String) {
        println("${if (ok) "PASS" else "FAIL"}  $name — $detail")
        if (!ok) failures++
    }

    // 1. chip identity
    val chip = try { LinuxGpioChip("/dev/gpiochip0") } catch (e: HalException) {
        println("FAIL  open gpiochip0 — ${e.message}"); exit(2); return
    }
    val info = chip.info()
    result("chip", info.label == "pinctrl-rp1", "name=${info.name} label=${info.label} lines=${info.lines}")

    // 2. loopback
    try {
        chip.requestOutput(outLine, initialHigh = false, consumer = "gpio_probe-out").use { out ->
            chip.requestInput(inLine, Bias.PULL_DOWN, consumer = "gpio_probe-in").use { inp ->
                var mismatches = 0
                repeat(20) { i ->
                    val level = i % 2 == 0
                    out.set(level)
                    usleep(2_000u)
                    if (inp.get() != level) mismatches++
                }
                out.set(false)
                result("loopback GPIO$outLine→GPIO$inLine", mismatches == 0,
                    if (mismatches == 0) "20/20 samples matched" else "$mismatches/20 mismatched (jumper between pins 11 and 13?)")
            }
        }
    } catch (e: HalException) {
        result("loopback", false, e.message ?: "")
    }

    // 3. I2C
    try {
        LinuxI2cBus("/dev/i2c-1").use { bus ->
            val found = bus.scan()
            result("i2c scan", true, if (found.isEmpty()) "no devices" else found.joinToString { "0x" + it.toString(16) })
            for (addr in listOf(0x76, 0x77)) if (addr in found) {
                val id = bus.readRegister(addr, 0xD0)
                val name = when (id) { 0x58 -> "BMP280"; 0x60 -> "BME280"; else -> "unknown" }
                result("bosch id @0x${addr.toString(16)}", id == 0x58 || id == 0x60, "reg 0xD0 = 0x${id.toString(16)} ($name)")
            }
        }
    } catch (e: HalException) {
        result("i2c", false, e.message ?: "")
    }

    chip.close()
    println(if (failures == 0) "ALL PASS" else "$failures FAILED")
    exit(if (failures == 0) 0 else 1)
}
