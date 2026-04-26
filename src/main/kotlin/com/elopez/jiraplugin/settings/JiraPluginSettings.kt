package com.elopez.jiraplugin.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil
import com.elopez.jiraplugin.jira.JiraBaseUrls

@Service(Service.Level.APP)
@State(name = "JiraPluginSettings", storages = [Storage("jiraPlugin.xml")])
class JiraPluginSettings : PersistentStateComponent<JiraPluginSettings.State> {

    private var state = State()

    class State {
        @JvmField
        var baseUrl: String = ""

        @JvmField
        var email: String = ""
    }

    override fun getState(): State = state

    override fun loadState(loaded: State) {
        XmlSerializerUtil.copyBean(loaded, state)
    }

    fun getBaseUrl(): String = JiraBaseUrls.normalizeRestBase(state.baseUrl)

    fun getEmail(): String = state.email.trim()

    companion object {
        fun getInstance(): JiraPluginSettings =
            ApplicationManager.getApplication().getService(JiraPluginSettings::class.java)
    }
}
