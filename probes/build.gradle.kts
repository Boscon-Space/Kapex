plugins { kotlin("multiplatform") }

// Bench probes, in the spirit of firmware/1x_*_probe: each is one executable
// with a pass condition that can fail. Built on the Mac, scp'd to the Pi.
kotlin {
    linuxArm64 {
        binaries {
            executable("gpio_probe") { entryPoint = "space.boscon.hab.probes.gpioProbe" }
            executable("edge_probe") { entryPoint = "space.boscon.hab.probes.edgeProbe" }
            executable("baro_probe") { entryPoint = "space.boscon.hab.probes.baroProbe" }
            executable("spi_probe") { entryPoint = "space.boscon.hab.probes.spiProbe" }
            executable("radio_probe") { entryPoint = "space.boscon.hab.probes.radioProbe" }
            executable("telemetry_tx") { entryPoint = "space.boscon.hab.probes.telemetryTx" }
            executable("llm_probe") { entryPoint = "space.boscon.hab.probes.llmProbe" }
        }
    }
    sourceSets {
        linuxArm64Main.dependencies {
            implementation(project(":hal"))
            implementation(project(":drivers"))
            implementation(project(":protocol"))
            implementation(project(":llm"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
        }
    }
}
