plugins {
    kotlin("jvm") version "2.4.0"
    application
}

group = "orastudio"
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.formdev:flatlaf:3.7.2")
    implementation("com.fifesoft:rsyntaxtextarea:4.0.1")
    implementation("com.google.code.gson:gson:2.14.0")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

application {
    mainClass.set("orastudio.MainKt")
    applicationDefaultJvmArgs = listOf(
        // drivers ojdbc 19+/21+ falham com ORA-01882 em alguns servidores sem isto
        "-Doracle.jdbc.timezoneAsRegion=false",
        "-Dswing.aatext=true",
        "-Dawt.useSystemAAFontSettings=on",
        "-Xmx1g",
    )
}

tasks.test {
    useJUnitPlatform()
    systemProperty("orastudio.home", layout.buildDirectory.dir("test-home").get().asFile.absolutePath)
    // os testes de UI precisam de pecas (peer) reais; nao mostram janelas
    systemProperty("java.awt.headless", "false")
    testLogging { events("passed", "failed", "skipped"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL; showStandardStreams = true }
    // Testes de integracao (precisam de um Oracle): variaveis de ambiente ORA_TEST_*
    environment("ORA_TEST_JAR", System.getenv("ORA_TEST_JAR") ?: "")
    environment("ORA_TEST_URL", System.getenv("ORA_TEST_URL") ?: "")
    environment("ORA_TEST_USER", System.getenv("ORA_TEST_USER") ?: "")
    environment("ORA_TEST_PASSWORD", System.getenv("ORA_TEST_PASSWORD") ?: "")
}
