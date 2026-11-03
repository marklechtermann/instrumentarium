// Compile check of the app sources against Compose Multiplatform 1.7.3, whose API level matches
// the Android build (Compose BOM 2024.12, Material3 1.3). Runtime would need Google Maven, so compile only.
pluginManagement {
    repositories {
        // Google's Maven Central mirror first: more robust against Central rate limits.
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories {
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
    }
}
rootProject.name = "instrumentarium-compile-check"
