package space.boscon.kapex.llm

import kotlinx.cinterop.*
import space.boscon.kapex.llm.native.*
import cnames.structs.llama_model
import cnames.structs.llama_context
import cnames.structs.llama_vocab
import kotlin.time.TimeSource

/**
 * llama.cpp in-process: load once (mlocked), generate deterministically with
 * hard caps. The maths kernel is libllama; everything around it — prompt
 * format, sampling policy, token cap, wall-clock timeout, stop rules — is
 * Kotlin (kapex/docs/01, flightd llm/ layer).
 */
@OptIn(ExperimentalForeignApi::class)
class Llm private constructor(
    private val model: CPointer<llama_model>,
    private val ctx: CPointer<llama_context>,
    private val vocab: CPointer<llama_vocab>,
    val nCtx: Int,
) : AutoCloseable {
    data class Result(val text: String, val promptTokens: Int, val generatedTokens: Int, val promptMs: Long, val genMs: Long, val stoppedBy: String) {
        val tokensPerSecond: Double get() = if (genMs > 0) generatedTokens * 1000.0 / genMs else 0.0
    }

    companion object {
        /** ChatML, as Qwen2.5-Instruct expects. */
        fun chatPrompt(system: String, user: String) =
            "<|im_start|>system\n$system<|im_end|>\n<|im_start|>user\n$user<|im_end|>\n<|im_start|>assistant\n"

        fun load(path: String, nCtx: Int = 512, threads: Int = 4, mlock: Boolean = true): Llm = memScoped {
            llama_backend_init()
            val mp = alloc<llama_model_params>(); llama_model_default_params().place(mp.ptr)
            mp.load_mode = if (mlock) LLAMA_LOAD_MODE_MMAP_MLOCK else LLAMA_LOAD_MODE_MMAP
            mp.n_gpu_layers = 0
            val model = llama_model_load_from_file(path, mp.readValue()) ?: error("model load failed: $path")
            val cp = alloc<llama_context_params>(); llama_context_default_params().place(cp.ptr)
            cp.n_ctx = nCtx.toUInt(); cp.n_batch = nCtx.toUInt(); cp.n_threads = threads; cp.n_threads_batch = threads
            cp.no_perf = true
            val ctx = llama_init_from_model(model, cp.readValue()) ?: error("context init failed")
            val vocab = llama_model_get_vocab(model) ?: error("no vocab")
            Llm(model, ctx, vocab, llama_n_ctx(ctx).toInt())
        }
    }

    val modelSizeBytes: Long get() = llama_model_size(model).toLong()
    val modelParams: Long get() = llama_model_n_params(model).toLong()

    private fun tokenize(text: String, addSpecial: Boolean): IntArray = memScoped {
        val bytes = text.encodeToByteArray()
        val max = bytes.size + 16
        val buf = allocArray<llama_tokenVar>(max)
        val n = llama_tokenize(vocab, text, bytes.size, buf, max, addSpecial, true)
        require(n >= 0) { "tokenize needs ${-n} tokens, buffer $max" }
        IntArray(n) { buf[it] }
    }

    private fun piece(tok: Int): String = memScoped {
        val buf = allocArray<ByteVar>(128)
        val n = llama_token_to_piece(vocab, tok, buf, 128, 0, true)
        if (n <= 0) "" else buf.readBytes(n).decodeToString()
    }

    /**
     * Deterministic (greedy) generation with the flight guards: at most
     * [maxTokens] new tokens, [timeoutMs] wall clock over prompt + generation.
     */
    fun generate(prompt: String, maxTokens: Int = 128, timeoutMs: Long = 90_000): Result = memScoped {
        llama_memory_clear(llama_get_memory(ctx), true)
        val promptToks = tokenize(prompt, addSpecial = true)
        require(promptToks.size + maxTokens <= nCtx) { "prompt ${promptToks.size} + $maxTokens exceeds context $nCtx" }
        val clock = TimeSource.Monotonic; val t0 = clock.markNow()

        val pbuf = allocArray<llama_tokenVar>(promptToks.size); promptToks.forEachIndexed { i, t -> pbuf[i] = t }
        check(llama_decode(ctx, llama_batch_get_one(pbuf, promptToks.size)) == 0) { "prompt decode failed" }
        val promptMs = t0.elapsedNow().inWholeMilliseconds

        val chain = llama_sampler_chain_init(llama_sampler_chain_default_params()) ?: error("sampler")
        llama_sampler_chain_add(chain, llama_sampler_init_greedy())
        val sb = StringBuilder(); var generated = 0; var stoppedBy = "max_tokens"
        val one = alloc<llama_tokenVar>()
        val tg = clock.markNow()
        try {
            while (generated < maxTokens) {
                val tok = llama_sampler_sample(chain, ctx, -1)
                if (llama_vocab_is_eog(vocab, tok)) { stoppedBy = "eos"; break }
                sb.append(piece(tok)); generated++
                if (t0.elapsedNow().inWholeMilliseconds > timeoutMs) { stoppedBy = "timeout"; break }
                one.value = tok
                check(llama_decode(ctx, llama_batch_get_one(one.ptr, 1)) == 0) { "decode failed at token $generated" }
            }
        } finally { llama_sampler_free(chain) }
        Result(sb.toString(), promptToks.size, generated, promptMs, tg.elapsedNow().inWholeMilliseconds, stoppedBy)
    }

    override fun close() { llama_free(ctx); llama_model_free(model); llama_backend_free() }
}
