package com.elopez.jiraplugin.jira

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.elopez.jiraplugin.settings.JiraCredentialStore
import com.elopez.jiraplugin.settings.JiraPluginSettings

@Service(Service.Level.APP)
class JiraIssueService {

    fun fetchIssue(issueKey: String): JiraFetchResult {
        val settings = JiraPluginSettings.getInstance()
        val base = settings.getBaseUrl()
        val email = settings.getEmail()
        val token = JiraCredentialStore.getToken()
        if (base.isBlank()) {
            return JiraFetchResult.ConfigError("Set your Jira base URL in Settings | Tools | Jira.")
        }
        if (email.isBlank() || token.isNullOrBlank()) {
            return JiraFetchResult.ConfigError("Set your Atlassian email and API token in Settings | Tools | Jira.")
        }
        val key = JiraIssueKeys.normalizeUserInput(issueKey)
        if (key.isBlank()) {
            return JiraFetchResult.ConfigError("Enter a Jira issue key (e.g. KAN-1) or paste the issue URL from your browser.")
        }
        return JiraRestClient.fetch(base, email, token, key)
    }

    fun searchIssues(jql: String): JiraSearchListResult {
        val settings = JiraPluginSettings.getInstance()
        val base = settings.getBaseUrl()
        val email = settings.getEmail()
        val token = JiraCredentialStore.getToken()
        if (base.isBlank()) {
            return JiraSearchListResult.ConfigError("Set your Jira base URL in Settings | Tools | Jira.")
        }
        if (email.isBlank() || token.isNullOrBlank()) {
            return JiraSearchListResult.ConfigError("Set your Atlassian email and API token in Settings | Tools | Jira.")
        }
        return JiraRestClient.searchIssues(base, email, token, jql)
    }

    companion object {
        fun getInstance(): JiraIssueService =
            ApplicationManager.getApplication().getService(JiraIssueService::class.java)
    }
}
