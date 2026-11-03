// Desktop harness: runs the app's platform-neutral code (core + ui) on the JVM with Compose for Desktop,
// renders screenshots headlessly and runs tests (XCP client against the Python simulator).
// Uses Compose Multiplatform 1.5.12 because newer versions need artifacts from Google Maven at runtime;
// small API gaps to the Android Compose version are bridged in src/shims. The exact Android API level
// is compile-checked separately in compile-check/.
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
rootProject.name = "instrumentarium-desktop-preview"
