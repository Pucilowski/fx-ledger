plugins {
    java
    application
}

group = "com.pucilowski"
version = "0.1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

val jooqVersion = "3.19.15"
val flywayVersion = "10.20.1"
val testcontainersVersion = "1.21.3"

dependencies {
    // HTTP
    implementation("com.sparkjava:spark-core:2.9.4")

    // Persistence
    implementation("org.postgresql:postgresql:42.7.4")
    implementation("com.zaxxer:HikariCP:6.2.1")
    implementation("org.jooq:jooq:$jooqVersion")
    implementation("org.flywaydb:flyway-core:$flywayVersion")
    runtimeOnly("org.flywaydb:flyway-database-postgresql:$flywayVersion")

    // JSON
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.2")

    // Logging
    implementation("org.slf4j:slf4j-api:2.0.16")
    runtimeOnly("org.slf4j:slf4j-simple:2.0.16")

    // Test
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.testcontainers:postgresql:$testcontainersVersion")
    testImplementation("org.testcontainers:junit-jupiter:$testcontainersVersion")
    testImplementation("org.assertj:assertj-core:3.27.0")
}

application {
    mainClass = "com.pucilowski.ledger.App"
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
    }
}
