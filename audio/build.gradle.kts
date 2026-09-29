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
        val nativeBuild = layout.buildDirectory.dir("native/${target.name}")
        val capitalizedTarget = target.name.replaceFirstChar(Char::uppercaseChar)
        val toolchainInterop = target.compilations.getByName("main").cinterops.create("pcmToolchain") {
            definitionFile.set(project.file("src/nativeInterop/cinterop/pcmToolchain.def"))
        }
        val configure = tasks.register<Exec>("configure${capitalizedTarget}Output") {
            dependsOn(toolchainInterop.interopProcessingTaskName)
            inputs.dir("native")
            inputs.property("nativeCompilerVersion", nativeCompilerVersion)
            inputs.property(
                "toolchain",
                providers.gradleProperty("pcmAudioToolchain$capitalizedTarget").orElse("default"),
            )
            listOf(
                "KONAN_DATA_DIR",
                "KONAN_HOME",
                "PCM_AUDIO_LLVM_ROOT",
                "PCM_AUDIO_MINGW_ROOT",
                "PCM_AUDIO_LINUX_ROOT",
            ).forEach {
                inputs.property(it, System.getenv(it) ?: "default")
            }
            outputs.file(nativeBuild.map { it.file("CMakeCache.txt") })
            doFirst {
                val konanData = file(
                    System.getenv("KONAN_DATA_DIR")
                        ?: "${System.getProperty("user.home")}/.konan",
                )
                val homeOverride = providers.gradleProperty("kotlin.native.home").orNull
                    ?: System.getenv("KONAN_HOME")
                val compilerHome = homeOverride?.let(::file) ?: konanData.listFiles()
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
                val dependencies = konanData.resolve("dependencies")
                val llvmRoot = System.getenv("PCM_AUDIO_LLVM_ROOT")
                    ?: dependencies.resolve(resolveProperty("llvmHome.$host")).absolutePath
                val targetKey = if (target.name == "mingwX64") "mingw_x64" else "linux_x64"
                val targetVariable = if (target.name == "mingwX64") "PCM_AUDIO_MINGW_ROOT" else "PCM_AUDIO_LINUX_ROOT"
                val targetRoot = System.getenv(targetVariable)
                    ?: dependencies.resolve(resolveProperty("toolchainDependency.$targetKey")).absolutePath
                environment("PCM_AUDIO_LLVM_ROOT", llvmRoot)
                environment(targetVariable, targetRoot)
                val toolchain = providers.gradleProperty("pcmAudioToolchain$capitalizedTarget").orNull
                    ?: if (target.name ==
                        "mingwX64"
                    ) {
                        "native/cmake/konan-mingw.cmake"
                    } else {
                        "native/cmake/konan-linux.cmake"
                    }
                commandLine(
                    "cmake", "-S", file("native").absolutePath,
                    "-B", nativeBuild.get().asFile.absolutePath,
                    "-G", "Ninja", "-DCMAKE_BUILD_TYPE=Release", "-DBUILD_TESTING=OFF",
                    "-DPCM_AUDIO_BUNDLE_RUNTIME=ON",
                    "-DCMAKE_TOOLCHAIN_FILE=${file(toolchain).absolutePath}",
                    "-DCMAKE_ARCHIVE_OUTPUT_DIRECTORY=${nativeBuild.get().asFile.absolutePath}",
                )
            }
        }
        val build = tasks.register<Exec>("build${capitalizedTarget}Output") {
            dependsOn(configure)
            inputs.dir("native")
            inputs.file(nativeBuild.map { it.file("CMakeCache.txt") })
            outputs.file(nativeBuild.map { it.file("libpcm_audio.a") })
            commandLine(
                "cmake",
                "--build",
                nativeBuild.get().asFile.absolutePath,
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
                nativeBuild.get().asFile.absolutePath,
                "-staticLibrary",
                "libpcm_audio.a",
            )
        }.also { interop ->
            tasks.named(interop.interopProcessingTaskName).configure {
                dependsOn(build)
                inputs.file(nativeBuild.map { it.file("libpcm_audio.a") })
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
