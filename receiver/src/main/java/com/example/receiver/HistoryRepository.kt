package com.example.receiver

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.historyDataStore: DataStore<Preferences> by preferencesDataStore(name = "history_settings")

data class HistoryItem(val title: String, val url: String, val timestamp: Long)

class HistoryRepository(val context: Context) {
    companion object {
        val HISTORY_KEY = stringPreferencesKey("history_items")
        private const val MAX_HISTORY = 100
    }

    val historyItems: Flow<List<HistoryItem>> = context.historyDataStore.data
        .map { preferences ->
            val jsonString = preferences[HISTORY_KEY] ?: "[]"
            val jsonArray = JSONArray(jsonString)
            val items = mutableListOf<HistoryItem>()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                items.add(HistoryItem(
                    obj.optString("title"),
                    obj.optString("url"),
                    obj.optLong("timestamp")
                ))
            }
            items.sortedByDescending { it.timestamp }
        }

    suspend fun addHistoryItem(title: String, url: String, timestamp: Long = System.currentTimeMillis()) {
        context.historyDataStore.edit { preferences ->
            val jsonString = preferences[HISTORY_KEY] ?: "[]"
            val jsonArray = JSONArray(jsonString)
            
            // Check for exact duplicate (same URL and same timestamp)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                if (obj.optString("url") == url && obj.optLong("timestamp") == timestamp) {
                    return@edit
                }
            }
            
            val newItem = JSONObject().apply {
                put("title", title)
                put("url", url)
                put("timestamp", timestamp)
            }
            
            // Prepend new item
            val newList = JSONArray()
            newList.put(newItem)
            for (i in 0 until jsonArray.length()) {
                if (i >= MAX_HISTORY - 1) break
                newList.put(jsonArray.get(i))
            }
            
            preferences[HISTORY_KEY] = newList.toString()
        }
    }

    suspend fun clearHistory() {
        context.historyDataStore.edit { preferences ->
            preferences[HISTORY_KEY] = "[]"
        }
    }
}
