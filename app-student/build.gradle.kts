import java.util.Properties

plugins {
    id("com.android.application")
    kotlin("android")
}

val localProperties = Properties().apply {
    val source = rootProject.file("local.properties")
    if (source.isFile) source.inputStream().use(::load)
}
fun buildConfigString(name: String): String =
    (localProperties.getProperty(name) ?: "")
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")

android {
    namespace = "io.remotestudy.student"
    compileSdk = 35
    buildToolsVersion = "35.0.1"

    defaultConfig {
        applicationId = "io.remotestudy.student"
        minSdk = 26
        targetSdk = 35
        versionCode = 20
        versionName = "0.16.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "TELEGRAM_BOT_TOKEN", "\"${buildConfigString("TELEGRAM_BOT_TOKEN")}\"")
        buildConfigField("long", "TELEGRAM_CHAT_ID", (localProperties.getProperty("TELEGRAM_CHAT_ID")?.toLongOrNull() ?: 0L).toString() + "L")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures { buildConfig = true }
}

dependencies {
    implementation(project(":activity-detection"))
    implementation(project(":core-domain"))
    implementation(project(":voice-command"))
    implementation(project(":telegram-report"))
    implementation("androidx.activity:activity-ktx:1.8.1")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.camera:camera-camera2:1.5.3")
    implementation("androidx.camera:camera-lifecycle:1.5.3")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
