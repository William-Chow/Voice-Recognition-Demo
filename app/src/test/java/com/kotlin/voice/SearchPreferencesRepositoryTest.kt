package com.kotlin.voice

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Backed by a throwaway DataStore file, so nothing here touches real app storage. */
class SearchPreferencesRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var storeScope: CoroutineScope
    private lateinit var store: DataStore<Preferences>
    private lateinit var repository: SearchPreferencesRepository

    @Before
    fun setUp() {
        storeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        store = PreferenceDataStoreFactory.create(scope = storeScope) {
            File(tempFolder.root, "test.preferences_pb")
        }
        repository = SearchPreferencesRepository(store)
    }

    @After
    fun tearDown() {
        storeScope.cancel()
    }

    @Test
    fun `history starts empty`() = runTest {
        assertEquals(emptyList<String>(), repository.history.first())
    }

    @Test
    fun `most recent search comes first`() = runTest {
        repository.add("one")
        repository.add("two")
        repository.add("three")
        assertEquals(listOf("three", "two", "one"), repository.history.first())
    }

    @Test
    fun `re-searching a term moves it to the front instead of duplicating`() = runTest {
        repository.add("kotlin")
        repository.add("compose")
        repository.add("kotlin")
        assertEquals(listOf("kotlin", "compose"), repository.history.first())
    }

    @Test
    fun `de-duplication ignores case and keeps the newest spelling`() = runTest {
        repository.add("Kotlin Flow")
        repository.add("kotlin flow")
        assertEquals(listOf("kotlin flow"), repository.history.first())
    }

    @Test
    fun `queries are trimmed and blank ones are dropped`() = runTest {
        repository.add("   spaced   ")
        repository.add("   ")
        repository.add("")
        assertEquals(listOf("spaced"), repository.history.first())
    }

    @Test
    fun `history is capped at twenty entries and drops the oldest`() = runTest {
        repeat(25) { repository.add("query $it") }
        val history = repository.history.first()
        assertEquals(20, history.size)
        assertEquals("query 24", history.first())
        assertEquals("query 5", history.last())
    }

    @Test
    fun `remove deletes only the named entry`() = runTest {
        repository.add("one")
        repository.add("two")
        repository.remove("one")
        assertEquals(listOf("two"), repository.history.first())
    }

    @Test
    fun `clear empties the history`() = runTest {
        repository.add("one")
        repository.add("two")
        repository.clear()
        assertEquals(emptyList<String>(), repository.history.first())
    }

    @Test
    fun `engine defaults to Google before anything is stored`() = runTest {
        assertEquals(SearchEngine.GOOGLE, repository.settings.first().engine)
    }

    @Test
    fun `engine round-trips through storage`() = runTest {
        repository.setEngine(SearchEngine.DUCKDUCKGO)
        assertEquals(SearchEngine.DUCKDUCKGO, repository.settings.first().engine)
    }

    @Test
    fun `an engine removed from a future build falls back to Google`() = runTest {
        store.edit { it[stringPreferencesKey("search_engine")] = "ALTAVISTA" }
        assertEquals(SearchEngine.GOOGLE, repository.settings.first().engine)
    }

    @Test
    fun `speech language is unset until the user picks one`() = runTest {
        assertNull(repository.settings.first().languageTag)
    }

    @Test
    fun `speech language round-trips through storage`() = runTest {
        repository.setLanguageTag("zh-CN")
        assertEquals("zh-CN", repository.settings.first().languageTag)
    }

    @Test
    fun `choosing follow-app-language clears the stored tag`() = runTest {
        repository.setLanguageTag("zh-CN")
        repository.setLanguageTag(null)
        assertNull(repository.settings.first().languageTag)
    }

    @Test
    fun `a blank stored tag reads back as follow-app-language`() = runTest {
        repository.setLanguageTag("   ")
        assertNull(repository.settings.first().languageTag)
    }

    @Test
    fun `theme mode round-trips through storage`() = runTest {
        repository.setThemeMode(ThemeMode.DARK)
        assertEquals(ThemeMode.DARK, repository.settings.first().themeMode)
    }

    @Test
    fun `a theme removed from a future build falls back to system`() = runTest {
        store.edit { it[stringPreferencesKey("theme_mode")] = "SEPIA" }
        assertEquals(ThemeMode.SYSTEM, repository.settings.first().themeMode)
    }

    @Test
    fun `dynamic colour defaults on and round-trips`() = runTest {
        assertEquals(true, repository.settings.first().dynamicColor)
        repository.setDynamicColor(false)
        assertEquals(false, repository.settings.first().dynamicColor)
    }

    @Test
    fun `auto-search delay round-trips through storage`() = runTest {
        repository.setAutoSearchMillis(VoiceSettings.AUTO_SEARCH_OFF)
        val settings = repository.settings.first()
        assertEquals(VoiceSettings.AUTO_SEARCH_OFF, settings.autoSearchMillis)
        assertEquals(false, settings.autoSearchEnabled)
    }

    @Test
    fun `a delay outside the offered choices falls back to the default`() = runTest {
        store.edit { it[longPreferencesKey("auto_search_millis")] = 99_999L }
        assertEquals(
            VoiceSettings.DEFAULT_AUTO_SEARCH_MILLIS,
            repository.settings.first().autoSearchMillis,
        )
    }

    @Test
    fun `clearing history leaves the chosen engine alone`() = runTest {
        repository.setEngine(SearchEngine.BING)
        repository.add("one")
        repository.clear()
        assertEquals(SearchEngine.BING, repository.settings.first().engine)
    }
}
