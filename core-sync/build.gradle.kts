plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":core-protocol"))
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    useJUnit()
}
