package com.medcompany.aireview.settings

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.CredentialStore
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service

data class AiReviewState(
    var enabled: Boolean = true,
    var apiUrl: String = "https://api.deepseek.com/chat/completions",
    var model: String = "deepseek-v4-flash",
    var maxDiffKb: Int = 180,
    var maxContextKb: Int = 240,
    var maxFiles: Int = 80,
    var maxRelatedFiles: Int = 12,
    var blockMedium: Boolean = false,
    var customFocus: String = "业务逻辑正确性、空值和边界条件\n金额精度、状态流转和重复提交\n权限、安全、敏感信息和注入风险\n异步竞态、错误处理和必要测试",
)

data class AiReviewConfig(
    val enabled: Boolean,
    val apiUrl: String,
    val model: String,
    val maxDiffBytes: Int,
    val maxContextBytes: Int,
    val maxFiles: Int,
    val maxRelatedFiles: Int,
    val blockingSeverities: Set<String>,
    val customFocus: List<String>,
)

@Service(Service.Level.APP)
@State(name = "AiCodeReviewSettings", storages = [Storage("AiCodeReview.xml")])
class AiReviewSettings : PersistentStateComponent<AiReviewState> {
    private var currentState = AiReviewState()

    override fun getState(): AiReviewState = currentState

    override fun loadState(state: AiReviewState) {
        if (state.apiUrl.startsWith("https://api.deepseek.com/") && state.model == "deepseek-chat") {
            state.model = "deepseek-v4-flash"
        }
        currentState = state
    }

    fun snapshot(): AiReviewConfig {
        val state = currentState
        val blocking = buildSet {
            add("CRITICAL")
            add("HIGH")
            if (state.blockMedium) add("MEDIUM")
        }
        return AiReviewConfig(
            enabled = state.enabled,
            apiUrl = state.apiUrl.trim(),
            model = state.model.trim(),
            maxDiffBytes = state.maxDiffKb.coerceIn(16, 1024) * 1024,
            maxContextBytes = state.maxContextKb.coerceIn(32, 1024) * 1024,
            maxFiles = state.maxFiles.coerceIn(1, 500),
            maxRelatedFiles = state.maxRelatedFiles.coerceIn(0, 50),
            blockingSeverities = blocking,
            customFocus = state.customFocus.lineSequence().map(String::trim).filter(String::isNotBlank).toList(),
        )
    }

    fun getApiKey(): String =
        readAndMigrateApiKey(PasswordSafe.instance)

    fun setApiKey(apiKey: String) {
        val value = apiKey.trim()
        PasswordSafe.instance.set(
            CREDENTIAL_ATTRIBUTES,
            if (value.isEmpty()) null else Credentials(CREDENTIAL_USER, value),
        )
    }

    internal fun readAndMigrateApiKey(credentialStore: CredentialStore): String {
        credentialStore.get(CREDENTIAL_ATTRIBUTES)
            ?.getPasswordAsString()
            ?.takeIf(String::isNotBlank)
            ?.let { return it }

        LEGACY_CREDENTIAL_ATTRIBUTES.forEach { legacyAttributes ->
            val legacyKey = credentialStore.get(legacyAttributes)
                ?.getPasswordAsString()
                ?.takeIf(String::isNotBlank)
                ?: return@forEach
            credentialStore.set(CREDENTIAL_ATTRIBUTES, Credentials(CREDENTIAL_USER, legacyKey))
            return legacyKey
        }
        return ""
    }

    companion object {
        private const val CREDENTIAL_SERVICE = "com.medcompany.ai-code-review-gate.deepseek-api-key"
        private const val CREDENTIAL_USER = "DeepSeek"

        internal val CREDENTIAL_ATTRIBUTES = CredentialAttributes(
            CREDENTIAL_SERVICE,
            CREDENTIAL_USER,
        )
        internal val LEGACY_CREDENTIAL_ATTRIBUTES = listOf(
            CredentialAttributes(
                generateServiceName("AI Code Review Gate", "deepseek-api-key"),
                null,
            ),
            CredentialAttributes(
                generateServiceName("AI Code Review", "deepseek-api-key"),
                null,
            ),
        )

        fun getInstance(): AiReviewSettings = service()
    }
}
