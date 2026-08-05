plugins {
    kotlin("jvm") version "2.0.21"
    // The core now parses JSON (AI dictionary replies), so the harness needs
    // the same serialization runtime the shared module uses.
    kotlin("plugin.serialization") version "2.0.21"
}

repositories { mavenCentral() }

// Compile the shared core straight out of the KMP module's commonMain. There is
// no copy to drift: this harness tests exactly the sources the apps ship.
val sharedCore = file("../../composeApp/src/commonMain/kotlin")

kotlin {
    // No toolchain pin: the harness runs on whatever JDK is present so it stays
    // usable on a bare CI runner. The shipped apps pin JVM 17 in composeApp.
    sourceSets["main"].kotlin.apply {
        srcDir(sharedCore)
        // Only the platform-independent core. UI and platform bindings need
        // Compose and the Android/iOS SDKs, which this harness deliberately lacks.
        include("com/bookreader/core/**")
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = true
    }
}
