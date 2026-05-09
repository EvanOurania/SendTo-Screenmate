package com.example.sendtoscreenmate

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class WebhookRepository(val context: Context) {

    companion object {
        val WEBHOOK_URL_KEY = stringPreferencesKey("webhook_url")
        val SERVICE_TYPE_KEY = stringPreferencesKey("service_type")
        val NTFY_TOPIC_KEY = stringPreferencesKey("ntfy_topic")
        val NTFY_SERVER_KEY = stringPreferencesKey("ntfy_server")
        val SECRET_KEY_KEY = stringPreferencesKey("secret_key")
        val ENCRYPTION_ENABLED_KEY = booleanPreferencesKey("encryption_enabled")
        
        const val DEFAULT_URL = "https://trigger.macrodroid.com/YOUR-UUID-HERE/your-identifier"
        const val DEFAULT_NTFY_SERVER = "https://ntfy.sh"
        const val SERVICE_MACRODROID = "macrodroid"
        const val SERVICE_NTFY = "ntfy"
    }

    val webhookUrl: Flow<String> = context.dataStore.data
        .map { preferences ->
            preferences[WEBHOOK_URL_KEY] ?: DEFAULT_URL
        }

    val serviceType: Flow<String> = context.dataStore.data
        .map { preferences ->
            preferences[SERVICE_TYPE_KEY] ?: SERVICE_NTFY
        }

    val ntfyTopic: Flow<String> = context.dataStore.data
        .map { preferences ->
            preferences[NTFY_TOPIC_KEY] ?: ""
        }

    val ntfyServer: Flow<String> = context.dataStore.data
        .map { preferences ->
            preferences[NTFY_SERVER_KEY] ?: DEFAULT_NTFY_SERVER
        }

    val secretKey: Flow<String> = context.dataStore.data
        .map { preferences ->
            preferences[SECRET_KEY_KEY] ?: ""
        }

    val encryptionEnabled: Flow<Boolean> = context.dataStore.data
        .map { preferences ->
            preferences[ENCRYPTION_ENABLED_KEY] ?: true
        }

    suspend fun saveWebhookUrl(url: String) {
        context.dataStore.edit { preferences ->
            preferences[WEBHOOK_URL_KEY] = url
        }
    }

    suspend fun saveServiceType(type: String) {
        context.dataStore.edit { preferences ->
            preferences[SERVICE_TYPE_KEY] = type
        }
    }

    suspend fun saveNtfyTopic(topic: String) {
        context.dataStore.edit { preferences ->
            preferences[NTFY_TOPIC_KEY] = topic
        }
    }

    suspend fun saveNtfyServer(server: String) {
        context.dataStore.edit { preferences ->
            preferences[NTFY_SERVER_KEY] = server
        }
    }

    suspend fun saveSecretKey(key: String) {
        context.dataStore.edit { preferences ->
            preferences[SECRET_KEY_KEY] = key
        }
    }

    suspend fun saveEncryptionEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[ENCRYPTION_ENABLED_KEY] = enabled
        }
    }
}
