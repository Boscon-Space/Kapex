package space.boscon.kapex.agent

/** What the agent needs from a model: one deterministic completion of a formatted prompt. */
interface LlmEngine {
    fun complete(prompt: String, maxTokens: Int, timeoutMs: Long): String
}

/** A tool the model may call. No arguments in this first version; the result is text the model reads. */
class Tool(val name: String, val description: String, val run: () -> String)

/**
 * Flight guards (docs/01 §5.2): inference is refused, not attempted, when
 * the payload is outside limits. Values are set by flightd from telemetry;
 * the agent never reads hardware itself.
 */
class Guards(
    var socTempC: Double = 0.0,
    var packVolts: Double = 99.0,
    val maxSocTempC: Double = 80.0,
    val minPackVolts: Double = 9.6,   // 3S Li-ion, 3.2 V/cell
    var busy: Boolean = false,
) {
    /** Reason for refusal, or null when inference may run. */
    fun refusal(): String? = when {
        busy -> "ERR_BUSY"
        socTempC > maxSocTempC -> "ERR_HOT soc=${socTempC}C>${maxSocTempC}C"
        packVolts < minPackVolts -> "ERR_LOWBAT pack=${packVolts}V<${minPackVolts}V"
        else -> null
    }
}

data class AgentResult(val text: String, val toolCalls: List<String>, val refused: Boolean, val reason: String?, val rounds: Int)

/**
 * ChatML (Qwen2.5) conversation; the model calls a tool by answering with
 * exactly `CALL <tool>` on the first line. Small models follow this when the
 * system prompt shows it once. Results are fed back as a user turn and the
 * model answers again, at most [maxToolCalls] times.
 */
class AgentLoop(
    private val engine: LlmEngine,
    tools: List<Tool>,
    private val guards: Guards,
    private val maxToolCalls: Int = 3,
    private val maxTokens: Int = 128,
    private val timeoutMs: Long = 90_000,
    private val systemPrefix: String = "You are HAB-1, the payload computer of a high-altitude balloon. Answer in under 60 words.",
) {
    private val tools = tools.associateBy { it.name }

    private fun systemPrompt(): String {
        if (tools.isEmpty()) return systemPrefix
        val list = tools.values.joinToString("\n") { "- ${it.name}: ${it.description}" }
        return "$systemPrefix\nYou have tools:\n$list\nTo use a tool, reply with exactly one line: CALL <tool name>\nYou will then receive its result and must answer the user. Otherwise answer directly."
    }

    private fun render(turns: List<Pair<String, String>>): String {
        val sb = StringBuilder("<|im_start|>system\n${systemPrompt()}<|im_end|>\n")
        for ((role, text) in turns) sb.append("<|im_start|>$role\n$text<|im_end|>\n")
        sb.append("<|im_start|>assistant\n")
        return sb.toString()
    }

    fun answer(userPrompt: String): AgentResult {
        guards.refusal()?.let { return AgentResult("", emptyList(), refused = true, reason = it, rounds = 0) }
        val turns = mutableListOf("user" to userPrompt)
        val calls = mutableListOf<String>()
        var rounds = 0
        while (true) {
            rounds++
            val out = engine.complete(render(turns), maxTokens, timeoutMs).trim()
            val call = CALL.find(out.lineSequence().firstOrNull() ?: "")?.groupValues?.get(1)
            if (call == null) return AgentResult(out, calls, false, null, rounds)
            val tool = tools[call]
            turns.add("assistant" to "CALL $call")
            if (tool == null) { turns.add("user" to "Tool result: no tool named $call. Answer without it."); continue }
            if (calls.size >= maxToolCalls) { turns.add("user" to "Tool result: tool budget exhausted. Answer now with what you have."); calls.add(call); continue }
            calls.add(call)
            val result = runCatching(tool.run).getOrElse { "error: ${it.message}" }
            turns.add("user" to "Tool result ($call): $result\nNow answer the user's question.")
            if (rounds > maxToolCalls + 1) return AgentResult(out, calls, false, "ERR_LOOP", rounds)
        }
    }

    companion object { private val CALL = Regex("""^\s*CALL\s+([A-Za-z0-9_]+)\s*$""") }
}
