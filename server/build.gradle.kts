plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSpring)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.springBoot)
    // TestPostgres for this module's tests and for :e2e (`testImplementation(testFixtures(projects.server))`).
    `java-test-fixtures`
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}

dependencies {
    // Gradle platform instead of the dependency-management plugin: versions we set explicitly
    // (Kotlin, kotlinx.*) win over older ones from the Spring Boot BOM.
    implementation(platform(libs.spring.boot.bom))
    implementation(projects.shared)
    implementation(libs.spring.boot.starter.webmvc)
    implementation(libs.spring.boot.starter.websocket)
    implementation(libs.spring.boot.starter.kotlinx.serialization.json)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.jdbc)
    implementation(libs.spring.boot.starter.flyway)
    implementation(libs.spring.boot.starter.mail)
    implementation(libs.spring.security.crypto)
    implementation(libs.jts.core)
    implementation(libs.kotlin.reflect)
    // Errors of the staging server (docs/adr/0018-field-test-build.md §7). Inert without the property `sentry.dsn`
    // (SENTRY_DSN): production sets none. The logback appender turns logged errors into events.
    implementation(libs.sentry.spring.boot.starter)
    implementation(libs.sentry.logback)
    runtimeOnly(libs.flyway.database.postgresql)
    runtimeOnly(libs.postgresql)

    testFixturesImplementation(platform(libs.spring.boot.bom))
    // The BOM moves every platform's binaries to the same PostgreSQL version.
    testFixturesImplementation(platform(libs.embedded.postgres.binaries.bom))
    testFixturesImplementation(libs.embedded.postgres)
    testFixturesImplementation(libs.postgresql)
    testFixturesRuntimeOnly(libs.embedded.postgres.binaries.darwin.arm64v8)
    testFixturesRuntimeOnly(libs.embedded.postgres.binaries.linux.arm64v8)
    // TestPostgresSessionListener; the test runtime brings the launcher itself.
    testFixturesCompileOnly(libs.junit.platform.launcher)

    testImplementation(libs.spring.boot.starter.webmvc.test)
    testImplementation(libs.kotlin.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}

tasks.bootJar {
    archiveFileName.set("hovanki-server.jar")
}
