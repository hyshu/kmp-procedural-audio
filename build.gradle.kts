plugins {
    kotlin("multiplatform") version "2.4.10" apply false
    id("com.android.kotlin.multiplatform.library") version "9.0.1" apply false
    id("com.android.application") version "9.0.1" apply false
    id("com.diffplug.spotless") version "8.10.3"
}

allprojects {
    group = "bio.aq.audio"
    version = "0.1.1"
}

spotless {
    kotlin {
        target("audio/src/**/*.kt", "samples/**/src/**/*.kt")
        ktlint("1.8.0")
    }
    kotlinGradle {
        target("*.gradle.kts", "audio/*.gradle.kts", "samples/*/*.gradle.kts")
        ktlint("1.8.0")
    }
    format("cpp") {
        target("audio/native/**/*.cpp", "audio/native/**/*.h")
        clangFormat("23.1.1").apply {
            providers.environmentVariable("CLANG_FORMAT").orNull?.let { pathToExe(it) }
        }
    }
}
