package com.elopez.jiraplugin.jira

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.Base64

internal object JiraRestClient {

    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .build()

    private fun basicAuth(email: String, token: String): String =
        Base64.getEncoder().encodeToString(
            "${email.trim()}:${token}".toByteArray(StandardCharsets.UTF_8),
        )

    fun fetch(baseUrl: String, email: String, token: String, issueKey: String): JiraFetchResult {
        val keyEncoded = URLEncoder.encode(issueKey.trim(), StandardCharsets.UTF_8)
        val path =
            "/rest/api/3/issue/$keyEncoded?fields=summary,status,issuetype,description,comment&expand=renderedFields"
        val uri = URI.create(baseUrl.trimEnd('/') + path)
        val basicToken = basicAuth(email, token)
        val request = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(60))
            .header("Authorization", "Basic $basicToken")
            .header("Accept", "application/json")
            .GET()
            .build()
        return try {
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
            parseResponse(response.statusCode(), response.body(), uri.toString())
        } catch (e: Exception) {
            JiraFetchResult.NetworkError(e.message ?: e.javaClass.simpleName)
        }
    }

    fun searchIssues(
        baseUrl: String,
        email: String,
        token: String,
        jql: String,
        maxResults: Int = 50,
    ): JiraSearchListResult {
        // Legacy POST /rest/api/3/search returns HTTP 410 (removed). Use enhanced JQL search (CHANGE-2046).
        val uri = URI.create(baseUrl.trimEnd('/') + "/rest/api/3/search/jql")
        val basicToken = basicAuth(email, token)
        val fields = JsonArray().apply {
            add("summary")
            add("status")
        }
        val payload = JsonObject().apply {
            addProperty("jql", jql)
            addProperty("maxResults", maxResults)
            addProperty("fieldsByKeys", false)
            add("fields", fields)
        }
        val bodyJson = payload.toString()
        val request = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(60))
            .header("Authorization", "Basic $basicToken")
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(bodyJson, StandardCharsets.UTF_8))
            .build()
        return try {
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
            parseSearchResponse(response.statusCode(), response.body())
        } catch (e: Exception) {
            JiraSearchListResult.NetworkError(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun parseSearchResponse(status: Int, body: String): JiraSearchListResult {
        when (status) {
            200 -> return parseSearchBody(body)
            401, 403 -> return JiraSearchListResult.HttpError(
                status,
                "Authentication failed. Check email and API token (Settings | Tools | Jira).",
            )
            400 -> {
                val msg = parseJiraErrorMessages(body) ?: "Invalid JQL or search request."
                return JiraSearchListResult.HttpError(400, msg)
            }
            410 -> return JiraSearchListResult.HttpError(
                410,
                "This Jira site no longer supports the old search API. Update the plugin, or contact support.",
            )
            in 500..599 -> return JiraSearchListResult.HttpError(
                status,
                "Jira server error ($status). Try again later.",
            )
            else -> {
                val msg = parseJiraErrorMessages(body)
                return JiraSearchListResult.HttpError(
                    status,
                    msg ?: "Search failed with HTTP $status.",
                )
            }
        }
    }

    private fun parseJiraErrorMessages(body: String): String? {
        return try {
            val err = JsonParser.parseString(body).asJsonObject
            val arr = err.getAsJsonArray("errorMessages")
            if (arr != null && arr.size() > 0) arr.get(0).asString else null
        } catch (_: Exception) {
            null
        }
    }

    private fun parseSearchBody(body: String): JiraSearchListResult {
        return try {
            val root = JsonParser.parseString(body).asJsonObject
            val issues = root.getAsJsonArray("issues")
                ?: root.getAsJsonArray("values")
                ?: JsonArray()
            val list = mutableListOf<JiraIssueListItem>()
            for (el in issues) {
                if (!el.isJsonObject) continue
                val issue = el.asJsonObject
                val key = issue.get("key")?.asString
                    ?: issue.get("issueKey")?.asString
                    ?: continue
                val fields = issue.getAsJsonObject("fields")
                val summary = fields?.get("summary")?.asString ?: ""
                val status = fields?.getAsJsonObject("status")?.get("name")?.asString ?: ""
                list.add(JiraIssueListItem(key = key, summary = summary, status = status))
            }
            JiraSearchListResult.Ok(list)
        } catch (e: Exception) {
            JiraSearchListResult.NetworkError("Could not parse Jira search response: ${e.message}")
        }
    }

    private fun parseResponse(status: Int, body: String, requestUri: String? = null): JiraFetchResult {
        val uriHint = requestUri?.let { "\n\nRequest: $it" } ?: ""
        when (status) {
            200 -> return parseIssueBody(body)
            401, 403 -> return JiraFetchResult.HttpError(
                status,
                "Authentication failed. Check email and API token (Settings | Tools | Jira).$uriHint",
            )
            404 -> return JiraFetchResult.HttpError(
                status,
                "Issue not found or no permission. Use only the issue key (e.g. KAN-1) or paste the /browse/… URL — not the whole site URL. In the sandbox IDE, set Settings | Tools | Jira again (settings are separate from your main IDE). Base URL must be https://yoursite.atlassian.net with no /jira.$uriHint",
            )
            in 500..599 -> return JiraFetchResult.HttpError(
                status,
                "Jira server error ($status). Try again later.$uriHint",
            )
            else -> {
                val msg = parseJiraErrorMessages(body)
                return JiraFetchResult.HttpError(
                    status,
                    (msg ?: "Request failed with HTTP $status.") + uriHint,
                )
            }
        }
    }

    private fun parseIssueBody(body: String): JiraFetchResult {
        return try {
            val root = JsonParser.parseString(body).asJsonObject
            val key = root.get("key")?.asString ?: return JiraFetchResult.NetworkError("Invalid response: missing issue key.")
            val fields = root.getAsJsonObject("fields")
            val summary = fields.get("summary")?.asString ?: ""
            val status = fields.getAsJsonObject("status")?.get("name")?.asString ?: ""
            val issueType = fields.getAsJsonObject("issuetype")?.get("name")?.asString ?: ""

            val rendered = root.getAsJsonObject("renderedFields")
            val descHtml = rendered?.get("description")?.takeUnless { it.isJsonNull }?.asString?.takeIf { it.isNotBlank() }

            val plainFromAdf = fields.get("description")?.let { adfToPlain(it) }?.takeIf { it.isNotBlank() }

            val comments = parseComments(fields.getAsJsonObject("comment"))

            JiraFetchResult.Ok(
                JiraIssueView(
                    key = key,
                    summary = summary,
                    status = status,
                    issueType = issueType,
                    descriptionHtml = descHtml,
                    descriptionPlainFallback = plainFromAdf,
                    comments = comments,
                ),
            )
        } catch (e: Exception) {
            JiraFetchResult.NetworkError("Could not parse Jira response: ${e.message}")
        }
    }

    private fun parseComments(commentField: JsonObject?): List<JiraComment> {
        if (commentField == null) return emptyList()
        val arr = commentField.getAsJsonArray("comments") ?: return emptyList()
        val out = mutableListOf<JiraComment>()
        for (el in arr) {
            if (!el.isJsonObject) continue
            val c = el.asJsonObject
            val author = c.getAsJsonObject("author")?.get("displayName")?.asString ?: "Unknown"
            val created = c.get("created")?.asString ?: ""
            val bodyPlain = c.get("body")?.let { adfToPlain(it) }?.trim().orEmpty().ifBlank { "(empty)" }
            out.add(JiraComment(author = author, created = created, bodyPlain = bodyPlain))
        }
        return out
    }

    private fun adfToPlain(node: JsonElement?): String {
        if (node == null || node.isJsonNull) return ""
        if (node.isJsonPrimitive) return node.asString
        if (node.isJsonArray) {
            return node.asJsonArray.joinToString("\n") { adfToPlain(it) }.trim()
        }
        if (!node.isJsonObject) return ""
        val o = node.asJsonObject
        val text = o.get("text")?.takeIf { it.isJsonPrimitive }?.asString ?: ""
        val content = o.get("content")
        val rest = if (content != null && content.isJsonArray) {
            content.asJsonArray.joinToString("") { adfToPlain(it) }
        } else {
            ""
        }
        return (text + rest).trim()
    }
}
