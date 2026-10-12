import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

// HAB-1 Station: the Mac-side console. Compose for Desktop; every panel reads
// the real Pi over SSH (tools/pi.sh's key) and the repo on disk. It reuses the
// payload's own protocol/ and analysis/ modules (jvm targets) so the analysis
// shown here is the same Kotlin that runs on board.
dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
    implementation(project(":protocol"))
    implementation(project(":analysis"))
}

kotlin { jvmToolchain(21) }

compose.desktop {
    application {
        mainClass = "space.boscon.kapex.station.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Dmg)
            packageName = "HAB-1 Station"
            packageVersion = "0.1.0"
        }
    }
}
