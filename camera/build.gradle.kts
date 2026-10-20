plugins { kotlin("multiplatform") }

// Camera storage: MJPEG frames from a UVC camera into AVI segments. Pure
// Kotlin in commonMain (unit-tested on the Mac JVM against a real C270
// frame); the file sink for the Pi lives in linuxArm64Main. No ffmpeg.
kotlin {
    jvmToolchain(21)   // the station bundles a Java 22 runtime; newer class files would not load
    linuxArm64()
    macosArm64()
    jvm()
    sourceSets {
        commonTest.dependencies { implementation(kotlin("test")) }
    }
}
