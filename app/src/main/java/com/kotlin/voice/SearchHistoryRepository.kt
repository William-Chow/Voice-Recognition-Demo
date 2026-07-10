package com.kotlin.voice

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "voice_search")

/**
 * Persists the most recent voice searches (most-recent-first, de-duplicated, capped).
 * Stored as a single newline-delimited string so insertion order is preserved.
 */
class SearchHistoryRepository(context: Context) {

    private val appContext = context.applicationContext

    val history: Flow<List<String>> = appContext.dataStore.data.map { prefs ->
        prefs[KEY].orEmpty()
            .split(SEPARATOR)
            .filter { it.isNotBlank() }
    }

    suspend fun add(query: String) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return
        appContext.dataStore.edit { prefs ->
            val current = prefs[KEY].orEmpty().split(SEPARATOR).filter { it.isNotBlank() }
            val updated = (listOf(trimmed) + current.filter { !it.equals(trimmed, ignoreCase = true) })
                .take(MAX_ENTRIES)
            prefs[KEY] = updated.joinToString(SEPARATOR)
        }
    }

    suspend fun remove(query: String) {
        appContext.dataStore.edit { prefs ->
            val updated = prefs[KEY].orEmpty()
                .split(SEPARATOR)
                .filter { it.isNotBlank() && it != query }
            prefs[KEY] = updated.joinToString(SEPARATOR)
        }
    }

    suspend fun clear() {
        appContext.dataStore.edit { prefs -> prefs.remove(KEY) }
    }

    private companion object {
        const val MAX_ENTRIES = 20
        const val SEPARATOR = "\n"
        val KEY = stringPreferencesKey("recent_searches")
    }
}
