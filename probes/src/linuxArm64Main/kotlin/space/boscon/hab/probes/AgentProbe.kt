package space.boscon.hab.probes

import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.exit
import space.boscon.hab.agent.*
import space.boscon.hab.drivers.Bmp280
import space.boscon.hab.hal.HalException
import space.boscon.hab.hal.LinuxI2cBus
import space.boscon.hab.llm.Llm
import kotlin.time.TimeSource

/**
 * agent_probe — M9. The real model + the real BMP280 behind a `read_sensors`
 * tool. Pass: (1) a question about the current pressure makes the model call
 * the tool and the answer quotes the live reading; (2) a question needing no
 * tool is answered directly; (3) with the SoC-temperature guard tripped the
 * request is refused without touching the model.
 */
@OptIn(ExperimentalForeignApi::class)
fun agentProbe(args: Array<String>) {
    val path = args.getOrNull(0) ?: "/home/hab/models/qwen2.5-1.5b-instruct-q4_k_m.gguf"
    var failures = 0
    fun result(name: String, ok: Boolean, detail: String) { println("${if (ok) "PASS" else "FAIL"}  $name — $detail"); if (!ok) failures++ }

    val i2c = LinuxI2cBus("/dev/i2c-1"); val bmp = Bmp280(i2c, 0x76); bmp.begin()
    var lastReading = ""
    val readSensors = Tool("read_sensors", "the payload's current barometric pressure in hPa and temperature in C") {
        val r = bmp.read(); lastReading = "pressure ${((r.pressurePa / 10).toInt() / 10.0)} hPa, temperature ${((r.temperatureC * 10).toInt() / 10.0)} C"; lastReading
    }
    val llm = Llm.load(path, nCtx = 1024, threads = 4, mlock = true)
    val engine = object : LlmEngine { override fun complete(prompt: String, maxTokens: Int, timeoutMs: Long) = llm.generate(prompt, maxTokens, timeoutMs).text }
    val guards = Guards(socTempC = 50.0, packVolts = 11.5)
    val agent = AgentLoop(engine, listOf(readSensors), guards, maxToolCalls = 3, maxTokens = 128)
    val clock = TimeSource.Monotonic

    var t = clock.markNow()
    val r1 = agent.answer("What is the air pressure around you right now, in hPa?")
    println("--- Q1 → calls=${r1.toolCalls} rounds=${r1.rounds} ${t.elapsedNow().inWholeMilliseconds} ms\n${r1.text}\n---")
    val hPa = lastReading.substringAfter("pressure ").substringBefore(" hPa").substringBefore(".")
    result("tool called", r1.toolCalls == listOf("read_sensors"), "calls=${r1.toolCalls}")
    result("answer quotes the live reading", hPa.isNotEmpty() && r1.text.contains(hPa), "live $lastReading; answer contains '$hPa': ${r1.text.contains(hPa)}")

    t = clock.markNow()
    val r2 = agent.answer("In one sentence, why does the sky look black from 30 km up?")
    println("--- Q2 → calls=${r2.toolCalls} rounds=${r2.rounds} ${t.elapsedNow().inWholeMilliseconds} ms\n${r2.text}\n---")
    result("direct answer, no tool", r2.toolCalls.isEmpty() && r2.text.isNotBlank(), "calls=${r2.toolCalls}, ${r2.text.length} chars")

    guards.socTempC = 86.0
    t = clock.markNow()
    val r3 = agent.answer("What is the pressure?")
    result("guard refuses when hot", r3.refused && r3.reason!!.startsWith("ERR_HOT") && t.elapsedNow().inWholeMilliseconds < 50,
        "reason=${r3.reason} in ${t.elapsedNow().inWholeMilliseconds} ms, rounds=${r3.rounds}")

    llm.close(); i2c.close()
    println(if (failures == 0) "ALL PASS" else "$failures FAILED"); exit(if (failures == 0) 0 else 1)
}
