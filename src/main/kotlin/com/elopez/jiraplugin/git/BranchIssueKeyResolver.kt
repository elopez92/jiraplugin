package com.elopez.jiraplugin.git

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.project.Project
import git4idea.repo.GitRepositoryManager

object BranchIssueKeyResolver {

    private val keyPattern = Regex("[A-Z][A-Z0-9]+-\\d+")

    fun isGitIntegrationAvailable(): Boolean {
        val plugin = PluginManagerCore.getPlugin(PluginId.getId("Git4Idea"))
        return plugin != null && plugin.isEnabled
    }

    fun resolveFromCurrentBranch(project: Project): String? {
        if (!isGitIntegrationAvailable()) return null
        val manager = GitRepositoryManager.getInstance(project)
        val branch = manager.repositories.firstOrNull()?.currentBranchName ?: return null
        return keyPattern.find(branch)?.value
    }
}
