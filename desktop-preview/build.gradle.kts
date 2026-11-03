plugins {
    kotlin("jvm") version "1.9.22"
    id("org.jetbrains.kotlin.plugin.serialization") version "1.9.22"
    id("org.jetbrains.compose") version "1.5.12"
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        freeCompilerArgs.addAll(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.foundation.layout.ExperimentalLayoutApi",
            "-opt-in=kotlinx.coroutines.FlowPreview",
        )
    }
}

sourceSets {
    main {
        kotlin.srcDir("../app/src/main/java")
        kotlin.srcDir("src/shims/kotlin")
        kotlin.exclude("de/instrumentarium/android/**")
    }
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation("org.jetbrains.compose.material:material-icons-core:1.5.12")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.7.3")
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    useJUnit()
    systemProperty("simulator", file("../simulator/xcp_sim.py").absolutePath)
    systemProperty("screenshots", file("build/screenshots").absolutePath)
    systemProperty("java.awt.headless", "true")
    testLogging { events("passed", "failed"); showStandardStreams = true; exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
