plugins { kotlin("multiplatform") }

// The agent loop: prompt -> (tool calls) -> answer, with the flight guards
// outside the loop. Pure Kotlin against an LlmEngine interface, so it is
// unit-tested on the Mac with a scripted engine; the Pi plugs in llm/Llm.
kotlin {
    jvmToolchain(21)   // the station bundles a Java 22 runtime; newer class files would not load
    linuxArm64()
    macosArm64()
    jvm()
    sourceSets {
        commonTest.dependencies { implementation(kotlin("test")) }
    }
}
