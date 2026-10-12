package space.boscon.kapex.station

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Polls the Pi every 2 s on a background thread; the UI observes [snapshot]. */
class Station {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _snapshot = MutableStateFlow<PiSnapshot?>(null)
    val snapshot: StateFlow<PiSnapshot?> = _snapshot
    val console = MutableStateFlow(listOf<String>())   // local command output (builds, flashes)
    @Volatile var paused = false

    init { scope.launch { while (isActive) { if (!paused) _snapshot.value = Parse.snapshot(PiLink.ssh(POLL_SCRIPT, 15)); delay(2000) } } }

    fun log(line: String) { console.value = (console.value + line).takeLast(400) }
    fun clearConsole() { console.value = emptyList() }
    /** Run a local tool (gradle, arduino-cli, repo script) streaming into the console; polling pauses meanwhile to keep ssh quiet. */
    fun runLocal(title: String, cmd: List<String>, cwd: java.io.File, onDone: (CmdResult) -> Unit = {}) = scope.launch {
        log("\$ $title"); paused = true
        val r = PiLink.local(cmd, cwd) { log(it) }
        paused = false; log(if (r.ok) "✓ $title done" else "✗ $title failed (exit ${r.code}) ${r.err}"); onDone(r)
    }
    fun runRemote(title: String, script: String, onDone: (CmdResult) -> Unit = {}) = scope.launch {
        log("pi\$ $title"); val r = PiLink.ssh(script, 120); r.out.lines().filter { it.isNotBlank() }.forEach { log(it) }
        if (r.err.isNotBlank()) r.err.lines().filter { it.isNotBlank() }.forEach { log("! $it") }
        log(if (r.ok) "✓ $title" else "✗ $title (exit ${r.code})"); onDone(r)
    }
}

enum class Tab(val label: String, val icon: ImageVector) {
    OVERVIEW("Overview", Icons.Filled.Dashboard), PINS("Pins", Icons.Filled.DeveloperBoard), RADIO("Radio", Icons.Filled.SettingsInputAntenna),
    DATA("Data", Icons.Filled.ShowChart), PROMPTS("Prompts", Icons.Filled.Chat), ANALYSIS("Analysis", Icons.Filled.Analytics),
    FAN("Fan", Icons.Filled.Air), CODE("Code", Icons.Filled.Code), SETTINGS("Settings", Icons.Filled.Settings),
}

fun main() = application {
    val station = remember { Station() }
    Window(onCloseRequest = ::exitApplication, title = "HAB-1 Station", state = rememberWindowState(width = 1380.dp, height = 900.dp)) {
        val systemDark = androidx.compose.foundation.isSystemInDarkTheme()
        val dark = remember { systemDark }   // decided once at start-up; later system flips do not restyle the session
        MaterialTheme(colorScheme = if (dark) DarkScheme else LightScheme) {
            var tab by remember { mutableStateOf(Tab.OVERVIEW) }
            val snap by station.snapshot.collectAsState()
            androidx.compose.material3.Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { Row(Modifier.fillMaxSize()) {
                NavigationRail(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Spacer(Modifier.height(8.dp))
                    for (t in Tab.entries) NavigationRailItem(selected = tab == t, onClick = { tab = t }, icon = { Icon(t.icon, t.label) }, label = { Text(t.label) })
                    Spacer(Modifier.weight(1f))
                    ConnectionDot(snap)
                    Spacer(Modifier.height(12.dp))
                }
                Column(Modifier.fillMaxSize().padding(16.dp)) {
                    when (tab) {
                        Tab.OVERVIEW -> OverviewPanel(snap)
                        Tab.PINS -> PinsPanel(snap)
                        Tab.RADIO -> RadioPanel(station, snap)
                        Tab.DATA -> DataPanel(snap)
                        Tab.PROMPTS -> PromptsPanel(station, snap)
                        Tab.ANALYSIS -> AnalysisPanel(snap)
                        Tab.FAN -> FanPanel(station, snap)
                        Tab.CODE -> CodePanel(station)
                        Tab.SETTINGS -> SettingsPanel(station, snap)
                    }
                }
            } }
        }
    }
}

@Composable
fun ConnectionDot(snap: PiSnapshot?) {
    val (txt, color) = when { snap == null -> "connecting…" to MaterialTheme.colorScheme.outline; snap.reachable -> "online" to Palette.good; else -> "offline" to MaterialTheme.colorScheme.error }
    Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
        Icon(Icons.Filled.Circle, txt, tint = color, modifier = Modifier.size(12.dp)); Text(txt, style = MaterialTheme.typography.labelSmall)
    }
}

/** Panels never show placeholder data: with no snapshot they explain why. */
@Composable
fun Unavailable(snap: PiSnapshot?, what: String = "the Pi") {
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
        Text("No connection to $what", style = MaterialTheme.typography.titleMedium)
        Text(snap?.error ?: "Waiting for the first poll of ${Settings.user}@${Settings.host} over SSH.", style = MaterialTheme.typography.bodyMedium)
    } }
}

@Composable fun KV(label: String, value: String) { Row(Modifier.padding(vertical = 2.dp)) { Text(label, Modifier.width(190.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant); Text(value, style = MaterialTheme.typography.bodyMedium) } }
@Composable fun SectionTitle(t: String) { Text(t, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp)) }
fun Double.f(d: Int = 1): String = String.format("%.${d}f", this)
