plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

application {
    mainClass.set("dev.tracedown.email.ApplicationKt")
}

dependencies {
    implementation(project(":tracedown-core-common"))

    // Ktor server (lifecycle container)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)

    // Redis
    implementation(libs.lettuce)

    // Serialization & coroutines
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)

    // Logging
    implementation(libs.logback)
    implementation(libs.logstash.logback.encoder)

    // Test
    testImplementation(libs.kotlin.test)
}
