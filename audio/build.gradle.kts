@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)

import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.KotlinHierarchyTemplate
import java.util.Properties

plugins {
    kotlin("multiplatform")
    id("com.android.kotlin.multiplatform.library") apply false
    `maven-publish`
}

val skipAndroid = providers.gradleProperty("skipAndroid").map(String::toBoolean).getOrElse(false)
if (!skipAndroid) apply(plugin = "com.android.kotlin.multiplatform.library")

kotlin {
    applyHierarchyTemplate(KotlinHierarchyTemplate.default) {
        common {
            group("desktopNative") {
                withLinux()
                withMingw()
            }
        }
    }

    val appleTargets = listOf(
        macosArm64(),
        macosX64(),
        iosArm64(),
        iosSimulatorArm64(),
        watchosArm64(),
        watchosDeviceArm64(),
        watchosSimulatorArm64(),
    )
    appleTargets.forEach {
        it.binaries.framework {
            baseName = "PcmAudio"
            isStatic = true
        }
    }

    val nativeCompilerVersion = coreLibrariesVersion
    listOf(linuxX64(), mingwX64()).forEach { target ->
        val targetName = target.name
        val nativeBuildDirectory = layout.buildDirectory.dir("native/$targetName").get().asFile
        val nativeSourceDirectory = file("native")
        val capitalizedTarget = targetName.replaceFirstChar(Char::uppercaseChar)
        val toolchain = providers.gradleProperty("pcmAudioToolchain$capitalizedTarget").orNull
        val toolchainFile = file(
            toolchain ?: if (targetName == "mingwX64") {
                "native/cmake/konan-mingw.cmake"
            } else {
                "native/cmake/konan-linux.cmake"
            },
        )
        val konanData = file(
            System.getenv("KONAN_DATA_DIR") ?: "${System.getProperty("user.home")}/.konan",
        )
        val compilerHomeOverride = (
            providers.gradleProperty("kotlin.native.home").orNull ?: System.getenv("KONAN_HOME")
            )?.let(::file)
        val llvmRootOverride = System.getenv("PCM_AUDIO_LLVM_ROOT")
        val targetKey = if (targetName == "mingwX64") "mingw_x64" else "linux_x64"
        val targetVariable = if (targetName == "mingwX64") "PCM_AUDIO_MINGW_ROOT" else "PCM_AUDIO_LINUX_ROOT"
        val targetRootOverride = System.getenv(targetVariable)
        val os = System.getProperty("os.name")
        val host = when {
            os.startsWith("Mac") -> if (System.getProperty("os.arch") in listOf("aarch64", "arm64")) {
                "macos_arm64"
            } else {
                "macos_x64"
            }

            os.startsWith("Windows") -> "mingw_x64"

            else -> "linux_x64"
        }
        val toolchainInterop = target.compilations.getByName("main").cinterops.create("pcmToolchain") {
            definitionFile.set(project.file("src/nativeInterop/cinterop/pcmToolchain.def"))
        }
        val configure = tasks.register<Exec>("configure${capitalizedTarget}Output") {
            dependsOn(toolchainInterop.interopProcessingTaskName)
            inputs.dir("native")
            inputs.property("nativeCompilerVersion", nativeCompilerVersion)
            inputs.property("toolchain", toolchain ?: "default")
            inputs.property("compilerHomeOverride", compilerHomeOverride?.absolutePath ?: "default")
            listOf(
                "KONAN_DATA_DIR",
                "KONAN_HOME",
                "PCM_AUDIO_LLVM_ROOT",
                "PCM_AUDIO_MINGW_ROOT",
                "PCM_AUDIO_LINUX_ROOT",
            ).forEach {
                inputs.property(it, System.getenv(it) ?: "default")
            }
            outputs.file(nativeBuildDirectory.resolve("CMakeCache.txt"))
            commandLine(
                "cmake", "-S", nativeSourceDirectory.absolutePath,
                "-B", nativeBuildDirectory.absolutePath,
                "-G", "Ninja", "-DCMAKE_BUILD_TYPE=Release", "-DBUILD_TESTING=OFF",
                "-DPCM_AUDIO_BUNDLE_RUNTIME=ON",
                "-DCMAKE_TOOLCHAIN_FILE=${toolchainFile.absolutePath}",
                "-DCMAKE_ARCHIVE_OUTPUT_DIRECTORY=${nativeBuildDirectory.absolutePath}",
            )
            doFirst {
                val compilerHome = compilerHomeOverride ?: konanData.listFiles()
                    ?.firstOrNull {
                        it.name.endsWith("-$nativeCompilerVersion") &&
                            it.resolve("konan/konan.properties").isFile
                    }
                    ?: error("Kotlin Native distribution was not found in $konanData")
                val properties = Properties().apply {
                    compilerHome.resolve("konan/konan.properties").inputStream().use { load(it) }
                }
                fun resolveProperty(key: String): String {
                    val value = properties.getProperty(key) ?: error("Missing Kotlin Native property $key")
                    return Regex("\\$([A-Za-z0-9_.-]+)").replace(value) {
                        resolveProperty(it.groupValues[1])
                    }
                }
                val dependencies = konanData.resolve("dependencies")
                val llvmRoot = llvmRootOverride
                    ?: dependencies.resolve(resolveProperty("llvmHome.$host")).absolutePath
                val targetRoot = targetRootOverride
                    ?: dependencies.resolve(resolveProperty("toolchainDependency.$targetKey")).absolutePath
                val configureOutput = this as Exec
                configureOutput.environment("PCM_AUDIO_LLVM_ROOT", llvmRoot)
                configureOutput.environment(targetVariable, targetRoot)
            }
        }
        val build = tasks.register<Exec>("build${capitalizedTarget}Output") {
            dependsOn(configure)
            inputs.dir("native")
            inputs.file(nativeBuildDirectory.resolve("CMakeCache.txt"))
            outputs.file(nativeBuildDirectory.resolve("libpcm_audio.a"))
            commandLine(
                "cmake",
                "--build",
                nativeBuildDirectory.absolutePath,
                "--config",
                "Release",
                "--target",
                "pcm_audio",
            )
        }
        target.compilations.getByName("main").cinterops.create("pcmAudio") {
            definitionFile.set(project.file("src/nativeInterop/cinterop/pcmAudio.def"))
            includeDirs(project.file("native/include"))
            extraOpts(
                "-libraryPath",
                nativeBuildDirectory.absolutePath,
                "-staticLibrary",
                "libpcm_audio.a",
            )
        }.also { interop ->
            tasks.named(interop.interopProcessingTaskName).configure {
                dependsOn(build)
                inputs.file(nativeBuildDirectory.resolve("libpcm_audio.a"))
            }
        }
    }

    js {
        outputModuleName = "pcm-audio"
        browser()
        nodejs()
        useEsModules()
        binaries.library()
        generateTypeScriptDefinitions()
    }

    if (!skipAndroid) {
        targets.withType<com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget>().configureEach {
            namespace = "bio.aq.audio"
            compileSdk = 36
            minSdk = 24
            compilerOptions.jvmTarget = JvmTarget.JVM_11
            withHostTest {}
        }
    }

    sourceSets {
        commonTest.dependencies { implementation(kotlin("test")) }
    }
}

publishing {
    publications.withType<MavenPublication>().configureEach {
        pom {
            name = "KMP Procedural Audio"
            description = "Kotlin sources and native outputs for generated audio"
            licenses {
                license {
                    name = "MIT License"
                    url = "https://opensource.org/license/mit"
                    distribution = "repo"
                }
            }
        }
    }
}
