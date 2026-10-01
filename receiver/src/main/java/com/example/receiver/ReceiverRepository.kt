package com.example.receiver

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
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
        val ONLY_ENCRYPTED_KEY = booleanPreferencesKey("only_encrypted")
        val LAST_MESSAGE_TIME_KEY = longPreferencesKey("last_message_time")
        val LAST_MESSAGE_ID_KEY = stringPreferencesKey("last_message_id")
        val AUTO_OPEN_MAPS_APP_KEY = stringPreferencesKey("auto_open_maps_app")
        val AUTO_OPEN_GEO_APP_KEY = stringPreferencesKey("auto_open_geo_app")
        // The apps shown in the chooser
        val CHOOSER_MAPS_APPS_KEY = stringSetPreferencesKey("chooser_maps_apps")
        val CHOOSER_GEO_APPS_KEY = stringSetPreferencesKey("chooser_geo_apps")
        val AUTO_OPEN_DELAY_KEY = intPreferencesKey("auto_open_delay")
        
        val SHOW_RESTART_BUTTON_KEY = booleanPreferencesKey("show_restart_button")
        val SHOW_STOP_BUTTON_KEY = booleanPreferencesKey("show_stop_button")
        val SHOW_REOPEN_BUTTON_KEY = booleanPreferencesKey("show_reopen_button")
        
        const val DEFAULT_NTFY_SERVER = "https://ntfy.sh"
        const val APP_NONE = "none"
        const val APP_MAPS = "maps"
        const val APP_WAZE = "waze"
        // Any other app is saved by its package name

        // "Let Android pick the app", until v1.2.2: replaced by choosing the app
        private const val LEGACY_APP_OTHER = "other"

        // Defaults used until the user changes a setting (e.g. on first launch)
        const val DEFAULT_AUTO_OPEN_APP = APP_WAZE
        val DEFAULT_CHOOSER_APPS = setOf(APP_MAPS, APP_WAZE)
        const val DEFAULT_AUTO_OPEN_DELAY = 5 // seconds
        const val DEFAULT_COPY_TO_CLIPBOARD = true
        const val DEFAULT_ONLY_ENCRYPTED = false
        const val DEFAULT_SHOW_RESTART_BUTTON = true
        const val DEFAULT_SHOW_STOP_BUTTON = false
        const val DEFAULT_SHOW_REOPEN_BUTTON = true

        private val TOPIC_REGEX = Regex("^[-_A-Za-z0-9]{1,64}$")

        /** ntfy topic names may only contain letters, digits, '-' and '_' (max 64 characters). */
        fun isValidTopic(topic: String): Boolean = TOPIC_REGEX.matches(topic)

        /** Adds a missing "https://" and drops a trailing slash, so that e.g. "ntfy.sh" works too. */
        fun normalizeServerUrl(server: String): String {
            val trimmed = server.trim().trimEnd('/')
            return if ("://" in trimmed) trimmed else "https://$trimmed"
        }
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
            preferences[COPY_TO_CLIPBOARD_KEY] ?: DEFAULT_COPY_TO_CLIPBOARD
        }

    val onlyEncrypted: Flow<Boolean> = context.dataStore.data
        .map { preferences ->
            preferences[ONLY_ENCRYPTED_KEY] ?: DEFAULT_ONLY_ENCRYPTED
        }

    val showRestartButton: Flow<Boolean> = context.dataStore.data
        .map { preferences ->
            preferences[SHOW_RESTART_BUTTON_KEY] ?: DEFAULT_SHOW_RESTART_BUTTON
        }

    val showStopButton: Flow<Boolean> = context.dataStore.data
        .map { preferences ->
            preferences[SHOW_STOP_BUTTON_KEY] ?: DEFAULT_SHOW_STOP_BUTTON
        }

    val showReopenButton: Flow<Boolean> = context.dataStore.data
        .map { preferences ->
            preferences[SHOW_REOPEN_BUTTON_KEY] ?: DEFAULT_SHOW_REOPEN_BUTTON
        }

    val lastMessageTime: Flow<Long> = context.dataStore.data
        .map { preferences ->
            preferences[LAST_MESSAGE_TIME_KEY] ?: 0L
        }

    val lastMessageId: Flow<String> = context.dataStore.data
        .map { preferences ->
            preferences[LAST_MESSAGE_ID_KEY] ?: ""
        }

    val autoOpenMapsApp: Flow<String> = context.dataStore.data
        .map { preferences ->
            (preferences[AUTO_OPEN_MAPS_APP_KEY] ?: DEFAULT_AUTO_OPEN_APP).takeIf { it != LEGACY_APP_OTHER } ?: APP_NONE
        }

    val autoOpenGeoApp: Flow<String> = context.dataStore.data
        .map { preferences ->
            (preferences[AUTO_OPEN_GEO_APP_KEY] ?: DEFAULT_AUTO_OPEN_APP).takeIf { it != LEGACY_APP_OTHER } ?: APP_NONE
        }

    val chooserMapsApps: Flow<Set<String>> = context.dataStore.data
        .map { preferences ->
            preferences[CHOOSER_MAPS_APPS_KEY] ?: DEFAULT_CHOOSER_APPS
        }

    val chooserGeoApps: Flow<Set<String>> = context.dataStore.data
        .map { preferences ->
            preferences[CHOOSER_GEO_APPS_KEY] ?: DEFAULT_CHOOSER_APPS
        }

    val autoOpenDelay: Flow<Int> = context.dataStore.data
        .map { preferences ->
            preferences[AUTO_OPEN_DELAY_KEY] ?: DEFAULT_AUTO_OPEN_DELAY
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

    suspend fun saveOnlyEncrypted(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[ONLY_ENCRYPTED_KEY] = enabled
        }
    }

    suspend fun saveShowRestartButton(show: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[SHOW_RESTART_BUTTON_KEY] = show
        }
    }

    suspend fun saveShowStopButton(show: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[SHOW_STOP_BUTTON_KEY] = show
        }
    }

    suspend fun saveShowReopenButton(show: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[SHOW_REOPEN_BUTTON_KEY] = show
        }
    }

    suspend fun saveLastMessageTime(time: Long) {
        context.dataStore.edit { preferences ->
            preferences[LAST_MESSAGE_TIME_KEY] = time
        }
    }

    suspend fun saveLastMessageId(id: String) {
        context.dataStore.edit { preferences ->
            preferences[LAST_MESSAGE_ID_KEY] = id
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

    suspend fun saveChooserMapsApps(apps: Set<String>) {
        context.dataStore.edit { preferences ->
            preferences[CHOOSER_MAPS_APPS_KEY] = apps
        }
    }

    suspend fun saveChooserGeoApps(apps: Set<String>) {
        context.dataStore.edit { preferences ->
            preferences[CHOOSER_GEO_APPS_KEY] = apps
        }
    }

    suspend fun saveAutoOpenDelay(seconds: Int) {
        context.dataStore.edit { preferences ->
            preferences[AUTO_OPEN_DELAY_KEY] = seconds
        }
    }
}
