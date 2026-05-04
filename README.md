# Jira Companion

IntelliJ / Android Studio plugin that keeps **Jira Cloud** next to your code: issue lookup, JQL search, comments, and optional Git helpers—see `src/main/resources/META-INF/plugin.xml` for the full feature list.

## Requirements

- **JDK 17**
- Gradle (wrapper included): `./gradlew` / `gradlew.bat`

## Build and run locally

```bash
./gradlew runIde
```

On Windows:

```bash
gradlew.bat runIde
```

## Verify the plugin

```bash
./gradlew verifyPlugin
```

## Package a distribution

```bash
./gradlew buildPlugin
```

The ZIP is under `build/distributions/`.

## Configuration

In the IDE: **Settings → Tools → Jira Companion**. You need your Jira site URL, Atlassian account email, and an [API token](https://support.atlassian.com/atlassian-account/docs/manage-api-tokens-for-your-atlassian-account/). Credentials are stored in the IDE credential store, not in this repository.

## Optional: Android Studio sandbox

Set `jiraPlugin.useAndroidStudio=true` in `gradle.properties`, sync, then use the **Run Plugin** configuration. See comments in `build.gradle.kts` for platform version notes.

## License

This project is licensed under the [MIT License](LICENSE).

## JetBrains Marketplace (open source listing)

If you publish this plugin as **open source** on [JetBrains Marketplace](https://plugins.jetbrains.com/), you should:

1. **Host the source publicly** (for example GitHub) and keep it in sync with what you ship.
2. Set the **Source code** URL in the plugin’s admin page to that repository (required for OSS-distributed plugins).
3. Declare the **same license** on the Marketplace as in the `LICENSE` file at the repo root (MIT here).

A README is not a separate legal requirement, but it helps users and reviewers; this file doubles as basic documentation. You can also link to external docs from the Marketplace listing if you prefer.
