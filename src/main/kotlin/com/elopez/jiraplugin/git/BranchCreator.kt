package com.elopez.jiraplugin.git

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import git4idea.branch.GitBrancher
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryManager

object BranchCreator {

    fun suggestBranchName(issueKey: String, summary: String): String {
        val key = issueKey.trim().uppercase()
        val slug = slugify(summary).take(40).ifBlank {"work"}
        return "${key.lowercase()}-$slug"
    }

    fun createAndCheckout(project: Project, branchName: String): Result<String> {
        val normalized = branchName.trim()
        if (normalized.isBlank()) return Result.failure(IllegalArgumentException("Branch name is empty."))

        val repositories = GitRepositoryManager.getInstance(project).repositories
        if (repositories.isEmpty()){
            return Result.failure(IllegalStateException("No Git repository found in this project."))
        }

        GitBrancher.getInstance(project).checkoutNewBranch(normalized, repositories)
        return Result.success(normalized)
    }

    fun createAndCheckoutFromIssue(project: Project, issueKey: String, summary: String): Result<String> {
        val key = issueKey.trim().uppercase()
        if (key.isBlank()) return Result.failure(IllegalArgumentException("Issue key is empty."))
        return createAndCheckout(project, suggestBranchName(key, summary))
    }

    private fun slugify(input: String): String {
        val normalized = StringUtil.toLowerCase(input.trim())
        return normalized
            .replace(Regex("[^a-z0-9]+"), "-")
            .replace(Regex("^-+|-+$"), "")
    }
}