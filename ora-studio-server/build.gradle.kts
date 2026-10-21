import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.4.0"
}

group = "orastudio"
version = "1.0.0"

repositories { mavenCentral() }

// O nucleo (ligacoes, metadados, execucao, autocompletar) e partilhado com o programa Swing (../ora-studio);
// aqui entram so os ficheiros sem Swing + o servidor JSON-RPC.
sourceSets["main"].kotlin {
    srcDir("../ora-studio/src/main/kotlin")
    include(
        "orastudio/AppLog.kt", "orastudio/Config.kt", "orastudio/Drivers.kt", "orastudio/Db.kt", "orastudio/Sql.kt",
        "orastudio/SqlTokens.kt", "orastudio/Metadata.kt", "orastudio/Resolver.kt", "orastudio/Runner.kt",
        "orastudio/Completion.kt", "orastudio/CompletionModel.kt", "orastudio/AliasParser.kt", "orastudio/ServerCatalog.kt",
        "orastudio/server/**",
    )
}

dependencies {
    implementation("com.google.code.gson:gson:2.14.0")
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

// jar unico (com kotlin-stdlib e gson) que a extensao do VS Code lanca com `java -jar`
tasks.register<Jar>("serverJar") {
    archiveFileName.set("orastudio-server.jar")
    manifest { attributes["Main-Class"] = "orastudio.server.ServerKt" }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(sourceSets["main"].output)
    from({ configurations.runtimeClasspath.get().filter { it.name.endsWith(".jar") }.map { zipTree(it) } })
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "module-info.class")
}

tasks.test {
    useJUnitPlatform()
    systemProperty("orastudio.home", layout.buildDirectory.dir("test-home").get().asFile.absolutePath)
    testLogging { events("passed", "failed", "skipped"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL; showStandardStreams = true }
    environment("ORA_TEST_JAR", System.getenv("ORA_TEST_JAR") ?: "")
    environment("ORA_TEST_URL", System.getenv("ORA_TEST_URL") ?: "")
    environment("ORA_TEST_USER", System.getenv("ORA_TEST_USER") ?: "")
    environment("ORA_TEST_PASSWORD", System.getenv("ORA_TEST_PASSWORD") ?: "")
}
