package space.boscon.hab.flightd

import kotlinx.cinterop.*
import kotlinx.coroutines.*
import platform.posix.*
import space.boscon.hab.agent.*
import space.boscon.hab.analysis.*
import space.boscon.hab.drivers.*
import space.boscon.hab.hal.*
import space.boscon.hab.llm.Llm
import space.boscon.hab.protocol.TelemetryFrame
import kotlin.concurrent.AtomicInt
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * flightd — the payload process. One tick per second:
 *   read BMP280 → analysis → 28-byte frame → beacon TX (Heltec bridge) → NDJSON log → pet watchdog
 * and, on its own thread, the agent answering prompts from the queue with
 * `read_sensors` backed by the last snapshot. Guards: SoC temperature from
 * the kernel, busy flag while the model runs. SIGTERM exits cleanly.
 *
 * usage: flightd [bridge=/dev/ttyUSB0] [model.gguf] [power_dbm=22]
 */
private val stopFlag = AtomicInt(0)

@OptIn(ExperimentalForeignApi::class)
fun main(args: Array<String>) {
    val dev = args.getOrNull(0) ?: "/dev/ttyUSB0"
    val modelPath = args.getOrNull(1) ?: "/home/hab/models/qwen2.5-1.5b-instruct-q4_k_m.gguf"
    val power = args.getOrNull(2)?.toIntOrNull() ?: 22
    signal(SIGTERM, staticCFunction<Int, Unit> { stopFlag.value = 1 })
    signal(SIGINT, staticCFunction<Int, Unit> { stopFlag.value = 1 })

    val log = FlightLog()
    fun info(msg: String) { println(msg); log.write("info", mapOf("msg" to jsonStr(msg))) }
    info("flightd starting: bridge=$dev model=$modelPath power=$power dBm watchdog=${Systemd.watchdogMs} ms")

    // --- radio (the beacon must come up even if the LLM does not) ---
    val port = LinuxSerialPort(dev, 921600); val bus = BridgeBus(port)
    if (!bus.ping()) { info("FATAL bridge not answering on $dev"); exit(2) }
    val radio = Sx1262(bus); radio.beginGfsk(Sx1262.GfskConfig(powerDbm = power))
    info("radio ${radio.versionString()} GFSK 38.4k @ 866.1 MHz, $power dBm")

    // --- sensors ---
    val i2c = runCatching { LinuxI2cBus("/dev/i2c-1") }.getOrNull()
    val bmp = i2c?.let { b -> runCatching { Bmp280(b, 0x76).also { require(it.chipId == Bmp280.ID_BMP280 || it.chipId == Bmp280.ID_BME280); it.begin() } }.getOrNull() }
    info("bmp280: ${if (bmp != null) "ok" else "ABSENT"}")

    // --- analysis state ---
    val pressMon = ChannelMonitor("pressure", maxRatePerS = 200.0)   // Pa/s; 1 hPa/s is already a 35 m/s descent
    val tempMon = ChannelMonitor("temperature", maxRatePerS = 2.0)
    val altRate = Rolling(10); val profile = AltitudeProfile(250.0)
    var lastAlt = Double.NaN; var lastAltT = Double.NaN
    var snapshot: List<Reading> = emptyList(); var socTempC = Double.NaN

    // --- LLM + agent (optional: the beacon flies without it) ---
    val guards = Guards(packVolts = 11.5)
    val llm = runCatching { Llm.load(modelPath, nCtx = 1024, threads = 4, mlock = true) }.getOrElse { info("LLM unavailable: ${it.message}"); null }
    val agent = llm?.let { m ->
        val engine = object : LlmEngine { override fun complete(prompt: String, maxTokens: Int, timeoutMs: Long) = m.generate(prompt, maxTokens, timeoutMs).text }
        AgentLoop(engine, listOf(Tool("read_sensors", "the payload's current pressure, temperature, barometric altitude and sensor health") { snapshot.toPromptText() }), guards)
    }
    info("llm: ${if (llm != null) "ready, ${llm.modelParams / 1_000_000} M params" else "ABSENT"}")
    val queue = PromptQueue()
    Systemd.ready(); Systemd.status("beaconing")

    val clock = TimeSource.Monotonic
    var seq = 0; var sent = 0; var txFail = 0
    val start = clock.markNow()
    var agentJob: Deferred<Pair<PromptQueue.Prompt, AgentResult>>? = null

    runBlocking {
        var nextTick = clock.markNow()
        while (stopFlag.value == 0) {
            // 1. sensors + analysis
            val tNow = start.elapsedNow().inWholeMilliseconds / 1000.0
            socTempC = readSocTemp(); guards.socTempC = socTempC
            var pressPa = 0f; var tempC100 = 0; var altBaro = 0f; var flags = TelemetryFrame.FLAG_POWER_UNKNOWN
            val readings = mutableListOf<Reading>()
            bmp?.let { b ->
                val r = runCatching { b.read() }.getOrNull()
                if (r != null) {
                    pressPa = r.pressurePa.toFloat(); tempC100 = (r.temperatureC * 100).roundToInt()
                    val alt = baroAltitudeM(r.pressurePa); altBaro = alt.toFloat(); flags = flags or TelemetryFrame.FLAG_BMP_OK
                    pressMon.add(tNow, r.pressurePa); tempMon.add(tNow, r.temperatureC)
                    if (!lastAlt.isNaN()) altRate.add((alt - lastAlt) / (tNow - lastAltT))
                    lastAlt = alt; lastAltT = tNow
                    val vr = if (altRate.size > 0) altRate.mean else 0.0
                    profile.add(alt, r.pressurePa, descending = vr < -1.0)
                    readings += Reading("pressure", r.pressurePa / 100.0, "hPa", pressMon.flags(tNow))
                    readings += Reading("temperature", r.temperatureC, "C", tempMon.flags(tNow))
                    readings += Reading("barometric altitude", alt, "m", emptyList(), 0)
                    readings += Reading("vertical rate", vr, "m/s")
                }
            }
            readings += Reading("computer temperature", socTempC, "C")
            snapshot = readings

            // 2. frame + beacon
            val frame = TelemetryFrame(seq and 0xFF, 0f, 0f, 0f, pressPa, tempC100, altBaro, 0, flags, 0)
            val ok = runCatching { radio.transmit(frame.encode()) }.onFailure { info("tx error: ${it.message}") }.isSuccess
            if (ok) sent++ else txFail++
            log.write("tlm", mapOf("seq" to "${frame.seq}", "pressPa" to "$pressPa", "tempC" to "${tempC100 / 100.0}", "altBaro" to "$altBaro",
                "soc" to "$socTempC", "tx" to "$ok", "flags" to "$flags"))
            if (seq % 30 == 0) println("#$seq ${snapshot.toPromptText()} | tx ok $sent fail $txFail | log ${log.records} rec ${log.currentPath.substringAfterLast('/')}")
            seq++

            // 3. agent: collect a finished answer, then maybe start the next prompt
            agentJob?.let { job -> if (job.isCompleted) {
                val (p, r) = job.await(); agentJob = null
                val text = if (r.refused) "REFUSED ${r.reason}" else r.text
                queue.answer(p, text)
                log.write("answer", mapOf("prompt" to jsonStr(p.name), "refused" to "${r.refused}", "tools" to jsonStr(r.toolCalls.joinToString(",")), "rounds" to "${r.rounds}", "text" to jsonStr(text)))
                info("answered ${p.name}: ${text.take(80)}"); Systemd.status("beaconing")
            } }
            if (agentJob == null && agent != null) queue.next()?.let { p ->
                log.write("prompt", mapOf("name" to jsonStr(p.name), "text" to jsonStr(p.text)))
                info("prompt ${p.name}: ${p.text.take(80)}"); Systemd.status("answering ${p.name}")
                val refusal = guards.refusal()
                if (refusal != null) { queue.answer(p, "REFUSED $refusal"); log.write("answer", mapOf("prompt" to jsonStr(p.name), "refused" to "true", "text" to jsonStr(refusal))); info("refused ${p.name}: $refusal") }
                else agentJob = async(Dispatchers.Default) { p to agent.answer(p.text) }   // one at a time: the queue waits while a job runs
            }

            // 4. watchdog + pacing
            Systemd.pet()
            nextTick += 1000.milliseconds
            val wait = -nextTick.elapsedNow().inWholeMilliseconds
            if (wait > 0) delay(wait) else nextTick = clock.markNow()
        }
        agentJob?.cancel()
    }
    info("flightd stopping: $sent frames sent, $txFail failed, ${log.records} log records")
    log.close(); bus.close(); i2c?.close(); llm?.close()
}

@OptIn(ExperimentalForeignApi::class)
private fun readSocTemp(): Double {
    val f = fopen("/sys/class/thermal/thermal_zone0/temp", "r") ?: return Double.NaN
    try { memScoped { val buf = allocArray<ByteVar>(32); val s = fgets(buf, 32, f)?.toKString()?.trim() ?: return Double.NaN; return (s.toDoubleOrNull() ?: return Double.NaN) / 1000.0 } } finally { fclose(f) }
}
