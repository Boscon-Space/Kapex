plugins { kotlin("multiplatform") }

// flightd: the one process that flies. Beacon TX (through the Heltec bridge
// for now), sensors, analysis, the LLM + agent loop, NDJSON log on /log,
// systemd watchdog. Everything above the HAL is the modules already proven
// by their probes; this module only composes them.
kotlin {
    linuxArm64 {
        binaries { executable("flightd") { entryPoint = "space.boscon.kapex.flightd.main" } }
    }
    sourceSets {
        linuxArm64Main.dependencies {
            implementation(project(":hal")); implementation(project(":drivers")); implementation(project(":protocol"))
            implementation(project(":llm")); implementation(project(":agent")); implementation(project(":analysis"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
        }
    }
}
