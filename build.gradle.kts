import java.util.EnumSet
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.0"
    id("org.jetbrains.intellij.platform") version "2.7.1"
}

group = "com.elopez"
// Marketplace rejects duplicate versions; use a new version for each upload (e.g. 1.0.1, 1.0.2).
version = "1.0.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

// Configure IntelliJ Platform Gradle Plugin
// Read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html
//
// Android Studio: set `jiraPlugin.useAndroidStudio=true` in gradle.properties, sync, then use the
// regular Run Plugin run configuration — the sandbox IDE will be Android Studio. Match
// `sinceBuild` / `untilBuild` to the IntelliJ platform version used by your minimum AS version:
// https://plugins.jetbrains.com/docs/intellij/android-studio.html
// https://plugins.jetbrains.com/docs/intellij/android-studio-releases-list.html
val useAndroidStudio: Boolean =
    providers.gradleProperty("jiraPlugin.useAndroidStudio")
        .map { it.equals("true", ignoreCase = true) }
        .getOrElse(false)

// Default false: avoids headless IDE + "Only one instance of IDEA can run" during buildPlugin when a desktop IDE is open.
// Set jiraPlugin.buildSearchableOptions=true if you need Settings search indexing for the plugin UI.
val buildSearchableOptionsEnabled: Boolean =
    providers.gradleProperty("jiraPlugin.buildSearchableOptions")
        .map { it.equals("true", ignoreCase = true) }
        .getOrElse(false)

// Optional plugin signing: https://plugins.jetbrains.com/docs/intellij/plugin-signing.html
// Prefer env vars or local (untracked) gradle.properties — never commit keys.
fun signingPathOrNull(env: String, gradleKey: String): String? =
    System.getenv(env)?.trim()?.takeIf { it.isNotEmpty() }
        ?: project.findProperty(gradleKey)?.toString()?.trim()?.takeIf { it.isNotEmpty() }

val signingCertPath = signingPathOrNull("JIRA_PLUGIN_CERT_CHAIN", "jiraPlugin.signing.certChain")
val signingKeyPath = signingPathOrNull("JIRA_PLUGIN_PRIVATE_KEY", "jiraPlugin.signing.privateKey")
val signingKeyPass =
    System.getenv("JIRA_PLUGIN_KEY_PASS")?.trim()?.takeIf { it.isNotEmpty() }
        ?: project.findProperty("jiraPlugin.signing.privateKeyPassword")?.toString()?.trim()?.takeIf { it.isNotEmpty() }

dependencies {
    implementation("com.google.code.gson:gson:2.11.0")

    intellijPlatform {
        if (useAndroidStudio) {
            // Koala / 2024.1.x — matches `sinceBuild = "241"` (oldest supported AS generation).
            androidStudio("2024.1.2.13")
            bundledPlugin("org.jetbrains.android")
        } else {
            // Same platform generation (241.*) for API parity with minimum Android Studio.
            create("IC", "2024.1.7")
        }
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
        bundledPlugin("Git4Idea")
    }
}

intellijPlatform {
    // Microsoft OpenJDK often has no `JAVA_HOME/Packages`, which breaks `:instrumentCode` (microsoft/openjdk#339).
    // `instrumentCode = false` does not work — this is a Gradle Property and must use `.set(false)`.
    // To turn instrumentation back on: set `true`, use JetBrains Runtime / Temurin, or create an empty `Packages` folder in JAVA_HOME.
    instrumentCode.set(false)
    buildSearchableOptions.set(buildSearchableOptionsEnabled)

    signing {
        val cert = signingCertPath
        val key = signingKeyPath
        if (cert != null && key != null) {
            certificateChainFile.set(rootProject.file(cert))
            privateKeyFile.set(rootProject.file(key))
            if (!signingKeyPass.isNullOrBlank()) {
                password.set(signingKeyPass)
            }
        }
    }

    // Pin to the same IC generation as `dependencies { intellijPlatform { create("IC", "2024.1.7") } }`.
    // `recommended()` can pull unreleased builds and break resolution.
    pluginVerification {
        // Verifier does not resolve optional config-file inside lib/*.jar layout ("descriptor not found");
        // the file is present at runtime. Ignore that structure warning so CI stays meaningful.
        failureLevel.set(
            EnumSet.complementOf(EnumSet.of(VerifyPluginTask.FailureLevel.PLUGIN_STRUCTURE_WARNINGS))
        )
        ides {
            create(IntelliJPlatformType.IntellijIdeaCommunity, "2024.1.7")
        }
    }

    // After the first manual Marketplace upload, deploy updates with: ./gradlew publishPlugin
    // Token: https://plugins.jetbrains.com/author/me/tokens — pass via ORG_GRADLE_PROJECT_intellijPlatformPublishingToken or -PintellijPlatformPublishingToken
    // See https://plugins.jetbrains.com/docs/intellij/publishing-plugin.html
    publishing {
        token = providers.gradleProperty("intellijPlatformPublishingToken")
    }

    pluginConfiguration {
        ideaVersion {
            // 241.* ≈ IntelliJ 2024.1 / Android Studio Koala (2024.1.x). Older AS (233.*) needs more testing.
            sinceBuild = "241"
        }

        changeNotes = """
            <ul>
                <li>First public release as <b>Jira Companion</b></li>
                <li>Tool window: issue lookup, JQL search, saved filters, issue detail with comments</li>
                <li>Assign to me, watch/unwatch, add comment; open in browser; copy issue key</li>
                <li>Optional Git: branch key from current branch; create branch from issue</li>
                <li>Notifications tab with feed picker and optional auto-refresh</li>
                <li>Minimum platform: 241 (IDEA 2024.1 / Android Studio Koala 2024.1)</li>
            </ul>
        """.trimIndent()
    }
}

tasks {
    // JVM 17: required for older Android Studio / JBR; do not raise without raising `sinceBuild`.
    withType<JavaCompile> {
        sourceCompatibility = "17"
        targetCompatibility = "17"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
