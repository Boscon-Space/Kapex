package space.boscon.kapex.station

/** Everything one poll of the Pi returns. Null fields = that section was unreadable; the UI says so. */
data class PiSnapshot(
    val at: Long,
    val reachable: Boolean,
    val error: String? = null,
    val hostname: String = "", val uptime: String = "", val kernel: String = "", val load: String = "",
    val socTempC: Double? = null, val throttled: String = "", val memUsedMb: Int? = null, val memTotalMb: Int? = null,
    val diskRootUsedPct: Int? = null, val diskLogUsedPct: Int? = null, val inputVolts: Double? = null,
    val fan: FanInfo? = null,
    val services: List<ServiceInfo> = emptyList(),
    val ttyUsb: Boolean = false,
    val pins: Map<Int, PinInfo> = emptyMap(),
    val tlm: List<TlmRecord> = emptyList(),
    val prompts: List<PromptRecord> = emptyList(),
    val infoLines: List<String> = emptyList(),
    val radioLine: String = "",
    val wifi: WifiInfo? = null,
    val watchdog: WatchdogInfo? = null,
    val logFile: String = "", val logRecords: Int = 0,
)

data class FanInfo(val present: Boolean, val rpm: Int?, val pwm: Int?, val enable: Int?, val curve: List<String>)
data class ServiceInfo(val name: String, val active: String, val enabled: String, val restarts: Int, val status: String, val pid: Int)
data class PinInfo(val gpio: Int, val function: String, val level: String, val pull: String, val alias: String)
data class TlmRecord(val t: Long, val seq: Int, val pressPa: Double, val tempC: Double, val altBaro: Double, val soc: Double, val tx: Boolean, val flags: Int)
data class PromptRecord(val t: Long, val kind: String, val name: String, val text: String, val refused: Boolean, val tools: String)
data class WifiInfo(val connected: Boolean, val ssid: String, val ip: String, val signal: String)
data class WatchdogInfo(val devicePresent: Boolean, val runtimeWatchdogSec: String, val serviceWatchdogSec: String)

/** The one script the poller runs on the Pi. Sections are delimited so one SSH round trip fetches everything. */
val POLL_SCRIPT = """
echo "@@sys"; hostname; uptime -p; uname -r; cut -d' ' -f1-3 /proc/loadavg
echo "@@temp"; cat /sys/class/thermal/thermal_zone0/temp; vcgencmd get_throttled; vcgencmd pmic_read_adc 2>/dev/null | grep EXT5V_V
echo "@@mem"; free -m | awk 'NR==2{print ${'$'}3, ${'$'}2}'; df --output=pcent / | tail -1; df --output=pcent /log 2>/dev/null | tail -1
echo "@@fan"; for h in /sys/class/hwmon/hwmon*; do [ "${'$'}(cat ${'$'}h/name)" = pwmfan ] && echo "present ${'$'}(cat ${'$'}h/fan1_input 2>/dev/null) ${'$'}(cat ${'$'}h/pwm1 2>/dev/null) ${'$'}(cat ${'$'}h/pwm1_enable 2>/dev/null)"; done; grep "^dtparam=fan_temp\|^dtparam=cooling_fan" /boot/firmware/config.txt
echo "@@svc"; for s in hab1-flightd hab1-telemetry; do echo "${'$'}s ${'$'}(systemctl is-active ${'$'}s.service) ${'$'}(systemctl is-enabled ${'$'}s.service 2>/dev/null) ${'$'}(systemctl show -p NRestarts --value ${'$'}s.service) ${'$'}(systemctl show -p MainPID --value ${'$'}s.service) ${'$'}(systemctl show -p StatusText --value ${'$'}s.service)"; done
echo "@@tty"; ls /dev/ttyUSB0 2>/dev/null
echo "@@pins"; pinctrl get 0-27 2>/dev/null
echo "@@log"; f=${'$'}(ls -t /log/flight/*.ndjson 2>/dev/null | head -1); echo "${'$'}f ${'$'}(wc -l < ${'$'}f 2>/dev/null)"; [ -n "${'$'}f" ] && tail -n 400 "${'$'}f"; ls -t /log/flight/*.ndjson 2>/dev/null | head -4 | xargs -r grep -h '"kind":"prompt"\|"kind":"answer"' 2>/dev/null | tail -60
echo "@@journal"; journalctl -u hab1-flightd.service -n 400 --no-pager -o cat 2>/dev/null | grep -E "^radio |^flightd |^llm:|^bmp280:|^prompt |^answered |^refused |^tx error|^FATAL" | tail -30
echo "@@wifi"; nmcli -t -f ACTIVE,SSID,SIGNAL dev wifi 2>/dev/null | grep "^yes" | head -1; hostname -I | awk '{print ${'$'}1}'
echo "@@wd"; ls /dev/watchdog 2>/dev/null; grep -h "^RuntimeWatchdogSec" /etc/systemd/system.conf /etc/systemd/system.conf.d/*.conf 2>/dev/null | tail -1; grep -h "^WatchdogSec" /etc/systemd/system/hab1-flightd.service 2>/dev/null
echo "@@end"
""".trimIndent()

object Parse {
    private fun sections(out: String): Map<String, List<String>> {
        val m = LinkedHashMap<String, MutableList<String>>(); var cur = ""
        for (l in out.lines()) { if (l.startsWith("@@")) { cur = l.drop(2); m[cur] = mutableListOf() } else m[cur]?.add(l) }
        return m
    }
    private fun jsonField(line: String, key: String): String? = Regex("\"$key\":(\"(?:[^\"\\\\]|\\\\.)*\"|[^,}]*)").find(line)?.groupValues?.get(1)?.let { v -> if (v.startsWith("\"")) v.drop(1).dropLast(1).replace("\\\"", "\"").replace("\\n", "\n").replace("\\\\", "\\") else v }

    fun snapshot(r: CmdResult): PiSnapshot {
        if (!r.ok && r.out.isBlank()) return PiSnapshot(System.currentTimeMillis(), false, error = r.err.ifBlank { "ssh exit ${r.code}" })
        val s = sections(r.out)
        val sys = s["sys"] ?: emptyList(); val temp = s["temp"] ?: emptyList(); val mem = s["mem"] ?: emptyList()
        val fanL = s["fan"] ?: emptyList()
        val fan = fanL.firstOrNull { it.startsWith("present") }?.split(" ")?.let { f -> FanInfo(true, f.getOrNull(1)?.toIntOrNull(), f.getOrNull(2)?.toIntOrNull(), f.getOrNull(3)?.toIntOrNull(), fanL.filter { it.startsWith("dtparam") }) }
            ?: FanInfo(false, null, null, null, fanL.filter { it.startsWith("dtparam") })
        val services = (s["svc"] ?: emptyList()).filter { it.isNotBlank() }.map { l -> val p = l.split(" ", limit = 6); ServiceInfo(p[0], p.getOrElse(1) { "?" }, p.getOrElse(2) { "?" }, p.getOrElse(3) { "0" }.toIntOrNull() ?: 0, p.getOrElse(5) { "" }, p.getOrElse(4) { "0" }.toIntOrNull() ?: 0) }
        val pins = (s["pins"] ?: emptyList()).mapNotNull { l ->
            // e.g. " 2: a3    pu | hi // GPIO2 = SDA1"   or "17: op dl pd | lo // GPIO17 = output"
            val m = Regex("""^\s*(\d+):\s+(\S+)\s*(\S*)\s*(\S*)\s*\|\s*(\S+)\s*//\s*GPIO\d+\s*=\s*(.*)$""").find(l) ?: return@mapNotNull null
            val g = m.groupValues; val toks = listOf(g[3], g[4]).filter { it.isNotBlank() }
            PinInfo(g[1].toInt(), g[2], g[5], toks.firstOrNull { it in listOf("pu", "pd", "pn") } ?: "", g[6].trim())
        }.associateBy { it.gpio }
        val logL = s["log"] ?: emptyList(); val hdr = logL.firstOrNull()?.split(" ") ?: emptyList()
        val tlm = logL.drop(1).filter { it.contains("\"kind\":\"tlm\"") }.mapNotNull { l ->
            runCatching { TlmRecord(jsonField(l, "t")!!.toLong(), jsonField(l, "seq")!!.toInt(), jsonField(l, "pressPa")!!.toDouble(), jsonField(l, "tempC")!!.toDouble(),
                jsonField(l, "altBaro")!!.toDouble(), jsonField(l, "soc")!!.toDouble(), jsonField(l, "tx") == "true", jsonField(l, "flags")!!.toInt()) }.getOrNull() }
        val prompts = logL.drop(1).filter { it.contains("\"kind\":\"prompt\"") || it.contains("\"kind\":\"answer\"") }.mapNotNull { l ->
            runCatching { val kind = jsonField(l, "kind")!!; PromptRecord(jsonField(l, "t")!!.toLong(), kind, jsonField(l, if (kind == "prompt") "name" else "prompt") ?: "", jsonField(l, "text") ?: "", jsonField(l, "refused") == "true", jsonField(l, "tools") ?: "") }.getOrNull() }
        val journal = s["journal"] ?: emptyList()
        val wifiL = s["wifi"] ?: emptyList(); val w = wifiL.firstOrNull { it.startsWith("yes:") }?.split(":")
        val wd = s["wd"] ?: emptyList()
        return PiSnapshot(System.currentTimeMillis(), true,
            hostname = sys.getOrElse(0) { "" }, uptime = sys.getOrElse(1) { "" }, kernel = sys.getOrElse(2) { "" }, load = sys.getOrElse(3) { "" },
            socTempC = temp.getOrNull(0)?.trim()?.toDoubleOrNull()?.div(1000), throttled = temp.getOrElse(1) { "" }.removePrefix("throttled="),
            inputVolts = temp.getOrNull(2)?.substringAfter("=")?.removeSuffix("V")?.toDoubleOrNull(),
            memUsedMb = mem.getOrNull(0)?.split(" ")?.getOrNull(0)?.toIntOrNull(), memTotalMb = mem.getOrNull(0)?.split(" ")?.getOrNull(1)?.toIntOrNull(),
            diskRootUsedPct = mem.getOrNull(1)?.trim()?.removeSuffix("%")?.toIntOrNull(), diskLogUsedPct = mem.getOrNull(2)?.trim()?.removeSuffix("%")?.toIntOrNull(),
            fan = fan, services = services, ttyUsb = (s["tty"] ?: emptyList()).any { it.contains("ttyUSB0") }, pins = pins,
            tlm = tlm, prompts = prompts, infoLines = journal, radioLine = journal.lastOrNull { it.startsWith("radio ") } ?: "",
            wifi = WifiInfo(w != null, w?.getOrNull(1) ?: "", wifiL.lastOrNull { Regex("""^\d+\.\d+\.\d+\.\d+$""").matches(it.trim()) }?.trim() ?: "", w?.getOrNull(2) ?: ""),
            watchdog = WatchdogInfo(wd.any { it.contains("/dev/watchdog") }, wd.firstOrNull { it.startsWith("RuntimeWatchdogSec") }?.substringAfter("=") ?: "(not set)", wd.firstOrNull { it.startsWith("WatchdogSec") }?.substringAfter("=") ?: "(none)"),
            logFile = hdr.getOrNull(0)?.substringAfterLast('/') ?: "", logRecords = hdr.getOrNull(1)?.toIntOrNull() ?: 0)
    }
}
