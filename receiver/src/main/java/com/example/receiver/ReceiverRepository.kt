package com.example.receiver

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
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
        val AUTO_OPEN_MAPS_APP_KEY = stringPreferencesKey("auto_open_maps_app")
        val AUTO_OPEN_GEO_APP_KEY = stringPreferencesKey("auto_open_geo_app")
        val AUTO_OPEN_DELAY_KEY = intPreferencesKey("auto_open_delay")
        
        const val DEFAULT_NTFY_SERVER = "https://ntfy.sh"
        const val APP_NONE = "none"
        const val APP_MAPS = "maps"
        const val APP_WAZE = "waze"
        const val APP_OTHER = "other"
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

    val autoOpenMapsApp: Flow<String> = context.dataStore.data
        .map { preferences ->
            preferences[AUTO_OPEN_MAPS_APP_KEY] ?: APP_NONE
        }

    val autoOpenGeoApp: Flow<String> = context.dataStore.data
        .map { preferences ->
            preferences[AUTO_OPEN_GEO_APP_KEY] ?: APP_NONE
        }

    val autoOpenDelay: Flow<Int> = context.dataStore.data
        .map { preferences ->
            preferences[AUTO_OPEN_DELAY_KEY] ?: 5 // Default 5 seconds
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

    suspend fun saveAutoOpenMapsApp(app: String) {
        context.dataStore.edit { preferences ->
            preferences[AUTO_OPEN_MAPS_APP_KEY] = app
        }
    }

    suspend fun saveAutoOpenGeoApp(app: String) {
        context.dataStore.edit { preferences ->
            preferences[AUTO_OPEN_GEO_APP_KEY] = app
        }
    }

    suspend fun saveAutoOpenDelay(seconds: Int) {
        context.dataStore.edit { preferences ->
            preferences[AUTO_OPEN_DELAY_KEY] = seconds
        }
    }
}
