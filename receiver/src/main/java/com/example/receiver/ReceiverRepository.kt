package com.example.receiver

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "receiver_settings")

class ReceiverRepository(val context: Context) {

    companion object {
        val NTFY_TOPIC_KEY = stringPreferencesKey("ntfy_topic")
        val NTFY_SERVER_KEY = stringPreferencesKey("ntfy_server")
        val SECRET_KEY_KEY = stringPreferencesKey("secret_key")
        val COPY_TO_CLIPBOARD_KEY = booleanPreferencesKey("copy_to_clipboard")
        val LAST_MESSAGE_TIME_KEY = longPreferencesKey("last_message_time")
        
        const val DEFAULT_NTFY_SERVER = "https://ntfy.sh"
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

    val copyToClipboard: Flow<Boolean> = context.dataStore.data
        .map { preferences ->
            preferences[COPY_TO_CLIPBOARD_KEY] ?: false
        }

    val lastMessageTime: Flow<Long> = context.dataStore.data
        .map { preferences ->
            preferences[LAST_MESSAGE_TIME_KEY] ?: 0L
        }

    suspend fun saveNtfyConfig(topic: String, server: String) {
        context.dataStore.edit { preferences ->
            preferences[NTFY_TOPIC_KEY] = topic
            preferences[NTFY_SERVER_KEY] = server
        }
    }

    suspend fun saveSecretKey(key: String) {
        context.dataStore.edit { preferences ->
            preferences[SECRET_KEY_KEY] = key
        }
    }

    suspend fun saveCopyToClipboard(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[COPY_TO_CLIPBOARD_KEY] = enabled
        }
    }

    suspend fun saveLastMessageTime(time: Long) {
        context.dataStore.edit { preferences ->
            preferences[LAST_MESSAGE_TIME_KEY] = time
        }
    }
}
