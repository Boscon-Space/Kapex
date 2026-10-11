plugins { kotlin("multiplatform") }

// llama.cpp linked into the Kotlin binary. The headers and .so files in
// native/ are copied from the Pi's own build (~/llama/install) by
// tools/pi_llama_pull.sh; they are target (aarch64) artefacts and git-ignored.
// At runtime the Pi finds the libraries through the rpath below.
kotlin {
    linuxArm64 {
        compilations.getByName("main") {
            cinterops.create("llama") {
                definitionFile.set(project.file("src/nativeInterop/cinterop/llama.def"))
                includeDirs.allHeaders(project.file("native/include"))
                compilerOpts("-I${project.file("native/include")}")
            }
        }
        binaries.all {
            linkerOpts("-L${project.file("native/lib")}", "-lllama", "-lggml", "-lggml-base", "-lggml-cpu",
                       "-Wl,-rpath,/home/hab/llama/install/lib")
        }
    }
    sourceSets {
        linuxArm64Main.dependencies { implementation(project(":hal")) }
    }
}
