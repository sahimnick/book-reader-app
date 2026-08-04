// Standalone verification harness.
//
// It is intentionally NOT part of the root Gradle build: it must resolve from
// Maven Central alone, with no Android Gradle Plugin and no Compose, so that the
// platform-independent core can be compiled and tested on any machine (or CI
// runner) that has nothing but a JDK.
pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories { mavenCentral() }
}

rootProject.name = "core-verify"
