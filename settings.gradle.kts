pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "kmp-procedural-audio"
include(":audio", ":samples:desktop")
if (!providers.gradleProperty("skipAndroid").map(String::toBoolean).getOrElse(false)) {
    include(":samples:android")
}
