package com.kotlin.voice

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

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "voice_search")

/** How the app picks between the light and dark palettes. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * Everything the user can configure. Delivered as one value so the UI applies a consistent set
 * rather than reacting to four independent flows arriving in an arbitrary order.
 */
data class VoiceSettings(
    val engine: SearchEngine = SearchEngine.GOOGLE,
    /** BCP-47 tag for recognition, or null to follow the app's own language. */
    val languageTag: String? = null,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    /** Delay before searching on its own; [AUTO_SEARCH_OFF] disables it. */
    val autoSearchMillis: Long = DEFAULT_AUTO_SEARCH_MILLIS,
) {
    val autoSearchEnabled: Boolean get() = autoSearchMillis > AUTO_SEARCH_OFF

    companion object {
        const val AUTO_SEARCH_OFF = 0L
        const val DEFAULT_AUTO_SEARCH_MILLIS = 2_000L

        /** Offered in Settings; the stored value is clamped to this set on read. */
        val AUTO_SEARCH_CHOICES = listOf(AUTO_SEARCH_OFF, 1_000L, 2_000L, 3_000L, 5_000L)
    }
}

/**
 * Persists the recent voice searches (most-recent-first, de-duplicated, capped) and every user
 * setting. History is stored as one newline-delimited string so insertion order is preserved.
 */
class SearchPreferencesRepository(private val dataStore: DataStore<Preferences>) {

    constructor(context: Context) : this(context.applicationContext.dataStore)

    val history: Flow<List<String>> = dataStore.data.map { prefs ->
        prefs[HISTORY_KEY].orEmpty()
            .split(SEPARATOR)
            .filter { it.isNotBlank() }
    }

    val settings: Flow<VoiceSettings> = dataStore.data.map { prefs ->
        VoiceSettings(
            // An engine or theme dropped in a later release must not brick the app, so an
            // unrecognised stored name falls back to the default rather than throwing.
            engine = prefs[ENGINE_KEY]
                ?.let { stored -> SearchEngine.entries.firstOrNull { it.name == stored } }
                ?: SearchEngine.GOOGLE,
            languageTag = prefs[LANGUAGE_KEY]?.takeIf { it.isNotBlank() },
            themeMode = prefs[THEME_KEY]
                ?.let { stored -> ThemeMode.entries.firstOrNull { it.name == stored } }
                ?: ThemeMode.SYSTEM,
            dynamicColor = prefs[DYNAMIC_COLOR_KEY] ?: true,
            autoSearchMillis = prefs[AUTO_SEARCH_KEY]
                ?.takeIf { it in VoiceSettings.AUTO_SEARCH_CHOICES }
                ?: VoiceSettings.DEFAULT_AUTO_SEARCH_MILLIS,
        )
    }

    suspend fun setEngine(engine: SearchEngine) {
        dataStore.edit { prefs -> prefs[ENGINE_KEY] = engine.name }
    }

    suspend fun setLanguageTag(tag: String?) {
        dataStore.edit { prefs ->
            if (tag.isNullOrBlank()) prefs.remove(LANGUAGE_KEY) else prefs[LANGUAGE_KEY] = tag
        }
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit { prefs -> prefs[THEME_KEY] = mode.name }
    }

    suspend fun setDynamicColor(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[DYNAMIC_COLOR_KEY] = enabled }
    }

    suspend fun setAutoSearchMillis(millis: Long) {
        dataStore.edit { prefs -> prefs[AUTO_SEARCH_KEY] = millis }
    }

    suspend fun add(query: String) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return
        dataStore.edit { prefs ->
            val current = prefs[HISTORY_KEY].orEmpty().split(SEPARATOR).filter { it.isNotBlank() }
            val updated = (listOf(trimmed) + current.filter { !it.equals(trimmed, ignoreCase = true) })
                .take(MAX_ENTRIES)
            prefs[HISTORY_KEY] = updated.joinToString(SEPARATOR)
        }
    }

    suspend fun remove(query: String) {
        dataStore.edit { prefs ->
            val updated = prefs[HISTORY_KEY].orEmpty()
                .split(SEPARATOR)
                .filter { it.isNotBlank() && it != query }
            prefs[HISTORY_KEY] = updated.joinToString(SEPARATOR)
        }
    }

    suspend fun clear() {
        dataStore.edit { prefs -> prefs.remove(HISTORY_KEY) }
    }

    private companion object {
        const val MAX_ENTRIES = 20
        const val SEPARATOR = "\n"
        val HISTORY_KEY = stringPreferencesKey("recent_searches")
        val ENGINE_KEY = stringPreferencesKey("search_engine")
        val LANGUAGE_KEY = stringPreferencesKey("speech_language")
        val THEME_KEY = stringPreferencesKey("theme_mode")
        val DYNAMIC_COLOR_KEY = booleanPreferencesKey("dynamic_color")
        val AUTO_SEARCH_KEY = longPreferencesKey("auto_search_millis")
    }
}
