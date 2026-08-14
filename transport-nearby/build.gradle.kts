plugins {
    id("com.android.library")
    kotlin("android")
}

android {
    namespace = "io.remotestudy.transport.nearby"
    compileSdk = 35
    buildToolsVersion = "35.0.1"

    defaultConfig {
        minSdk = 26
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
    implementation(project(":transport-api"))
    implementation("com.google.android.gms:play-services-nearby:19.3.0")
}
