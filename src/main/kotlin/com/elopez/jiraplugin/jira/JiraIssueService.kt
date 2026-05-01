package com.elopez.jiraplugin.jira

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.elopez.jiraplugin.settings.JiraCredentialStore
import com.elopez.jiraplugin.settings.JiraPluginSettings

@Service(Service.Level.APP)
class JiraIssueService {

    @Volatile
    private var cachedMyAccountId: String? = null

    fun fetchIssue(issueKey: String): JiraFetchResult {
        val settings = JiraPluginSettings.getInstance()
        val base = settings.getBaseUrl()
        val email = settings.getEmail()
        val token = JiraCredentialStore.getToken()
        if (base.isBlank()) {
            return JiraFetchResult.ConfigError("Set your Jira base URL in Settings | Tools | Jira Companion.")
        }
        if (email.isBlank() || token.isNullOrBlank()) {
            return JiraFetchResult.ConfigError("Set your Atlassian email and API token in Settings | Tools | Jira Companion.")
        }
        val key = JiraIssueKeys.normalizeUserInput(issueKey)
        if (key.isBlank()) {
            return JiraFetchResult.ConfigError("Enter a Jira issue key (e.g. KAN-1) or paste the issue URL from your browser.")
        }
        return JiraRestClient.fetch(base, email, token, key)
    }

    fun searchIssues(jql: String, maxResults: Int = 50): JiraSearchListResult {
        val settings = JiraPluginSettings.getInstance()
        val base = settings.getBaseUrl()
        val email = settings.getEmail()
        val token = JiraCredentialStore.getToken()
        if (base.isBlank()) {
            return JiraSearchListResult.ConfigError("Set your Jira base URL in Settings | Tools | Jira Companion.")
        }
        if (email.isBlank() || token.isNullOrBlank()) {
            return JiraSearchListResult.ConfigError("Set your Atlassian email and API token in Settings | Tools | Jira Companion.")
        }
        return JiraRestClient.searchIssues(base, email, token, jql, maxResults = maxResults)
    }

    fun assignIssueToMe(issueKey: String): JiraIssueActionResult {
        val cfg = validateConfigAndKey(issueKey) ?: return configErrorForIssueAction(issueKey)
        val accountId = resolveMyAccountId(cfg.baseUrl, cfg.email, cfg.token)
            ?: return JiraIssueActionResult.NetworkError("Could not resolve your Atlassian accountId from /myself.")
        return JiraRestClient.assignIssueToAccountId(cfg.baseUrl, cfg.email, cfg.token, cfg.issueKey, accountId)
    }

    fun addWatcher(issueKey: String): JiraIssueActionResult {
        val cfg = validateConfigAndKey(issueKey) ?: return configErrorForIssueAction(issueKey)
        val accountId = resolveMyAccountId(cfg.baseUrl, cfg.email, cfg.token)
            ?: return JiraIssueActionResult.NetworkError("Could not resolve your Atlassian accountId from /myself.")
        return JiraRestClient.addWatcher(cfg.baseUrl, cfg.email, cfg.token, cfg.issueKey, accountId)
    }

    fun removeWatcher(issueKey: String): JiraIssueActionResult {
        val cfg = validateConfigAndKey(issueKey) ?: return configErrorForIssueAction(issueKey)
        val accountId = resolveMyAccountId(cfg.baseUrl, cfg.email, cfg.token)
            ?: return JiraIssueActionResult.NetworkError("Could not resolve your Atlassian accountId from /myself.")
        return JiraRestClient.removeWatcher(cfg.baseUrl, cfg.email, cfg.token, cfg.issueKey, accountId)
    }

    fun addComment(issueKey: String, commentText: String): JiraIssueActionResult {
        val cfg = validateConfigAndKey(issueKey) ?: return configErrorForIssueAction(issueKey)
        val body = commentText.trim()
        if (body.isBlank()) {
            return JiraIssueActionResult.ConfigError("Comment cannot be empty.")
        }
        return JiraRestClient.addComment(cfg.baseUrl, cfg.email, cfg.token, cfg.issueKey, body)
    }

    fun isIssueAssignedToMe(issue: JiraIssueView): Boolean? {
        val assignedAccountId = issue.assigneeAccountId?.trim().orEmpty()
        if (assignedAccountId.isBlank()) return false
        val settings = JiraPluginSettings.getInstance()
        val base = settings.getBaseUrl()
        val email = settings.getEmail()
        val token = JiraCredentialStore.getToken()
        if (base.isBlank() || email.isBlank() || token.isNullOrBlank()) return null
        val myAccountId = resolveMyAccountId(base, email, token) ?: return null
        return myAccountId == assignedAccountId
    }

    private data class ActionConfig(
        val baseUrl: String,
        val email: String,
        val token: String,
        val issueKey: String,
    )

    private fun validateConfigAndKey(issueKey: String): ActionConfig? {
        val settings = JiraPluginSettings.getInstance()
        val base = settings.getBaseUrl()
        val email = settings.getEmail()
        val token = JiraCredentialStore.getToken()
        val key = JiraIssueKeys.normalizeUserInput(issueKey)
        if (base.isBlank() || email.isBlank() || token.isNullOrBlank() || key.isBlank()) {
            return null
        }
        return ActionConfig(base, email, token, key)
    }

    private fun configErrorForIssueAction(issueKey: String): JiraIssueActionResult {
        val settings = JiraPluginSettings.getInstance()
        if (settings.getBaseUrl().isBlank()) {
            return JiraIssueActionResult.ConfigError("Set your Jira base URL in Settings | Tools | Jira Companion.")
        }
        if (settings.getEmail().isBlank() || JiraCredentialStore.getToken().isNullOrBlank()) {
            return JiraIssueActionResult.ConfigError("Set your Atlassian email and API token in Settings | Tools | Jira Companion.")
        }
        val key = JiraIssueKeys.normalizeUserInput(issueKey)
        if (key.isBlank()) {
            return JiraIssueActionResult.ConfigError("Enter a Jira issue key (e.g. KAN-1) or paste the issue URL from your browser.")
        }
        return JiraIssueActionResult.ConfigError("Missing Jira Companion configuration.")
    }

    private fun resolveMyAccountId(baseUrl: String, email: String, token: String): String? {
        val cached = cachedMyAccountId
        if (!cached.isNullOrBlank()) return cached
        val result = JiraRestClient.fetchCurrentAccountId(baseUrl, email, token)
        val id = result.getOrNull() ?: return null
        cachedMyAccountId = id
        return id
    }

    companion object {
        fun getInstance(): JiraIssueService =
            ApplicationManager.getApplication().getService(JiraIssueService::class.java)
    }
}
