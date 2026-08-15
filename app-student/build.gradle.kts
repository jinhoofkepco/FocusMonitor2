plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "io.remotestudy.student"
    compileSdk = 35
    buildToolsVersion = "35.0.1"

    defaultConfig {
        applicationId = "io.remotestudy.student"
        minSdk = 26
        targetSdk = 35
        versionCode = 9
        versionName = "0.6.2"
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
    implementation(project(":activity-detection"))
    implementation(project(":camera-capture"))
    implementation(project(":core-domain"))
    implementation(project(":core-protocol"))
    implementation(project(":core-sync"))
    implementation(project(":transport-api"))
    implementation(project(":transport-nearby"))
    implementation(project(":voice-command"))
    implementation(project(":voice-message"))
    implementation("androidx.activity:activity-ktx:1.8.1")
    implementation("androidx.core:core-ktx:1.13.1")
}
