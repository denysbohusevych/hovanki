plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSpring)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.springBoot)
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
    implementation(libs.spring.boot.starter.kotlinx.serialization.json)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.kotlin.reflect)

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
