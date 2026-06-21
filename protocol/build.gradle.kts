plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":navigation"))
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
