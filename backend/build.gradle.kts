// All explicit versions live in gradle/libs.versions.toml.
plugins {
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.kotlin.jpa)
}

group = "com.werewolf"
version = "0.0.1-SNAPSHOT"

// Kotlin libraries managed by the Spring Boot BOM follow the Kotlin plugin version.
extra["kotlin.version"] = libs.versions.kotlin.get()

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    // Web (Spring MVC)
    implementation("org.springframework.boot:spring-boot-starter-webmvc")

    // Data JPA
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")

    // WebSocket
    implementation("org.springframework.boot:spring-boot-starter-websocket")

    // Security
    implementation("org.springframework.boot:spring-boot-starter-security")

    // OAuth2 Client
    implementation("org.springframework.boot:spring-boot-starter-security-oauth2-client")

    // Validation
    implementation("org.springframework.boot:spring-boot-starter-validation")

    // Databases: PostgreSQL in dev/prod, in-memory H2 for the e2e profile and tests
    runtimeOnly("org.postgresql:postgresql")
    runtimeOnly("com.h2database:h2")

    // Flyway: Spring Boot 4 only auto-runs migrations via the starter; Flyway 10+
    // moved PostgreSQL support out of flyway-core into its own module.
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.flywaydb:flyway-database-postgresql")

    // Stripe (credit purchases — Checkout sessions + webhook fulfillment)
    implementation(libs.stripe.java)

    // JWT
    implementation(libs.jjwt.api)
    runtimeOnly(libs.jjwt.impl)
    runtimeOnly(libs.jjwt.jackson)

    // Kotlin (Jackson 3 Kotlin module — Spring Boot 4's JSON stack)
    implementation("tools.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")

    // Kotlin Coroutines (the old -jdk8 module has been folded into core since 1.7)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core")

    // DevTools
    developmentOnly("org.springframework.boot:spring-boot-devtools")

    // Test (webmvc-test brings TestRestTemplate, whose auto-config needs the
    // restclient module's RestTemplateBuilder; security-test brings spring-security-test)
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-restclient")
    testImplementation("org.springframework.boot:spring-boot-starter-security-test")
    testImplementation(libs.mockito.kotlin)
    // PaymentWebhookIntegrationTest parses events via Stripe's ApiResource.GSON;
    // stripe-java only ships Gson on the runtime classpath.
    testImplementation("com.google.code.gson:gson")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict")
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}
