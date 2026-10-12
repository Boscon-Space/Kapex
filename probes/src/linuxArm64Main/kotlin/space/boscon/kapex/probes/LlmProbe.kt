package space.boscon.kapex.probes

import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.exit
import space.boscon.kapex.llm.Llm
import kotlin.time.TimeSource

/**
 * llm_probe — M8. Load the model in-process (mlocked), answer a fixed prompt
 * deterministically, report tokens/s. Pass: model loads, answer non-empty,
 * generation ≥ 8 tok/s (docs/35 §8.4), stopped by eos or max_tokens, never timeout.
 *
 * usage: llm_probe [model.gguf] [max_tokens=128]
 */
@OptIn(ExperimentalForeignApi::class)
fun llmProbe(args: Array<String>) {
    val path = args.getOrNull(0) ?: "/home/hab/models/qwen2.5-1.5b-instruct-q4_k_m.gguf"
    val maxTokens = args.getOrNull(1)?.toIntOrNull() ?: 128
    var failures = 0
    fun result(name: String, ok: Boolean, detail: String) { println("${if (ok) "PASS" else "FAIL"}  $name — $detail"); if (!ok) failures++ }

    val t0 = TimeSource.Monotonic.markNow()
    val llm = try { Llm.load(path, nCtx = 512, threads = 4, mlock = true) } catch (e: Throwable) { println("FAIL  load — ${e.message}"); exit(1); return }
    result("load", true, "${llm.modelParams / 1_000_000} M params, ${llm.modelSizeBytes / 1_048_576} MiB, ctx ${llm.nCtx}, in ${t0.elapsedNow().inWholeMilliseconds} ms")

    val system = "You are the HAB-1 payload computer, a Raspberry Pi 5 flying under a balloon. Answer in under 60 words."
    val prompts = listOf(
        "You are at 21 km altitude, outside pressure 4.7 kPa, temperature -52 C. Describe what you would see and why the sky looks the way it does.",
        "What is 17 times 23? Answer with the number only.",
    )
    for ((i, p) in prompts.withIndex()) {
        val r = llm.generate(Llm.chatPrompt(system, p), maxTokens = maxTokens, timeoutMs = 90_000)
        println("--- prompt ${i + 1}: $p\n${r.text.trim()}\n---")
        result("answer ${i + 1}", r.text.isNotBlank() && r.stoppedBy != "timeout",
            "${r.promptTokens} prompt tok in ${r.promptMs} ms, ${r.generatedTokens} tok in ${r.genMs} ms = ${(r.tokensPerSecond * 10).toInt() / 10.0} tok/s, stopped by ${r.stoppedBy}")
        if (i == 0) result("speed ≥ 8 tok/s", r.tokensPerSecond >= 8.0, "${(r.tokensPerSecond * 10).toInt() / 10.0} tok/s")
        if (i == 1) result("arithmetic", r.text.contains("391"), "expected 391 in: \"${r.text.trim().take(40)}\"")
    }
    llm.close()
    println(if (failures == 0) "ALL PASS" else "$failures FAILED"); exit(if (failures == 0) 0 else 1)
}
