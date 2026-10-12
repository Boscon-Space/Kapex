plugins { kotlin("multiplatform") }

// On-board data analysis, pure Kotlin: rolling statistics, altitude-binned
// profiles, the Regener-Pfotzer peak, burst and free-fall detection, and
// anomaly flags the agent can read. Same code runs on the Pi and in the
// ground station's replay; unit-tested on the Mac with synthetic flights.
kotlin {
    jvmToolchain(21)   // the station bundles a Java 22 runtime; newer class files would not load
    linuxArm64()
    macosArm64()
    jvm()
    sourceSets {
        commonTest.dependencies { implementation(kotlin("test")) }
    }
}
