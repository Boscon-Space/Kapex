package space.boscon.hab.flightd

import kotlinx.cinterop.*
import platform.posix.*

/**
 * Where prompts come from until the radio uplink (M7) feeds the same queue:
 * a text file dropped into [inDir] is one prompt; the answer is written to
 * [outDir]/<name>.answer. Bench use: `echo "..." > /home/hab/prompts/q1.txt`.
 * Depth-limited like docs/01 §5.2 (max 4 queued; the rest wait on disk).
 */
@OptIn(ExperimentalForeignApi::class)
class PromptQueue(private val inDir: String = "/home/hab/prompts", private val outDir: String = "/log/answers", val maxDepth: Int = 4) {
    init { mkdir(inDir, 0b111_111_101u); mkdir(outDir, 0b111_101_101u) }

    data class Prompt(val name: String, val text: String)

    fun next(): Prompt? {
        val d = opendir(inDir) ?: return null
        try {
            val names = mutableListOf<String>()
            while (true) { val e = readdir(d) ?: break; val n = e.pointed.d_name.toKString(); if (n.endsWith(".txt")) names += n }
            val n = names.sorted().firstOrNull() ?: return null
            val path = "$inDir/$n"
            val text = readFile(path) ?: return null
            unlink(path)
            return Prompt(n.removeSuffix(".txt"), text.trim())
        } finally { closedir(d) }
    }

    fun answer(p: Prompt, text: String) { writeFile("$outDir/${p.name}.answer", text) }

    private fun readFile(path: String): String? {
        val f = fopen(path, "rb") ?: return null
        try { val sb = StringBuilder(); memScoped { val buf = allocArray<ByteVar>(4096); while (true) { val n = fread(buf, 1u, 4096u, f).toInt(); if (n <= 0) break; sb.append(buf.readBytes(n).decodeToString()) } }; return sb.toString() } finally { fclose(f) }
    }
    private fun writeFile(path: String, text: String) { val f = fopen(path, "wb") ?: return; try { val b = text.encodeToByteArray(); b.usePinned { fwrite(it.addressOf(0), 1u, b.size.convert(), f) } } finally { fclose(f) } }
}
