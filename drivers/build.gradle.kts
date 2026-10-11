plugins { kotlin("multiplatform") }

// Sensor and radio drivers. Register maps and compensation maths live in
// commonMain against the HAL interfaces, so they unit-test on the Mac;
// nothing here touches a file descriptor directly.
kotlin {
    linuxArm64()
    macosArm64()
    sourceSets {
        commonMain.dependencies { api(project(":hal")) }
        commonTest.dependencies { implementation(kotlin("test")) }
    }
}
