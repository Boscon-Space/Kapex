plugins { kotlin("multiplatform") }

// camrec: one UVC camera to MJPEG AVI segments on the card. Its own process,
// separate from flightd, so a stalled camera can never hold up the beacon.
kotlin {
    linuxArm64 {
        binaries { executable("camrec") { entryPoint = "space.boscon.kapex.camrec.main" } }
    }
    sourceSets {
        linuxArm64Main.dependencies { implementation(project(":hal")); implementation(project(":camera")) }
    }
}
