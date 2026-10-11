plugins { kotlin("multiplatform") }

// Wire formats shared by the payload and the ground station. Pure Kotlin,
// no I/O. The Telemetry frame must stay byte-identical to the C struct in
// firmware/08_telemetry_tx; tools/check_packet_abi.py cross-checks both.
kotlin {
    linuxArm64()
    macosArm64()
    jvm()
    sourceSets {
        commonTest.dependencies { implementation(kotlin("test")) }
    }
}
