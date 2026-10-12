package space.boscon.kapex.station

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Properties
import java.util.concurrent.TimeUnit

/** Persisted settings: ~/.hab1-station.properties. Never holds passwords. */
object Settings {
    private val file = File(System.getProperty("user.home"), ".hab1-station.properties")
    private val props = Properties().apply { if (file.exists()) file.inputStream().use { load(it) } }
    var host: String get() = props.getProperty("host", "hab1.local"); set(v) { props.setProperty("host", v); save() }
    var user: String get() = props.getProperty("user", "hab"); set(v) { props.setProperty("user", v); save() }
    var key: String get() = props.getProperty("key", File(System.getProperty("user.home"), ".ssh/hab1_pi").path); set(v) { props.setProperty("key", v); save() }
    var repo: String get() = props.getProperty("repo", findRepo()); set(v) { props.setProperty("repo", v); save() }
    private fun save() = file.outputStream().use { props.store(it, "HAB-1 Station") }
    private fun findRepo(): String {
        var d: File? = File(System.getProperty("user.dir")).absoluteFile
        while (d != null) { if (File(d, "kapex/tools/pi.sh").exists()) return d.path; d = d.parentFile }
        return System.getProperty("user.dir")
    }
}

data class CmdResult(val code: Int, val out: String, val err: String) { val ok get() = code == 0 }

/** Runs commands: on the Pi over ssh, or locally (gradle, arduino-cli, scripts). */
object PiLink {
    private fun sshArgs() = listOf("ssh", "-i", Settings.key, "-o", "StrictHostKeyChecking=accept-new", "-o", "ConnectTimeout=6",
        "-o", "UserKnownHostsFile=${System.getProperty("user.home")}/.ssh/known_hosts_hab1", "-o", "BatchMode=yes", "${Settings.user}@${Settings.host}")

    suspend fun ssh(script: String, timeoutS: Long = 20, stdin: String? = null): CmdResult = run(sshArgs() + listOf("bash", "-s"), stdin ?: script, timeoutS, if (stdin != null) script else null)

    /** Copy text to a file on the Pi (via ssh + cat, so no temp files on the Mac). */
    suspend fun putFile(remotePath: String, content: String): CmdResult =
        run(sshArgs() + listOf("bash", "-c", "mkdir -p \"\$(dirname '$remotePath')\" && cat > '$remotePath'"), content, 20, null)

    suspend fun local(cmd: List<String>, cwd: File? = null, timeoutS: Long = 600, onLine: ((String) -> Unit)? = null): CmdResult = withContext(Dispatchers.IO) {
        val pb = ProcessBuilder(cmd).redirectErrorStream(true); cwd?.let { pb.directory(it) }
        pb.environment()["PATH"] = "/opt/homebrew/bin:/usr/local/bin:" + System.getenv("PATH")
        val p = pb.start(); p.outputStream.close()
        val sb = StringBuilder()
        p.inputStream.bufferedReader().forEachLine { sb.appendLine(it); onLine?.invoke(it) }
        if (!p.waitFor(timeoutS, TimeUnit.SECONDS)) { p.destroyForcibly(); return@withContext CmdResult(124, sb.toString(), "timeout") }
        CmdResult(p.exitValue(), sb.toString(), "")
    }

    private suspend fun run(cmd: List<String>, stdin: String, timeoutS: Long, scriptForStdinMode: String?): CmdResult = withContext(Dispatchers.IO) {
        try {
            val pb = ProcessBuilder(cmd); val p = pb.start()
            p.outputStream.bufferedWriter().use { w -> if (scriptForStdinMode != null) w.write(scriptForStdinMode) else w.write(stdin) }
            val out = StringBuilder(); val err = StringBuilder()
            val t1 = Thread { p.inputStream.bufferedReader().forEachLine { out.appendLine(it) } }.apply { start() }
            val t2 = Thread { p.errorStream.bufferedReader().forEachLine { err.appendLine(it) } }.apply { start() }
            if (!p.waitFor(timeoutS, TimeUnit.SECONDS)) { p.destroyForcibly(); return@withContext CmdResult(124, out.toString(), "timeout after ${timeoutS}s") }
            t1.join(); t2.join()
            CmdResult(p.exitValue(), out.toString(), err.toString())
        } catch (e: Exception) { CmdResult(-1, "", e.message ?: "exec failed") }
    }
}
