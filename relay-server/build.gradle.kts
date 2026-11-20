plugins {
    id("java")
    id("application")
    id("org.jetbrains.kotlin.jvm")
}

group = "com.instantbits.cast.relay"
version = "1.0.0"

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.9.22")

    // Ktor server for relay
    implementation("io.ktor:ktor-server-netty:2.3.11")
    implementation("io.ktor:ktor-server-core:2.3.11")
    implementation("io.ktor:ktor-server-content-negotiation:2.3.11")
    implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.11")
    implementation("io.ktor:ktor-server-status-pages-jvm:2.3.11")
    implementation("io.ktor:ktor-server-call-logging-jvm:2.3.11")
    implementation("io.ktor:ktor-server-hsts-jvm:2.3.11")
    implementation("io.ktor:ktor-server-conditional-headers-jvm:2.3.11")
    implementation("io.ktor:ktor-server-compression-jvm:2.3.11")
    implementation("io.ktor:ktor-client-cio:2.3.11")
    implementation("io.ktor:ktor-client-auth:2.3.11")

    // HTML parsing for video extraction
    implementation("org.jsoup:jsoup:1.17.2")

    // Logging
    implementation("ch.qos.logback:logback-classic:1.5.6")
    implementation("org.slf4j:slf4j-api:2.0.13")

    // Configuration
    implementation("com.typesafe:config:1.4.3")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testImplementation("io.ktor:ktor-server-test-host:2.3.11")
}

application {
    mainClass.set("com.instantbits.cast.relay.RelayServerKt")
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs = listOf("-Xjsr305=strict")
    }
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}