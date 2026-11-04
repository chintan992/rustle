pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Leftover jitpack / k2-fsa.github.io maven repos removed (round 6 cruft).
        // sherpa-onnx now ships as a local AAR picked up by fileTree("libs").
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "Audiobook"
include(":app")
