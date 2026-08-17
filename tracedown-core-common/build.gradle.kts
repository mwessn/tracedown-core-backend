plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    implementation(libs.exposed.core)
    implementation(libs.exposed.dao)
    implementation(libs.exposed.jdbc)
    implementation(libs.exposed.java.time)
    implementation(libs.exposed.json)

    implementation(libs.hikari)
    implementation(libs.postgresql)

    implementation(libs.lettuce)

    implementation(libs.koin.core)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)

    implementation(libs.logback)
    // Shared logback-base.xml (this module's resources) uses the logstash JSON
    // encoder and janino-backed <if> conditionals; both must be on every
    // consuming service's runtime classpath, so they live here.
    implementation(libs.logstash.logback.encoder)
    implementation(libs.janino)

    implementation(libs.simple.java.mail)
    implementation(libs.simple.java.mail.batch)
    implementation(libs.okhttp)
    implementation(libs.minio)

    implementation(libs.ktor.server.core)
    implementation(libs.java.jwt)
    implementation(libs.java.otp)
    implementation(libs.bcrypt)

    testImplementation(libs.kotlin.test)
}
