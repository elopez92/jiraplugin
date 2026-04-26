package com.elopez.jiraplugin.jira

/**
 * Users often paste a full browser URL. The REST path must be only the issue key (or numeric id).
 */
object JiraIssueKeys {

    fun normalizeUserInput(raw: String): String {
        var s = raw.trim()
        if (s.isEmpty()) return ""
        val browse = Regex("""/browse/([^/?#]+)""", RegexOption.IGNORE_CASE).find(s)
        if (browse != null) {
            return browse.groupValues[1].trim()
        }
        return s.substringBefore("?").substringBefore("#").trim()
    }
}
