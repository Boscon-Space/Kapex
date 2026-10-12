package space.boscon.kapex.agent

import kotlin.test.*

/** Scripted engine: returns canned completions in order, records the prompts it saw. */
private class ScriptedEngine(vararg replies: String) : LlmEngine {
    private val q = ArrayDeque(replies.toList()); val prompts = mutableListOf<String>()
    override fun complete(prompt: String, maxTokens: Int, timeoutMs: Long): String { prompts += prompt; return q.removeFirstOrNull() ?: "" }
}

class AgentLoopTest {
    private val sensors = Tool("read_sensors", "current pressure and temperature") { "pressure 937.7 hPa, temperature 31.0 C" }

    @Test fun directAnswerWithoutTool() {
        val e = ScriptedEngine("The sky is dark blue.")
        val r = AgentLoop(e, listOf(sensors), Guards()).answer("What colour is the sky?")
        assertEquals("The sky is dark blue.", r.text); assertTrue(r.toolCalls.isEmpty()); assertFalse(r.refused); assertEquals(1, r.rounds)
    }

    @Test fun toolCallFeedsResultBack() {
        val e = ScriptedEngine("CALL read_sensors", "Pressure is 937.7 hPa and it is 31 C.")
        val r = AgentLoop(e, listOf(sensors), Guards()).answer("What is the pressure?")
        assertEquals(listOf("read_sensors"), r.toolCalls); assertEquals(2, r.rounds)
        assertTrue(e.prompts[1].contains("Tool result (read_sensors): pressure 937.7 hPa"))
        assertTrue(e.prompts[1].contains("<|im_start|>assistant\nCALL read_sensors<|im_end|>"))
        assertEquals("Pressure is 937.7 hPa and it is 31 C.", r.text)
    }

    @Test fun guardsRefuseBeforeTheModelIsTouched() {
        val e = ScriptedEngine("should never be used")
        val r = AgentLoop(e, listOf(sensors), Guards(socTempC = 85.0)).answer("hi")
        assertTrue(r.refused); assertTrue(r.reason!!.startsWith("ERR_HOT")); assertTrue(e.prompts.isEmpty())
        assertTrue(AgentLoop(e, listOf(sensors), Guards(packVolts = 9.0)).answer("hi").reason!!.startsWith("ERR_LOWBAT"))
        assertEquals("ERR_BUSY", AgentLoop(e, listOf(sensors), Guards(busy = true)).answer("hi").reason)
    }

    @Test fun unknownToolIsReportedAndLoopContinues() {
        val e = ScriptedEngine("CALL launch_missiles", "I cannot do that; here is the pressure instead.")
        val r = AgentLoop(e, listOf(sensors), Guards()).answer("fire")
        assertTrue(r.toolCalls.isEmpty()); assertTrue(e.prompts[1].contains("no tool named launch_missiles"))
    }

    @Test fun toolBudgetStopsRunawayCalling() {
        val e = ScriptedEngine("CALL read_sensors", "CALL read_sensors", "CALL read_sensors", "CALL read_sensors", "final")
        val r = AgentLoop(e, listOf(sensors), Guards(), maxToolCalls = 2).answer("loop")
        assertTrue(r.rounds <= 5); assertTrue(e.prompts.any { it.contains("tool budget exhausted") })
    }

    @Test fun systemPromptListsTools() {
        val e = ScriptedEngine("ok")
        AgentLoop(e, listOf(sensors), Guards()).answer("x")
        assertTrue(e.prompts[0].contains("- read_sensors: current pressure and temperature"))
        assertTrue(e.prompts[0].contains("CALL <tool name>"))
    }
}
