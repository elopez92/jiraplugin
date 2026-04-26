package com.elopez.jiraplugin.jira

import java.net.URI

/**
 * Jira Cloud REST lives at `https://site.atlassian.net/rest/api/3/...`, never under `/jira/...`.
 * Users often paste the browser URL which includes `/jira` — that breaks every API call with 404.
 */
object JiraBaseUrls {

    fun normalizeRestBase(raw: String): String {
        val s = raw.trim().trimEnd('/')
        if (s.isEmpty()) return ""
        val withScheme = if (!s.contains("://")) "https://$s" else s
        return try {
            val uri = URI.create(withScheme)
            val scheme = uri.scheme ?: "https"
            val host = uri.host
            if (host.isNullOrBlank()) {
                return s.trimEnd('/')
            }
            val portPart =
                if (uri.port > 0 && uri.port != defaultPort(scheme)) ":${uri.port}" else ""
            if (host.endsWith(".atlassian.net", ignoreCase = true)) {
                "$scheme://$host$portPart"
            } else {
                s.trimEnd('/')
            }
        } catch (_: Exception) {
            s.trimEnd('/')
        }
    }

    private fun defaultPort(scheme: String): Int =
        if (scheme.equals("https", ignoreCase = true)) 443 else 80
}
