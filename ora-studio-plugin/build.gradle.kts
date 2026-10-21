import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    id("org.jetbrains.kotlin.jvm") version "2.0.21"
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "orastudio"
version = "1.0.0"

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

// O codigo do Ora Studio e partilhado com o programa independente (../ora-studio); aqui so se exclui o Main.kt (FlatLaf).
sourceSets["main"].kotlin.srcDir("../ora-studio/src/main/kotlin")

dependencies {
    // Nao incluir a stdlib do Kotlin nem o gson (a plataforma ja os traz)
    implementation("com.fifesoft:rsyntaxtextarea:4.0.1")
    compileOnly("com.google.code.gson:gson:2.10.1")
    compileOnly("org.jetbrains.kotlin:kotlin-stdlib:1.9.24")
    intellijPlatform {
        intellijIdeaCommunity("2024.1.7")
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    exclude("**/Main.kt")
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        apiVersion.set(KotlinVersion.KOTLIN_1_9)
        languageVersion.set(KotlinVersion.KOTLIN_1_9)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

intellijPlatform {
    buildSearchableOptions = false
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "241"
            untilBuild = provider { null }
        }
    }
}
