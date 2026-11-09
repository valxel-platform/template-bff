import org.springframework.boot.gradle.plugin.SpringBootPlugin

plugins {
    java
    jacoco
    alias(libs.plugins.spring.boot)
}

group = "com.vrixel"
description = "Vrixel BFF template: browser sign-in, server-side sessions and the product's API behind one origin"

java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
}

repositories { mavenCentral() }

dependencies {
    implementation(platform(SpringBootPlugin.BOM_COORDINATES))
    implementation(platform("org.springframework.cloud:spring-cloud-dependencies:${libs.versions.spring.cloud.get()}"))
    implementation("org.springframework.cloud:spring-cloud-starter-gateway-server-webflux")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-client")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-session-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-data-redis-reactive")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    // Sign-ins and sign-outs on the product's audit trail (SignInAudit).
    implementation("org.apache.kafka:kafka-clients")
    implementation("org.springframework.boot:spring-boot-starter-opentelemetry")
    // Error reports to Sentry when SENTRY_DSN is set (ErrorReports).
    implementation(platform(libs.sentry.bom))
    implementation("io.sentry:sentry-spring-boot-4-starter")
    implementation("io.sentry:sentry-logback")
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webflux-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // Spring Cloud brings Bouncy Castle (spring-security-rsa); versions before 1.85 have critical
    // and high advisories (CVE-2026-8763, CVE-2026-13506). Drop this once Spring Cloud ships 1.85+.
    constraints {
        listOf("bcprov-jdk18on", "bcpkix-jdk18on", "bcutil-jdk18on").forEach { artifact ->
            implementation("org.bouncycastle:$artifact") {
                version { require("[1.85,2.0)") }
                because("CVE-2026-8763, CVE-2026-13506")
            }
        }
        implementation("at.yawk.lz4:lz4-java") {
            version { require("[1.11.4,2.0)") }
            because("CVE-2026-106451 (GHSA-mcr4-qmvw-px4g), through kafka-clients")
        }
        implementation("org.xerial.snappy:snappy-java") {
            version { require("[1.1.10.9,2.0)") }
            because("GHSA-q233-g92q-qxp6, GHSA-gwq9-9ffj-mvrv, through kafka-clients")
        }
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 21
    options.compilerArgs.addAll(listOf("-Xlint:all,-processing,-serial", "-parameters"))
}

// The integration tests start Redis in a container (Testcontainers), so they need Docker, which
// CI runners have.
tasks.test {
    useJUnitPlatform()
    testLogging {
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showCauses = true
    }
    finalizedBy(tasks.jacocoTestReport)
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required = true
        html.required = true
    }
}

// The platform's floor; products may raise it (<product>-governance/product.yaml quality.unit.min_coverage).
tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.jacocoTestReport)
    violationRules {
        rule { limit { counter = "LINE"; minimum = "0.85".toBigDecimal() } }
    }
}

tasks.check {
    dependsOn(tasks.jacocoTestCoverageVerification)
}

tasks.bootJar {
    archiveFileName = "app.jar"
}

tasks.jar {
    enabled = false
}
