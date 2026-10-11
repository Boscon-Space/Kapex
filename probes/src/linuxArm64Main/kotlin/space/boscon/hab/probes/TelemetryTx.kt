package space.boscon.hab.probes

import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.exit
import platform.posix.usleep
import space.boscon.hab.drivers.*
import space.boscon.hab.hal.*
import space.boscon.hab.protocol.TelemetryFrame
import kotlin.math.roundToInt
import kotlin.time.TimeSource
import kotlin.time.Duration.Companion.seconds

/**
 * telemetry_tx — the Kotlin counterpart of firmware/08_telemetry_tx.
 *
 * Reads the BMP280 on the Pi's I²C, the NEO-6M through the Heltec bridge's
 * GPS forwarding, packs the 28-byte Telemetry frame and transmits it once a
 * second on 866.1 MHz GFSK 38.4 kb/s through the SX1262 in the Heltec.
 * The RX Heltec running 09_telemetry_rx shows the packets on its display.
 *
 * usage: telemetry_tx [count=0 (forever)] [power_dbm=22] [/dev/ttyUSB0]
 * Prints one line per packet in the receiver's own format so the two can be
 * compared side by side. Exit code 1 if the radio could not be configured.
 */
/** Fixed-point decimal formatting; Kotlin/Native has no String.format. */
private fun fx(v: Double, decimals: Int): String {
    var scale = 1.0; repeat(decimals) { scale *= 10.0 }
    val r = kotlin.math.round(kotlin.math.abs(v) * scale).toLong()
    val ip = r / scale.toLong(); val fp = r % scale.toLong()
    val sign = if (v < 0) "-" else ""
    return if (decimals == 0) "$sign$ip" else "$sign$ip.${fp.toString().padStart(decimals, '0')}"
}

@OptIn(ExperimentalForeignApi::class)
fun telemetryTx(args: Array<String>) {
    val count = args.getOrNull(0)?.toIntOrNull() ?: 0
    val power = args.getOrNull(1)?.toIntOrNull() ?: 22
    val dev = args.getOrNull(2) ?: "/dev/ttyUSB0"

    val port = try { LinuxSerialPort(dev, 921600) } catch (e: HalException) { println("FAIL  ${e.message} — is the bridge Heltec plugged in?"); exit(1); return }
    val bus = BridgeBus(port)
    if (!bus.ping()) { println("FAIL  bridge ping on $dev"); exit(1); return }
    val radio = Sx1262(bus)
    try {
        radio.beginGfsk(Sx1262.GfskConfig(powerDbm = power))
    } catch (e: Exception) { println("FAIL  radio config — ${e.message}"); exit(1); return }
    println("radio: ${radio.versionString()} GFSK 38.4 kb/s @ 866.1 MHz, $power dBm, sync 2D D4, CRC-16 — status 0x${radio.status().toString(16)}")

    val i2c = try { LinuxI2cBus("/dev/i2c-1") } catch (e: HalException) { null }
    val bmp = i2c?.let { b -> runCatching { Bmp280(b, 0x76).also { require(it.chipId == Bmp280.ID_BMP280 || it.chipId == Bmp280.ID_BME280); it.begin() } }.getOrNull() }
    println("bmp280: ${if (bmp != null) "ok @0x76" else "NOT FOUND — frames carry no pressure"}")

    val gps = NmeaParser()
    var seq = 0; var sent = 0; var failed = 0
    val clock = TimeSource.Monotonic
    var nextSend = clock.markNow()
    try { while (count == 0 || sent + failed < count) {
        // poll the bridge for GPS bytes ~10x per second between sends
        while (nextSend.elapsedNow().inWholeMilliseconds < 0) { gps.feed(bus.drainGps()); usleep(100_000u) }
        gps.feed(bus.drainGps())
        nextSend = clock.markNow() + 1.seconds

        var flags = TelemetryFrame.FLAG_POWER_UNKNOWN          // no battery on the Pi: like 08's "not established"
        var lat = 0f; var lon = 0f; var altGps = 0f; var sats = 0
        if (gps.hasFix) { flags = flags or TelemetryFrame.FLAG_GPS_FIX; lat = gps.lat!!.toFloat(); lon = gps.lon!!.toFloat() }
        gps.altitudeM?.let { altGps = it.toFloat() }
        gps.satellites?.let { sats = it }
        if (sats >= 4) flags = flags or TelemetryFrame.FLAG_FIX_3D
        var pressPa = 0f; var tempC100 = 0; var altBaro = 0f
        bmp?.let { b ->
            val r = b.read()
            pressPa = r.pressurePa.toFloat(); tempC100 = (r.temperatureC * 100.0).roundToInt()
            altBaro = TelemetryFrame.baroAltitudeM(pressPa); flags = flags or TelemetryFrame.FLAG_BMP_OK
        }
        val frame = TelemetryFrame(seq = seq and 0xFF, lat = lat, lon = lon, altGps = altGps, pressPa = pressPa,
            tempC100 = tempC100, altBaro = altBaro, sats = sats, flags = flags, vbatMv = 0)
        val t0 = clock.markNow()
        val ok = try { radio.transmit(frame.encode()); true } catch (e: HalException) { println("  tx error: ${e.message}"); false }
        val ms = t0.elapsedNow().inWholeMilliseconds
        if (ok) sent++ else failed++
        println("#${frame.seq.toString().padStart(3)} ${if (flags and 1 != 0) "FIX " else "----"} " +
            "${fx(lat.toDouble(), 6).padStart(10)},${fx(lon.toDouble(), 6).padStart(10)} alt ${fx(altGps.toDouble(), 1).padStart(6)} sats ${sats.toString().padStart(2)} | " +
            "${fx(pressPa.toDouble(), 1).padStart(7)} Pa ${fx(tempC100 / 100.0, 2).padStart(5)} C ${fx(altBaro.toDouble(), 1).padStart(6)} m | " +
            "${if (ok) "TX ok" else "TX FAIL"} in $ms ms | gps ${gps.sentences} sent, ${gps.badChecksums} bad cs")
        seq++
    } } catch (e: HalException) { println("FATAL  ${e.message} — exiting for systemd to restart"); exit(1); return }
    println("sent $sent, failed $failed")
    bus.close(); i2c?.close()
    exit(if (failed == 0) 0 else 1)
}
