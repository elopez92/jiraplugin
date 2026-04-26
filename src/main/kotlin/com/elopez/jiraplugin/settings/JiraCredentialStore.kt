package com.elopez.jiraplugin.settings

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.application.ApplicationManager
import com.intellij.util.concurrency.AppExecutorUtil
import java.util.concurrent.Callable

internal object JiraCredentialStore {

    private const val SUBSYSTEM = "JiraPlugin"
    private const val TOKEN_KEY = "apiToken"

    private fun attributes(): CredentialAttributes =
        CredentialAttributes(generateServiceName(SUBSYSTEM, TOKEN_KEY))

    fun getToken(): String? = runOffEdtIfNeeded {
        PasswordSafe.instance.get(attributes())?.getPasswordAsString()
    }

    fun setToken(token: String?) {
        runOffEdtIfNeeded {
            val attrs = attributes()
            if (token.isNullOrBlank()) {
                PasswordSafe.instance.set(attrs, null)
            } else {
                PasswordSafe.instance.set(attrs, Credentials("", token))
            }
        }
    }

    private fun <T> runOffEdtIfNeeded(action: () -> T): T {
        val app = ApplicationManager.getApplication()
        if (!app.isDispatchThread) {
            return action()
        }
        return AppExecutorUtil.getAppExecutorService().submit(Callable(action)).get()
    }
}
