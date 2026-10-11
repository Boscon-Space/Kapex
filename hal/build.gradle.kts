plugins { kotlin("multiplatform") }

kotlin {
    // The HAL talks straight to the Linux kernel (gpio chardev v2, i2c-dev,
    // spidev, termios). Only libc is linked. Flight target = Pi 5.
    linuxArm64 {
        compilations.getByName("main") {
            cinterops.create("linuxio") {
                definitionFile.set(project.file("src/nativeInterop/cinterop/linuxio.def"))
            }
        }
    }
    // Host target so the pure-Kotlin parts (register maps, framing, maths)
    // compile and unit-test on the Mac. No cinterop here.
    macosArm64()

    sourceSets {
        commonMain.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
        }
        commonTest.dependencies { implementation(kotlin("test")) }
    }
}
