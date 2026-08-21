package com.medcompany.aireview.settings

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.CredentialStore
import com.intellij.credentialStore.Credentials
import kotlin.test.Test
import kotlin.test.assertEquals

class AiReviewSettingsTest {
    @Test
    fun `reads key from stable credential id`() {
        val store = InMemoryCredentialStore()
        store.set(
            AiReviewSettings.CREDENTIAL_ATTRIBUTES,
            Credentials("DeepSeek", "current-key"),
        )

        assertEquals("current-key", AiReviewSettings().readAndMigrateApiKey(store))
    }

    @Test
    fun `migrates key saved by an older plugin version`() {
        val store = InMemoryCredentialStore()
        store.set(
            AiReviewSettings.LEGACY_CREDENTIAL_ATTRIBUTES.first(),
            Credentials("DeepSeek", "legacy-key"),
        )

        assertEquals("legacy-key", AiReviewSettings().readAndMigrateApiKey(store))
        assertEquals(
            "legacy-key",
            store.get(AiReviewSettings.CREDENTIAL_ATTRIBUTES)?.getPasswordAsString(),
        )
    }

    private class InMemoryCredentialStore : CredentialStore {
        private val credentials = mutableMapOf<CredentialAttributes, Credentials?>()

        override fun get(attributes: CredentialAttributes): Credentials? = credentials[attributes]

        override fun set(attributes: CredentialAttributes, credentials: Credentials?) {
            this.credentials[attributes] = credentials
        }
    }
}
