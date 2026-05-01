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
import java.time.OffsetDateTime
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
            "/rest/api/3/issue/$keyEncoded?fields=summary,status,issuetype,assignee,description,comment,watches&expand=renderedFields"
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
            add("updated")
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

    fun fetchCurrentAccountId(baseUrl: String, email: String, token: String): Result<String> {
        val uri = URI.create(baseUrl.trimEnd('/') + "/rest/api/3/myself")
        val basicToken = basicAuth(email, token)
        val request = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(60))
            .header("Authorization", "Basic $basicToken")
            .header("Accept", "application/json")
            .GET()
            .build()
        return try {
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
            if (response.statusCode() != 200) {
                return Result.failure(IllegalStateException("Failed to fetch account id (HTTP ${response.statusCode()})."))
            }
            val root = JsonParser.parseString(response.body()).asJsonObject
            val accountId = root.get("accountId")?.asString?.trim().orEmpty()
            if (accountId.isBlank()) {
                Result.failure(IllegalStateException("Missing accountId in /myself response."))
            } else {
                Result.success(accountId)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun assignIssueToAccountId(
        baseUrl: String,
        email: String,
        token: String,
        issueKey: String,
        accountId: String,
    ): JiraIssueActionResult {
        val keyEncoded = URLEncoder.encode(issueKey.trim(), StandardCharsets.UTF_8)
        val uri = URI.create(baseUrl.trimEnd('/') + "/rest/api/3/issue/$keyEncoded/assignee")
        val basicToken = basicAuth(email, token)
        val payload = JsonObject().apply { addProperty("accountId", accountId) }.toString()
        val request = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(60))
            .header("Authorization", "Basic $basicToken")
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .PUT(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
            .build()
        return try {
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
            parseIssueActionResponse(response.statusCode(), response.body(), "Assigned issue to you.")
        } catch (e: Exception) {
            JiraIssueActionResult.NetworkError(e.message ?: e.javaClass.simpleName)
        }
    }

    fun addWatcher(
        baseUrl: String,
        email: String,
        token: String,
        issueKey: String,
        accountId: String,
    ): JiraIssueActionResult {
        val keyEncoded = URLEncoder.encode(issueKey.trim(), StandardCharsets.UTF_8)
        val uri = URI.create(baseUrl.trimEnd('/') + "/rest/api/3/issue/$keyEncoded/watchers")
        val basicToken = basicAuth(email, token)
        val request = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(60))
            .header("Authorization", "Basic $basicToken")
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("\"$accountId\"", StandardCharsets.UTF_8))
            .build()
        return try {
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
            parseIssueActionResponse(response.statusCode(), response.body(), "Issue added to your watched list.")
        } catch (e: Exception) {
            JiraIssueActionResult.NetworkError(e.message ?: e.javaClass.simpleName)
        }
    }

    fun removeWatcher(
        baseUrl: String,
        email: String,
        token: String,
        issueKey: String,
        accountId: String,
    ): JiraIssueActionResult {
        val keyEncoded = URLEncoder.encode(issueKey.trim(), StandardCharsets.UTF_8)
        val accountIdEncoded = URLEncoder.encode(accountId, StandardCharsets.UTF_8)
        val uri = URI.create(baseUrl.trimEnd('/') + "/rest/api/3/issue/$keyEncoded/watchers?accountId=$accountIdEncoded")
        val basicToken = basicAuth(email, token)
        val request = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(60))
            .header("Authorization", "Basic $basicToken")
            .header("Accept", "application/json")
            .DELETE()
            .build()
        return try {
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
            parseIssueActionResponse(response.statusCode(), response.body(), "Issue removed from your watched list.")
        } catch (e: Exception) {
            JiraIssueActionResult.NetworkError(e.message ?: e.javaClass.simpleName)
        }
    }

    fun addComment(
        baseUrl: String,
        email: String,
        token: String,
        issueKey: String,
        commentText: String,
    ): JiraIssueActionResult {
        val keyEncoded = URLEncoder.encode(issueKey.trim(), StandardCharsets.UTF_8)
        val uri = URI.create(baseUrl.trimEnd('/') + "/rest/api/3/issue/$keyEncoded/comment")
        val basicToken = basicAuth(email, token)
        val payload = JsonObject().apply {
            add("body", JsonObject().apply {
                addProperty("type", "doc")
                addProperty("version", 1)
                add("content", JsonArray().apply {
                    add(JsonObject().apply {
                        addProperty("type", "paragraph")
                        add("content", JsonArray().apply {
                            add(JsonObject().apply {
                                addProperty("type", "text")
                                addProperty("text", commentText)
                            })
                        })
                    })
                })
            })
        }.toString()
        val request = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(60))
            .header("Authorization", "Basic $basicToken")
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
            .build()
        return try {
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
            parseIssueActionResponse(response.statusCode(), response.body(), "Comment added.")
        } catch (e: Exception) {
            JiraIssueActionResult.NetworkError(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun parseSearchResponse(status: Int, body: String): JiraSearchListResult {
        when (status) {
            200 -> return parseSearchBody(body)
            401, 403 -> return JiraSearchListResult.HttpError(
                status,
                "Authentication failed. Check email and API token (Settings | Tools | Jira Companion).",
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

    private fun parseIssueActionResponse(status: Int, body: String, successMessage: String): JiraIssueActionResult {
        return when (status) {
            200, 201, 204 -> JiraIssueActionResult.Ok(successMessage)
            401, 403 -> JiraIssueActionResult.HttpError(
                status,
                "Authentication failed. Check email and API token (Settings | Tools | Jira Companion).",
            )
            400 -> JiraIssueActionResult.HttpError(
                400,
                parseJiraErrorMessages(body) ?: "Request is invalid.",
            )
            404 -> JiraIssueActionResult.HttpError(
                404,
                "Issue not found or you do not have permission to modify it.",
            )
            in 500..599 -> JiraIssueActionResult.HttpError(
                status,
                "Jira server error ($status). Try again later.",
            )
            else -> JiraIssueActionResult.HttpError(
                status,
                parseJiraErrorMessages(body) ?: "Request failed with HTTP $status.",
            )
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

    /** Jira returns e.g. `2024-08-21T14:22:11.000+0000` (no colon in offset). */
    private fun parseJiraUpdatedMillis(fields: JsonObject?): Long {
        val s = fields?.get("updated")?.takeUnless { it.isJsonNull }?.asString?.trim().orEmpty()
        if (s.isBlank()) return 0L
        val normalized = s.replace(Regex("([+-])(\\d{2})(\\d{2})$"), "$1$2:$3")
        return try {
            OffsetDateTime.parse(normalized).toInstant().toEpochMilli()
        } catch (_: Exception) {
            0L
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
                val updatedMs = parseJiraUpdatedMillis(fields)
                list.add(JiraIssueListItem(key = key, summary = summary, status = status, updatedEpochMillis = updatedMs))
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
                "Authentication failed. Check email and API token (Settings | Tools | Jira Companion).$uriHint",
            )
            404 -> return JiraFetchResult.HttpError(
                status,
                "Issue not found or no permission. Use only the issue key (e.g. KAN-1) or paste the /browse/… URL — not the whole site URL. In the sandbox IDE, set Settings | Tools | Jira Companion again (settings are separate from your main IDE). Base URL must be https://yoursite.atlassian.net with no /jira.$uriHint",
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
            val status = fields.getObject("status")?.get("name")?.asString ?: ""
            val issueType = fields.getObject("issuetype")?.get("name")?.asString ?: ""
            val assigneeObj = fields.getObject("assignee")
            val assigneeAccountId = assigneeObj?.get("accountId")?.asString?.trim()?.ifBlank { null }
            val assigneeDisplayName = assigneeObj?.get("displayName")?.asString?.trim()?.ifBlank { null }
            val isWatching = fields.getObject("watches")?.get("isWatching")?.asBoolean ?: false

            val rendered = root.getAsJsonObject("renderedFields")
            val descHtml = rendered?.get("description")?.takeUnless { it.isJsonNull }?.asString?.takeIf { it.isNotBlank() }

            val plainFromAdf = fields.get("description")?.let { adfToPlain(it) }?.takeIf { it.isNotBlank() }

            val comments = parseComments(fields.getObject("comment"))

            JiraFetchResult.Ok(
                JiraIssueView(
                    key = key,
                    summary = summary,
                    status = status,
                    issueType = issueType,
                    assigneeAccountId = assigneeAccountId,
                    assigneeDisplayName = assigneeDisplayName,
                    isWatching = isWatching,
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

    private fun JsonObject?.getObject(name: String): JsonObject? {
        if (this == null) return null
        val el = this.get(name) ?: return null
        if (el.isJsonNull || !el.isJsonObject) return null
        return el.asJsonObject
    }
}
