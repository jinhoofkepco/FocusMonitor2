plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "io.remotestudy.teacher"
    compileSdk = 35
    buildToolsVersion = "35.0.1"

    defaultConfig {
        applicationId = "io.remotestudy.teacher"
        minSdk = 26
        targetSdk = 35
        versionCode = 6
        versionName = "0.5.1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(project(":core-protocol"))
    implementation(project(":core-sync"))
    implementation(project(":transport-api"))
    implementation(project(":transport-nearby"))
    implementation(project(":voice-message"))
    implementation("androidx.activity:activity-ktx:1.8.1")
    implementation("androidx.core:core-ktx:1.13.1")
}
