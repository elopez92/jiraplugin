plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.0"
    id("org.jetbrains.intellij.platform") version "2.7.1"
}

group = "com.elopez"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

// Configure IntelliJ Platform Gradle Plugin
// Read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html
//
// Android Studio: build numbers often lag IntelliJ. To target a specific Android Studio build, switch
// `create("IC", ...)` to `create("AndroidStudio", ...)` and set `sinceBuild` / `untilBuild` to match
// https://plugins.jetbrains.com/docs/intellij/android-studio.html
dependencies {
    implementation("com.google.code.gson:gson:2.11.0")

    intellijPlatform {
        create("IC", "2025.1.4.1")
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
        bundledPlugin("Git4Idea")
    }
}

intellijPlatform {
    // Microsoft OpenJDK often has no `JAVA_HOME/Packages`, which breaks `:instrumentCode` (microsoft/openjdk#339).
    // `instrumentCode = false` does not work — this is a Gradle Property and must use `.set(false)`.
    // To turn instrumentation back on: set `true`, use JetBrains Runtime / Temurin, or create an empty `Packages` folder in JAVA_HOME.
    instrumentCode.set(false)

    pluginConfiguration {
        ideaVersion {
            sinceBuild = "251"
        }

        changeNotes = """
            Initial version
        """.trimIndent()
    }
}

tasks {
    // Set the JVM compatibility versions
    withType<JavaCompile> {
        sourceCompatibility = "21"
        targetCompatibility = "21"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}
