package com.elopez.jiraplugin.settings

import com.intellij.openapi.options.Configurable
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.elopez.jiraplugin.jira.JiraBaseUrls
import javax.swing.JComponent
import javax.swing.JPanel

class JiraPluginConfigurable : Configurable {

    private val settings = JiraPluginSettings.getInstance()

    private val baseUrlField = JBTextField()
    private val emailField = JBTextField()
    private val tokenField = JBPasswordField()

    private var root: JPanel? = null

    override fun getDisplayName(): String = "Jira Companion"

    override fun createComponent(): JComponent {
        val panel = FormBuilder.createFormBuilder()
            .addLabeledComponent(
                JBLabel("Jira site URL (https://your-site.atlassian.net only — no /jira):"),
                baseUrlField,
                1,
                false,
            )
            .addLabeledComponent(JBLabel("Atlassian account email:"), emailField, 1, false)
            .addLabeledComponent(JBLabel("API token (leave blank to keep current):"), tokenField, 1, false)
            .addComponentFillVertically(JPanel(), 0)
            .panel
        root = panel
        reset()
        return panel
    }

    override fun isModified(): Boolean {
        val st = settings.getState()
        val tokenChanged = tokenField.password.isNotEmpty()
        return JiraBaseUrls.normalizeRestBase(baseUrlField.text.trim()) != JiraBaseUrls.normalizeRestBase(st.baseUrl.trim()) ||
            emailField.text.trim() != st.email.trim() ||
            tokenChanged
    }

    override fun apply() {
        val st = settings.getState()
        st.baseUrl = JiraBaseUrls.normalizeRestBase(baseUrlField.text.trim())
        st.email = emailField.text.trim()
        if (tokenField.password.isNotEmpty()) {
            JiraCredentialStore.setToken(String(tokenField.password))
            tokenField.text = ""
        }
    }

    override fun reset() {
        val st = settings.getState()
        baseUrlField.text = JiraBaseUrls.normalizeRestBase(st.baseUrl.trim())
        emailField.text = st.email
        tokenField.text = ""
    }

    override fun disposeUIResources() {
        root = null
    }
}
