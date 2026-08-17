plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

allprojects {
    group = "dev.tracedown"
    version = "0.1.0"

    repositories {
        mavenCentral()
    }

    // The Ktor Gradle plugin declares commons-lang3 as an open range
    // ([3.18.0,)), which makes every cold resolve list maven-metadata over
    // the network and fail outright when plugins.gradle.org is unreachable
    // (e.g. inside docker builds). Pin it so resolution is concrete and
    // fully cacheable.
    buildscript {
        configurations.all {
            resolutionStrategy.force("org.apache.commons:commons-lang3:3.18.0")
        }
    }
}

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "org.jetbrains.kotlin.plugin.serialization")

    configure<JavaPluginExtension> {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    tasks.withType<JavaCompile> {
        sourceCompatibility = "17"
        targetCompatibility = "17"
    }

    tasks.withType<Test> {
        useJUnitPlatform()
        // Testcontainers' bundled docker-java defaults to Docker API v1.32, which
        // Docker Engine 25+ rejects (minimum supported API is 1.40). docker-java
        // reads the target version from the `api.version` system property, so pin a
        // modern, widely-supported version to let integration tests reach the daemon.
        systemProperty("api.version", System.getProperty("api.version") ?: "1.44")
    }
}
