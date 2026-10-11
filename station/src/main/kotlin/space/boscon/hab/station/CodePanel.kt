package space.boscon.hab.station

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File

/** Which files the Code tab offers and what "Check" means for each. */
private fun codeFiles(repo: File): List<File> = buildList {
    add(File(repo, "firmware/23_spi_bridge/23_spi_bridge.ino"))
    listOf("flightd", "agent", "analysis", "drivers", "hal", "llm", "protocol", "probes").forEach { m -> File(repo, "payload/$m/src").walkTopDown().filter { it.isFile && it.extension in setOf("kt", "def") }.sortedBy { it.path }.forEach { add(it) } }
    File(repo, "payload/tools").listFiles { f -> f.extension == "sh" }?.sortedBy { it.name }?.forEach { add(it) }
}

@Composable
fun CodePanel(station: Station) {
    val repo = remember { File(Settings.repo) }
    val files = remember { codeFiles(repo) }
    var current by remember { mutableStateOf(files.firstOrNull()) }
    var text by remember(current) { mutableStateOf(current?.takeIf { it.exists() }?.readText() ?: "") }
    var dirty by remember(current) { mutableStateOf(false) }
    val console by station.console.collectAsState()
    val isIno = current?.extension == "ino"; val isKt = current?.extension in setOf("kt", "def")
    Row(Modifier.fillMaxSize()) {
        Column(Modifier.width(300.dp).fillMaxHeight().verticalScroll(rememberScrollState())) {
            Text("Files", style = MaterialTheme.typography.titleMedium)
            for (f in files) TextButton(onClick = { current = f }, contentPadding = PaddingValues(4.dp)) { Text(f.relativeTo(repo).path, style = MaterialTheme.typography.bodySmall, color = if (f == current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface) }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(current?.name ?: "—", style = MaterialTheme.typography.titleMedium); if (dirty) Text("• unsaved", color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.weight(1f))
                Button(enabled = dirty && current != null, onClick = { current!!.writeText(text); dirty = false; station.log("saved ${current!!.name}") }) { Text("Save") }
                if (isIno) {
                    OutlinedButton(onClick = { station.runLocal("arduino-cli compile (check)", listOf("arduino-cli", "compile", "--fqbn", "esp32:esp32:heltec_wifi_lora_32_V3", "firmware/23_spi_bridge"), repo) }) { Text("Check (compile)") }
                    var confirm by remember { mutableStateOf(false) }
                    Button(onClick = { confirm = true }) { Text("Flash via Pi") }
                    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("Flash the bridge?") }, text = { Text("Stops flightd for ~90 s, flashes, pings, rolls back on failure, restarts flightd.") },
                        confirmButton = { Button(onClick = { confirm = false; station.runLocal("flash bridge from the Pi (with rollback)", listOf("payload/tools/flash_bridge.sh"), repo) }) { Text("Flash") } }, dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } })
                }
                if (isKt) {
                    OutlinedButton(onClick = { station.runLocal("gradle compile + tests", listOf("gradle", "--no-daemon", "-q", ":hal:compileKotlinLinuxArm64", ":drivers:macosArm64Test", ":protocol:macosArm64Test", ":agent:macosArm64Test", ":analysis:macosArm64Test"), File(repo, "payload")) }) { Text("Check (compile + tests)") }
                    Button(onClick = { station.runLocal("build flightd", listOf("gradle", "--no-daemon", "-q", ":flightd:linkFlightdReleaseExecutableLinuxArm64"), File(repo, "payload")) { r -> if (r.ok) station.runLocal("deploy flightd to the Pi", listOf("payload/tools/pi_flightd.sh", "install"), repo) } }) { Text("Build + deploy flightd") }
                }
                OutlinedButton(onClick = { station.clearConsole() }) { Text("Clear output") }
            }
            Row(Modifier.weight(1f).padding(top = 6.dp)) {
                val lines = text.count { it == '\n' } + 1
                Column(Modifier.width(44.dp).verticalScroll(rememberScrollState())) { for (i in 1..lines) Text("$i", style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)) }
                BasicTextField(value = text, onValueChange = { text = it; dirty = true }, modifier = Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                    textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface))
            }
            Text("Output", style = MaterialTheme.typography.titleSmall)
            Card(Modifier.fillMaxWidth().height(200.dp)) { Column(Modifier.padding(8.dp).verticalScroll(rememberScrollState())) { for (l in console.takeLast(60)) Text(l, style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp)) } }
        }
    }
}
