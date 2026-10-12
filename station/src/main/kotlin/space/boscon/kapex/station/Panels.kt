package space.boscon.kapex.station

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import space.boscon.kapex.analysis.*

@Composable
fun OverviewPanel(snap: PiSnapshot?) {
    if (snap == null || !snap.reachable) { Unavailable(snap); return }
    Column(Modifier.verticalScroll(rememberScrollState())) {
        Text("${snap.hostname}: ${snap.uptime}", style = MaterialTheme.typography.titleLarge)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Card(Modifier.weight(1f)) { Column(Modifier.padding(12.dp)) { SectionTitle("Computer")
                KV("kernel", snap.kernel); KV("load (1/5/15 min)", snap.load); KV("SoC temperature", snap.socTempC?.let { "${it.f()} °C" } ?: "unreadable")
                KV("throttle flags", if (snap.throttled == "0x0") "0x0 (none)" else "${snap.throttled}: under-voltage/throttling has occurred"); KV("input voltage", snap.inputVolts?.let { "${it.f(2)} V at the PMIC" } ?: "unreadable")
                KV("memory", if (snap.memUsedMb != null) "${snap.memUsedMb} / ${snap.memTotalMb} MB used" else "unreadable"); KV("disk / and /log", "${snap.diskRootUsedPct ?: "?"} % · ${snap.diskLogUsedPct ?: "?"} %")
                KV("fan", snap.fan?.let { if (it.present) "${it.rpm ?: "?"} rpm, pwm ${it.pwm ?: "?"}/255" else "not detected" } ?: "unreadable")
                KV("Wi-Fi", snap.wifi?.let { if (it.connected) "${it.ssid} ${it.signal}% · ${it.ip}" else "not connected" } ?: "unreadable") } }
            Card(Modifier.weight(1f)) { Column(Modifier.padding(12.dp)) { SectionTitle("Services")
                for (s in snap.services) { KV(s.name, "${s.active} · ${s.enabled} · restarts ${s.restarts}${if (s.status.isNotBlank()) " · ${s.status}" else ""}") }
                KV("Heltec bridge (ttyUSB0)", if (snap.ttyUsb) "attached" else "NOT ATTACHED"); KV("radio", snap.radioLine.ifBlank { "no radio line in the journal" })
                KV("flight log", if (snap.logFile.isBlank()) "none" else "${snap.logFile} · ${snap.logRecords} records")
                val last = snap.tlm.lastOrNull(); KV("last frame", last?.let { "#${it.seq} ${ago(it.t)} · ${(it.pressPa / 100).f()} hPa · ${it.tempC.f()} °C · tx ${if (it.tx) "ok" else "FAIL"}" } ?: "none in log") } }
        }
        SectionTitle("Recent journal (flightd)")
        Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(8.dp)) { for (l in snap.infoLines.takeLast(12)) Text(l, style = MaterialTheme.typography.bodySmall) } }
    }
}

@Composable
fun RadioPanel(station: Station, snap: PiSnapshot?) {
    if (snap == null || !snap.reachable) { Unavailable(snap); return }
    val console by station.console.collectAsState()
    Column(Modifier.verticalScroll(rememberScrollState())) {
        Text("Radio: SX1262 in the TX Heltec, driven from Kotlin on the Pi through the bridge", style = MaterialTheme.typography.titleLarge)
        KV("bridge on /dev/ttyUSB0", if (snap.ttyUsb) "attached" else "NOT ATTACHED"); KV("radio (from flightd)", snap.radioLine.ifBlank { "none" })
        val frames = snap.tlm; val fails = frames.count { !it.tx }
        KV("frames in current log", "${frames.size} · ${fails} TX failures"); KV("beacon cadence", cadence(frames))
        KV("link", "GFSK 38.4 kb/s, 866.1 MHz, ±40 kHz, sync 2D D4, CRC-16: identical to firmware/08 so 09_telemetry_rx receives it")
        SectionTitle("Bridge firmware (firmware/23_spi_bridge)")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            var confirm by remember { mutableStateOf(false) }
            Button(onClick = { confirm = true }) { Text("Compile + flash via Pi") }
            if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("Flash the bridge?") },
                text = { Text("Stops flightd (the beacon goes quiet ~90 s), flashes the Heltec from the Pi, pings it, rolls back on failure, restarts flightd.") },
                confirmButton = { Button(onClick = { confirm = false; station.runLocal("flash bridge from the Pi (compile on Mac, flash with rollback)", listOf("kapex/tools/flash_bridge.sh"), java.io.File(Settings.repo)) }) { Text("Flash") } },
                dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } })
            OutlinedButton(onClick = { station.runRemote("restart flightd", "sudo systemctl restart hab1-flightd.service && sleep 6 && systemctl --no-pager status hab1-flightd.service | head -5") }) { Text("Restart flightd") }
        }
        SectionTitle("Output"); Card(Modifier.fillMaxWidth().height(260.dp)) { Column(Modifier.padding(8.dp).verticalScroll(rememberScrollState())) { for (l in console.takeLast(80)) Text(l, style = MaterialTheme.typography.bodySmall) } }
    }
}

fun cadence(frames: List<TlmRecord>): String {
    if (frames.size < 3) return "n/a"
    val d = frames.zipWithNext { a, b -> b.t - a.t }.takeLast(120)
    return "${d.minOrNull()}–${d.maxOrNull()} ms between frames (mean ${d.average().f(0)} ms over ${d.size})"
}

@Composable
fun DataPanel(snap: PiSnapshot?) {
    if (snap == null || !snap.reachable) { Unavailable(snap); return }
    val t = snap.tlm
    Column(Modifier.verticalScroll(rememberScrollState())) {
        Text("Telemetry: the last ${t.size} frames in ${snap.logFile.ifBlank { "(no log)" }}", style = MaterialTheme.typography.titleLarge)
        Text("Each measure on its own axis. Hover for values. Source: /log/flight NDJSON written by flightd, O_SYNC.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            LineChart("Pressure", "hPa", t.map { it.t to it.pressPa / 100 }, 2, Modifier.weight(1f)); LineChart("Air temperature (BMP280)", "°C", t.map { it.t to it.tempC }, 2, Modifier.weight(1f)) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            LineChart("Barometric altitude", "m", t.map { it.t to it.altBaro }, 1, Modifier.weight(1f)); LineChart("Computer (SoC) temperature", "°C", t.map { it.t to it.soc }, 1, Modifier.weight(1f)) }
        SectionTitle("Table (last 15)")
        Row { listOf("seq" to 50, "when" to 110, "hPa" to 80, "°C" to 60, "alt m" to 70, "SoC °C" to 70, "tx" to 50, "flags" to 60).forEach { (h, w) -> Text(h, Modifier.width(w.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
        for (r in t.takeLast(15).reversed()) Row { Text("${r.seq}", Modifier.width(50.dp)); Text(ago(r.t), Modifier.width(110.dp)); Text((r.pressPa / 100).f(2), Modifier.width(80.dp)); Text(r.tempC.f(2), Modifier.width(60.dp)); Text(r.altBaro.f(0), Modifier.width(70.dp)); Text(r.soc.f(1), Modifier.width(70.dp)); Text(if (r.tx) "ok" else "FAIL", Modifier.width(50.dp)); Text("0x${r.flags.toString(16)}", Modifier.width(60.dp)) }
    }
}

@Composable
fun PromptsPanel(station: Station, snap: PiSnapshot?) {
    var text by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize()) {
        Text("Prompts: to the LLM agent in flightd (file queue; the radio uplink will feed the same queue)", style = MaterialTheme.typography.titleLarge)
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(text, { text = it }, Modifier.weight(1f), label = { Text("Ask the payload") }, minLines = 2)
            Spacer(Modifier.width(8.dp))
            Button(enabled = text.isNotBlank() && snap?.reachable == true, onClick = {
                val name = "s${System.currentTimeMillis() / 1000}"; pending = name
                station.runRemote("queue prompt $name", "mkdir -p ~/prompts && cat > ~/prompts/$name.txt <<'EOP'\n${text.trim()}\nEOP\necho queued") { text = "" }
            }) { Text("Send") }
        }
        if (snap?.reachable != true) Unavailable(snap)
        val console by station.console.collectAsState()
        console.lastOrNull { it.contains("queue prompt") || it.startsWith("✓") || it.startsWith("✗") }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = if (it.startsWith("✗")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) }
        val svc = snap?.services?.firstOrNull { it.name == "hab1-flightd" }
        if (svc != null && svc.active != "active") Text("flightd is ${svc.active}: prompts wait in the queue until it is active", color = MaterialTheme.colorScheme.error)
        val answers = snap?.prompts ?: emptyList()
        if (pending != null && answers.none { it.kind == "answer" && it.name == pending }) Text("waiting for the answer to $pending … (the model runs on the Pi; a tool round trip takes ~6 s)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SectionTitle("History (from the flight log)")
        Column(Modifier.verticalScroll(rememberScrollState())) {
            val byName = answers.groupBy { it.name }
            for ((name, recs) in byName.entries.reversed().take(30)) {
                val q = recs.firstOrNull { it.kind == "prompt" }; val a = recs.firstOrNull { it.kind == "answer" }
                Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) { Column(Modifier.padding(10.dp)) {
                    Text("$name · ${q?.let { ago(it.t) } ?: ""}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(q?.text ?: "(prompt text not in this log window)", style = MaterialTheme.typography.bodyMedium)
                    Divider(Modifier.padding(vertical = 4.dp))
                    Text(a?.text ?: "… no answer yet", style = MaterialTheme.typography.bodyMedium, color = if (a?.refused == true) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                    if (a != null) Text("tools: ${a.tools.ifBlank { "none" }}${if (a.refused) " · REFUSED" else ""}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } }
            }
        }
    }
}

/** The same analysis module the Pi runs, applied here to the frames in the log window. */
@Composable
fun AnalysisPanel(snap: PiSnapshot?) {
    if (snap == null || !snap.reachable) { Unavailable(snap); return }
    val t = snap.tlm
    val out = remember(t) {
        if (t.size < 2) null else {
            val press = Rolling(60); val temp = Rolling(60); val soc = Rolling(60); val vr = Rolling(10)
            val pm = ChannelMonitor("pressure", 200.0); val tm = ChannelMonitor("temperature", 2.0); val sm = ChannelMonitor("soc", 5.0)
            val profile = AltitudeProfile(250.0); var lastAlt = Double.NaN; var lastT = Double.NaN; val t0 = t.first().t
            for (r in t) { val ts = (r.t - t0) / 1000.0; press.add(r.pressPa); temp.add(r.tempC); soc.add(r.soc); pm.add(ts, r.pressPa); tm.add(ts, r.tempC); sm.add(ts, r.soc)
                val alt = baroAltitudeM(r.pressPa); if (!lastAlt.isNaN() && ts > lastT) vr.add((alt - lastAlt) / (ts - lastT)); lastAlt = alt; lastT = ts
                profile.add(alt, r.pressPa, descending = vr.size > 0 && vr.mean < -1.0) }
            val now = (t.last().t - t0) / 1000.0
            val readings = listOf(Reading("pressure", press.last / 100, "hPa", pm.flags(now), 2), Reading("temperature", temp.last, "C", tm.flags(now)), Reading("barometric altitude", lastAlt, "m", emptyList(), 0), Reading("vertical rate", if (vr.size > 0) vr.mean else Double.NaN, "m/s"), Reading("computer temperature", soc.last, "C", sm.flags(now)))
            Triple(readings, listOf(press, temp, soc, vr), profile)
        }
    }
    Column(Modifier.verticalScroll(rememberScrollState())) {
        Text("Analysis: kapex/analysis run on the Mac over the same frames", style = MaterialTheme.typography.titleLarge)
        if (out == null) { Text("fewer than two frames in the log window"); return }
        val (readings, rolls, profile) = out
        SectionTitle("What read_sensors would hand the model right now"); Card(Modifier.fillMaxWidth()) { Text(readings.toPromptText(), Modifier.padding(12.dp)) }
        SectionTitle("Rolling statistics (last 60 frames)")
        Row { listOf("channel" to 170, "last" to 90, "mean" to 90, "σ" to 90, "min" to 90, "max" to 90).forEach { (h, w) -> Text(h, Modifier.width(w.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
        for ((name, r) in listOf("pressure (Pa)" to rolls[0], "air temperature (°C)" to rolls[1], "SoC temperature (°C)" to rolls[2], "vertical rate (m/s, 10)" to rolls[3])) if (r.size > 0) Row { Text(name, Modifier.width(170.dp)); Text(r.last.f(2), Modifier.width(90.dp)); Text(r.mean.f(2), Modifier.width(90.dp)); Text(if (r.size > 1) r.stdDev.f(3) else "none", Modifier.width(90.dp)); Text(r.min().f(2), Modifier.width(90.dp)); Text(r.max().f(2), Modifier.width(90.dp)) }
        SectionTitle("Altitude profile (250 m bins) and Regener-Pfotzer detector")
        val bins = profile.bins(); Text("${bins.size} bin(s) populated on ascent: ${bins.keys.sorted().joinToString { "${profile.binAltitude(it).f(0)} m×${bins[it]!!.n}" }}", style = MaterialTheme.typography.bodySmall)
        Text("Peak detector input is the Geiger count rate (sensor not yet on the bench); run here on pressure as a plumbing check: ${profile.findPeak()?.let { "peak at ${it.altitudeM.f(0)} m, ratio ${it.ratio.f(1)}" } ?: "no peak (needs ≥ 3 bins, ≥ 5 samples each, ≥ 3× ground): expected on a static bench"}", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun FanPanel(station: Station, snap: PiSnapshot?) {
    if (snap == null || !snap.reachable) { Unavailable(snap); return }
    val fan = snap.fan
    var manual by remember { mutableStateOf(120f) }
    Column(Modifier.verticalScroll(rememberScrollState())) {
        Text("Fan: Pi 5 fan connector (pwm-fan)", style = MaterialTheme.typography.titleLarge)
        if (fan == null || !fan.present) { Text("No fan device. The firmware enables the connector only when it sees a fan at power-on; dtparam=cooling_fan=on forces it (set on 2026-10-11).", color = MaterialTheme.colorScheme.error); }
        else {
            KV("speed", "${fan.rpm ?: "?"} rpm"); KV("pwm", "${fan.pwm ?: "?"} / 255"); KV("control", when (fan.enable) { 2 -> "automatic (kernel curve)"; 1 -> "MANUAL override"; 0 -> "off"; else -> "?" }); KV("SoC temperature", snap.socTempC?.let { "${it.f()} °C" } ?: "?")
            SectionTitle("Curve in /boot/firmware/config.txt (applies at boot)"); for (l in fan.curve) Text(l, style = MaterialTheme.typography.bodySmall)
            SectionTitle("Override now (writes pwm1_enable/pwm1 via sudo; the kernel curve resumes with Auto)")
            Row(verticalAlignment = Alignment.CenterVertically) { Slider(manual, { manual = it }, Modifier.width(300.dp), valueRange = 0f..255f); Text("pwm ${manual.toInt()}", Modifier.padding(horizontal = 8.dp))
                Button(onClick = { station.runRemote("fan manual pwm ${manual.toInt()}", "h=\$(for h in /sys/class/hwmon/hwmon*; do [ \"\$(cat \$h/name)\" = pwmfan ] && echo \$h; done); echo 1 | sudo tee \$h/pwm1_enable >/dev/null; echo ${manual.toInt()} | sudo tee \$h/pwm1 >/dev/null; sleep 2; echo \"rpm \$(cat \$h/fan1_input) pwm \$(cat \$h/pwm1)\"") }) { Text("Set") }
                Spacer(Modifier.width(8.dp)); OutlinedButton(onClick = { station.runRemote("fan auto", "h=\$(for h in /sys/class/hwmon/hwmon*; do [ \"\$(cat \$h/name)\" = pwmfan ] && echo \$h; done); echo 2 | sudo tee \$h/pwm1_enable >/dev/null; sleep 2; echo \"rpm \$(cat \$h/fan1_input) pwm \$(cat \$h/pwm1) enable \$(cat \$h/pwm1_enable)\"") }) { Text("Auto") } }
        }
        val console by station.console.collectAsState(); SectionTitle("Output"); Card(Modifier.fillMaxWidth().height(160.dp)) { Column(Modifier.padding(8.dp).verticalScroll(rememberScrollState())) { for (l in console.takeLast(30)) Text(l, style = MaterialTheme.typography.bodySmall) } }
    }
}

@Composable
fun SettingsPanel(station: Station, snap: PiSnapshot?) {
    var host by remember { mutableStateOf(Settings.host) }; var user by remember { mutableStateOf(Settings.user) }; var key by remember { mutableStateOf(Settings.key) }; var repo by remember { mutableStateOf(Settings.repo) }
    var ssid by remember { mutableStateOf("") }; var pw by remember { mutableStateOf("") }
    val console by station.console.collectAsState()
    Column(Modifier.verticalScroll(rememberScrollState())) {
        Text("Settings", style = MaterialTheme.typography.titleLarge)
        SectionTitle("Connection (this Mac → Pi over SSH)")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedTextField(host, { host = it }, label = { Text("host") }); OutlinedTextField(user, { user = it }, label = { Text("user") }) }
        OutlinedTextField(key, { key = it }, Modifier.fillMaxWidth(), label = { Text("ssh private key") }); OutlinedTextField(repo, { repo = it }, Modifier.fillMaxWidth(), label = { Text("BosconSpace repo on this Mac") })
        Button(onClick = { Settings.host = host; Settings.user = user; Settings.key = key; Settings.repo = repo; station.log("settings saved") }) { Text("Save") }

        SectionTitle("Pi Wi-Fi (NetworkManager on the Pi; the password goes straight to the Pi over SSH and is not stored here)")
        KV("current", snap?.wifi?.let { if (it.connected) "${it.ssid} · ${it.signal}% · ${it.ip}" else "not connected" } ?: "unknown")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedTextField(ssid, { ssid = it }, label = { Text("SSID") }); OutlinedTextField(pw, { pw = it }, label = { Text("password") }, visualTransformation = PasswordVisualTransformation()) }
        Text("Changing the network can drop this link if the Mac is not on the same network; the current connection is kept as a fallback profile.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(enabled = ssid.isNotBlank() && pw.isNotBlank() && snap?.reachable == true, onClick = {
            val s = ssid; val p = pw; pw = ""
            station.runRemote("connect Pi to Wi-Fi '$s'", "sudo nmcli dev wifi connect \"${s.replace("\"", "\\\"")}\" password \"${p.replace("\"", "\\\"")}\" 2>&1; nmcli -t -f ACTIVE,SSID dev wifi | grep '^yes'") }) { Text("Connect Pi to this network") }

        SectionTitle("Hardware watchdog (BCM2712 watchdog via systemd RuntimeWatchdogSec)")
        KV("/dev/watchdog", if (snap?.watchdog?.devicePresent == true) "present" else "absent/unknown"); KV("RuntimeWatchdogSec", snap?.watchdog?.runtimeWatchdogSec ?: "?"); KV("flightd service watchdog", snap?.watchdog?.serviceWatchdogSec ?: "?")
        Text("With RuntimeWatchdogSec set, systemd itself must pet the hardware watchdog; if PID 1 hangs, the SoC reboots. Takes effect after daemon-reexec; verified only by a deliberate hang test, which reboots the Pi.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { station.runRemote("enable hardware watchdog 15 s", "sudo mkdir -p /etc/systemd/system.conf.d && printf '[Manager]\\nRuntimeWatchdogSec=15s\\nRebootWatchdogSec=2min\\n' | sudo tee /etc/systemd/system.conf.d/hab1-watchdog.conf && sudo systemctl daemon-reexec && sleep 2 && systemctl show -p RuntimeWatchdogUSec --value && journalctl -b -n 50 --no-pager -o cat | grep -i watchdog | tail -2") }) { Text("Enable (15 s)") }
            OutlinedButton(onClick = { station.runRemote("disable hardware watchdog", "sudo rm -f /etc/systemd/system.conf.d/hab1-watchdog.conf && sudo systemctl daemon-reexec && systemctl show -p RuntimeWatchdogUSec --value") }) { Text("Disable") }
        }
        SectionTitle("Pi power"); Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedButton(onClick = { station.runRemote("reboot", "sudo systemctl reboot") }) { Text("Reboot Pi") }; OutlinedButton(onClick = { station.runRemote("poweroff", "sudo systemctl poweroff") }) { Text("Power off Pi") } }
        SectionTitle("Output"); Card(Modifier.fillMaxWidth().height(180.dp)) { Column(Modifier.padding(8.dp).verticalScroll(rememberScrollState())) { for (l in console.takeLast(40)) Text(l, style = MaterialTheme.typography.bodySmall) } }
    }
}
