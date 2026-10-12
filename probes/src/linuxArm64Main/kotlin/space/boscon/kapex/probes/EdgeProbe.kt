package space.boscon.kapex.probes

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.*
import platform.posix.exit
import platform.posix.usleep
import space.boscon.kapex.hal.*
import kotlin.time.TimeSource

/**
 * edge_probe — kernel edge events through the GPIO v2 line fd (what the
 * Geiger pulse counter and the SX1262 DIO1 interrupt will use).
 *
 * With the pin 11–13 jumper: GPIO17 is toggled N times from one coroutine
 * while another blocks on readEvent() for GPIO27 (both edges). Pass when the
 * rising-edge count equals N exactly and the falling count equals N, and no
 * seqno gap appears. Half-period default 500 µs (1 kHz toggle rate).
 *
 * usage: edge_probe [pulses=2000] [half_period_us=500] [out=17] [in=27]
 */
@OptIn(ExperimentalForeignApi::class)
fun edgeProbe(args: Array<String>) {
    val pulses = args.getOrNull(0)?.toIntOrNull() ?: 2000
    val halfUs = args.getOrNull(1)?.toIntOrNull() ?: 500
    val outLine = args.getOrNull(2)?.toIntOrNull() ?: 17
    val inLine = args.getOrNull(3)?.toIntOrNull() ?: 27

    val chip = LinuxGpioChip("/dev/gpiochip0")
    val out = chip.requestOutput(outLine, initialHigh = false, consumer = "edge_probe-out")
    val inp = chip.requestEvents(inLine, Edge.BOTH, Bias.PULL_DOWN, consumer = "edge_probe-in")

    var rising = 0; var falling = 0; var seqGaps = 0; var lastSeq = 0
    val expectedEvents = pulses * 2
    val mark = TimeSource.Monotonic.markNow()

    runBlocking {
        val reader = launch(Dispatchers.IO) {
            var n = 0
            while (n < expectedEvents) {
                val ev = inp.readEvent()          // blocking read on the line fd
                if (ev.rising) rising++ else falling++
                if (lastSeq != 0 && ev.seqno != lastSeq + 1) seqGaps++
                lastSeq = ev.seqno
                n++
            }
        }
        val writer = launch(Dispatchers.Default) {
            delay(50)
            repeat(pulses) {
                out.set(true); usleep(halfUs.toUInt())
                out.set(false); usleep(halfUs.toUInt())
            }
        }
        writer.join()
        // give the reader up to 2 s to drain, then give up (it is blocked in read())
        withTimeoutOrNull(2_000) { reader.join() } ?: reader.cancel()
    }
    val elapsedMs = mark.elapsedNow().inWholeMilliseconds
    val ok = rising == pulses && falling == pulses && seqGaps == 0
    println("pulses=$pulses half_period=${halfUs}us  rising=$rising falling=$falling seqGaps=$seqGaps  ${elapsedMs} ms")
    println((if (ok) "PASS" else "FAIL") + "  edge events GPIO$outLine→GPIO$inLine — " +
        if (ok) "every edge seen, in order" else "missed or duplicated edges (jumper on pins 11–13? rate too high?)")
    inp.close(); out.close(); chip.close()
    exit(if (ok) 0 else 1)
}
