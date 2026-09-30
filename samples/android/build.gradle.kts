plugins {
    id("com.android.application")
}

android {
    namespace = "bio.aq.audio.sample"
    compileSdk = 36
    defaultConfig {
        applicationId = "bio.aq.audio.sample"
        minSdk = 24
        targetSdk = 36
        versionCode = 2
        versionName = "0.1.1"
    }
}

dependencies {
    implementation(project(":audio"))
}
