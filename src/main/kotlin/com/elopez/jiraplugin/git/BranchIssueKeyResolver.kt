package com.elopez.jiraplugin.git

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.project.Project
import git4idea.repo.GitRepositoryManager

object BranchIssueKeyResolver {

    private val keyPattern = Regex("(?i)\\b([a-z][a-z0-9]+-\\d+)\\b")

    fun isGitIntegrationAvailable(): Boolean {
        val plugin = PluginManagerCore.getPlugin(PluginId.getId("Git4Idea"))
        return plugin != null && plugin.isEnabled
    }

    fun resolveFromCurrentBranch(project: Project): String? {
        if (!isGitIntegrationAvailable()) return null
        val manager = GitRepositoryManager.getInstance(project)
        val branches = manager.repositories.mapNotNull { it.currentBranchName }
        for (branch in branches) {
            val match = keyPattern.find(branch)?.groupValues?.getOrNull(1) ?: continue
            return match.uppercase()
        }
        return null
    }
}
