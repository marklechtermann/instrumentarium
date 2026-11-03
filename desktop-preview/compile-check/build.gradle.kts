plugins {
    kotlin("jvm") version "2.1.0"
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.0"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.1.0"
    id("org.jetbrains.compose") version "1.7.3"
}

kotlin { jvmToolchain(21) }

sourceSets {
    main {
        kotlin.srcDir("../../app/src/main/java")
        kotlin.exclude("de/instrumentarium/android/**")
        resources.srcDir("../../app/src/main/assets")
    }
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation("org.jetbrains.compose.material:material-icons-core:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")
}

