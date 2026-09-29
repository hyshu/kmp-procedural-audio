plugins { kotlin("multiplatform") }

kotlin {
    listOf(macosArm64(), macosX64(), linuxX64(), mingwX64()).forEach { target ->
        target.binaries.executable {
            baseName = "pcm-audio-demo"
            entryPoint = "main"
        }
    }
    sourceSets.commonMain.dependencies { implementation(project(":audio")) }
}
