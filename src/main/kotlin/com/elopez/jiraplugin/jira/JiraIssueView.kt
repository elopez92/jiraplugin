package com.elopez.jiraplugin.jira

data class JiraIssueView(
    val key: String,
    val summary: String,
    val status: String,
    val issueType: String,
    val assigneeAccountId: String? = null,
    val assigneeDisplayName: String? = null,
    val isWatching: Boolean = false,
    val descriptionHtml: String?,
    val descriptionPlainFallback: String?,
    val comments: List<JiraComment> = emptyList(),
)

data class JiraComment(
    val author: String,
    val created: String,
    val bodyPlain: String,
)

sealed class JiraFetchResult {
    data class Ok(val issue: JiraIssueView) : JiraFetchResult()
    data class ConfigError(val message: String) : JiraFetchResult()
    data class HttpError(val code: Int, val message: String) : JiraFetchResult()
    data class NetworkError(val message: String) : JiraFetchResult()
}

/** Row in the issue picker list (from /rest/api/3/search). */
data class JiraIssueListItem(
    val key: String,
    val summary: String,
    val status: String,
    /** Epoch millis from `fields.updated`, or 0 if missing. */
    val updatedEpochMillis: Long = 0L,
)

sealed class JiraSearchListResult {
    data class Ok(val issues: List<JiraIssueListItem>) : JiraSearchListResult()
    data class ConfigError(val message: String) : JiraSearchListResult()
    data class HttpError(val code: Int, val message: String) : JiraSearchListResult()
    data class NetworkError(val message: String) : JiraSearchListResult()
}

sealed class JiraIssueActionResult {
    data class Ok(val message: String) : JiraIssueActionResult()
    data class ConfigError(val message: String) : JiraIssueActionResult()
    data class HttpError(val code: Int, val message: String) : JiraIssueActionResult()
    data class NetworkError(val message: String) : JiraIssueActionResult()
}
