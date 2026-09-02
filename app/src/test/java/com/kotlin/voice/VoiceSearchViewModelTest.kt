package com.kotlin.voice

import android.app.Application
import android.os.Bundle
import android.speech.SpeechRecognizer
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** Swaps Dispatchers.Main for a test dispatcher so viewModelScope runs on virtual time. */
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(val dispatcher: TestDispatcher = StandardTestDispatcher()) : TestWatcher() {
    override fun starting(description: Description) = Dispatchers.setMain(dispatcher)
    override fun finished(description: Description) = Dispatchers.resetMain()
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class VoiceSearchViewModelTest {

    @get:Rule(order = 0)
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule(order = 1)
    val tempFolder = TemporaryFolder()

    private lateinit var storeScope: CoroutineScope
    private lateinit var repository: SearchPreferencesRepository

    @Before
    fun setUp() {
        // The store shares the test scheduler, so advanceUntilIdle() also drains its writes.
        storeScope = CoroutineScope(UnconfinedTestDispatcher(mainDispatcherRule.dispatcher.scheduler) + Job())
        val store = PreferenceDataStoreFactory.create(scope = storeScope) {
            File(tempFolder.root, "vm.preferences_pb")
        }
        repository = SearchPreferencesRepository(store)
    }

    @After
    fun tearDown() {
        storeScope.cancel()
    }

    // region Countdown

    @Test
    fun `a recognised phrase auto-searches once the countdown runs out`() = runTest {
        val viewModel = createViewModel()
        val effects = collectEffects(viewModel)

        viewModel.onResults(resultsOf("weather today"))
        runCurrent()

        assertEquals("weather today", viewModel.uiState.value.query)
        assertTrue(viewModel.uiState.value.countdownActive)

        advanceTimeBy(1_900)
        runCurrent()
        assertTrue("search fired before the countdown finished", effects.isEmpty())

        advanceUntilIdle()
        assertEquals(
            listOf(VoiceEffect.OpenUrl("https://www.google.com/search?q=weather%20today")),
            effects,
        )
        assertFalse(viewModel.uiState.value.countdownActive)
    }

    @Test
    fun `cancelling the countdown keeps the text but skips the search`() = runTest {
        val viewModel = createViewModel()
        val effects = collectEffects(viewModel)

        viewModel.onResults(resultsOf("kotlin coroutines"))
        runCurrent()
        viewModel.cancelCountdown()
        advanceUntilIdle()

        assertTrue(effects.isEmpty())
        assertFalse(viewModel.uiState.value.countdownActive)
        assertEquals("kotlin coroutines", viewModel.uiState.value.query)
    }

    @Test
    fun `editing the query cancels the pending auto-search`() = runTest {
        val viewModel = createViewModel()
        val effects = collectEffects(viewModel)

        viewModel.onResults(resultsOf("kotlin"))
        runCurrent()
        viewModel.onQueryChange("kotlin flow")
        advanceUntilIdle()

        assertTrue(effects.isEmpty())
        assertEquals("kotlin flow", viewModel.uiState.value.query)
    }

    @Test
    fun `leaving the foreground cancels the pending auto-search`() = runTest {
        val viewModel = createViewModel()
        val effects = collectEffects(viewModel)

        viewModel.onResults(resultsOf("kotlin"))
        runCurrent()
        viewModel.onStop()
        advanceUntilIdle()

        assertTrue("app searched while backgrounded", effects.isEmpty())
    }

    // endregion

    // region Abort race

    @Test
    fun `a result arriving after the user taps stop is ignored`() = runTest {
        val viewModel = createViewModel()
        val effects = collectEffects(viewModel)

        viewModel.stopListening()
        viewModel.onResults(resultsOf("ignore me"))
        advanceUntilIdle()

        assertEquals("", viewModel.uiState.value.query)
        assertFalse(viewModel.uiState.value.countdownActive)
        assertTrue(effects.isEmpty())
    }

    @Test
    fun `an error from the aborted session does not nag the user`() = runTest {
        val viewModel = createViewModel()
        val effects = collectEffects(viewModel)

        viewModel.stopListening()
        viewModel.onError(SpeechRecognizer.ERROR_CLIENT)
        advanceUntilIdle()

        assertTrue(effects.isEmpty())
    }

    @Test
    fun `an error outside an abort is reported`() = runTest {
        val viewModel = createViewModel()
        val effects = collectEffects(viewModel)

        viewModel.onError(SpeechRecognizer.ERROR_NO_MATCH)
        advanceUntilIdle()

        assertEquals(1, effects.size)
        assertTrue(effects.first() is VoiceEffect.ShowMessage)
        assertFalse(viewModel.uiState.value.isListening)
    }

    @Test
    fun `a busy recognizer is treated as transient and stays silent`() = runTest {
        val viewModel = createViewModel()
        val effects = collectEffects(viewModel)

        viewModel.onError(SpeechRecognizer.ERROR_RECOGNIZER_BUSY)
        advanceUntilIdle()

        assertTrue(effects.isEmpty())
    }

    // endregion

    // region Search + persistence

    @Test
    fun `the chosen engine is used for the search and remembered`() = runTest {
        val viewModel = createViewModel()
        val effects = collectEffects(viewModel)

        viewModel.onEngineSelected(SearchEngine.DUCKDUCKGO)
        advanceUntilIdle()
        assertEquals(SearchEngine.DUCKDUCKGO, viewModel.uiState.value.engine)
        assertEquals(SearchEngine.DUCKDUCKGO, repository.settings.first().engine)

        viewModel.onQueryChange("privacy")
        viewModel.searchNow()
        advanceUntilIdle()

        assertEquals(listOf(VoiceEffect.OpenUrl("https://duckduckgo.com/?q=privacy")), effects)
    }

    @Test
    fun `a completed search is written to history`() = runTest {
        val viewModel = createViewModel()
        collectEffects(viewModel)

        viewModel.onQueryChange("  kotlin flow  ")
        viewModel.searchNow()
        advanceUntilIdle()

        assertEquals(listOf("kotlin flow"), repository.history.first())
    }

    @Test
    fun `a blank query never searches`() = runTest {
        val viewModel = createViewModel()
        val effects = collectEffects(viewModel)

        viewModel.onQueryChange("   ")
        viewModel.searchNow()
        advanceUntilIdle()

        assertTrue(effects.isEmpty())
        assertEquals(emptyList<String>(), repository.history.first())
    }

    @Test
    fun `tapping a history entry searches it straight away`() = runTest {
        val viewModel = createViewModel()
        val effects = collectEffects(viewModel)

        viewModel.onHistoryClick("kotlin flow")
        advanceUntilIdle()

        assertEquals(
            listOf(VoiceEffect.OpenUrl("https://www.google.com/search?q=kotlin%20flow")),
            effects,
        )
    }

    @Test
    fun `the stored engine is restored on the next launch`() = runTest {
        repository.setEngine(SearchEngine.BING)
        advanceUntilIdle()

        val viewModel = createViewModel()

        assertEquals(SearchEngine.BING, viewModel.uiState.value.engine)
    }

    @Test
    fun `stored history shows up in the ui state`() = runTest {
        repository.add("kotlin")
        repository.add("compose")
        advanceUntilIdle()

        val viewModel = createViewModel()

        assertEquals(listOf("compose", "kotlin"), viewModel.uiState.value.history)
    }

    // endregion

    // region Speech language

    @Test
    fun `picking a language persists it`() = runTest {
        val viewModel = createViewModel()

        viewModel.onLanguageSelected("zh-CN")
        advanceUntilIdle()

        assertEquals("zh-CN", viewModel.uiState.value.languageTag)
        assertEquals("zh-CN", repository.settings.first().languageTag)
    }

    @Test
    fun `falling back to the app language clears the stored tag`() = runTest {
        val viewModel = createViewModel()

        viewModel.onLanguageSelected("zh-CN")
        advanceUntilIdle()
        viewModel.onLanguageSelected(null)
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.languageTag)
        assertNull(repository.settings.first().languageTag)
    }

    @Test
    fun `a stored language is restored on the next launch`() = runTest {
        repository.setLanguageTag("ja-JP")
        advanceUntilIdle()

        val viewModel = createViewModel()

        assertEquals("ja-JP", viewModel.uiState.value.languageTag)
    }

    @Test
    fun `a network failure retries on-device before bothering the user`() = runTest {
        val viewModel = createViewModel()
        val effects = collectEffects(viewModel)

        viewModel.onError(SpeechRecognizer.ERROR_NETWORK)
        advanceUntilIdle()

        assertTrue("gave up before trying offline", effects.isEmpty())
    }

    @Test
    fun `a second network failure is reported instead of retrying forever`() = runTest {
        val viewModel = createViewModel()
        val effects = collectEffects(viewModel)

        viewModel.onError(SpeechRecognizer.ERROR_NETWORK)
        viewModel.onError(SpeechRecognizer.ERROR_NETWORK)
        advanceUntilIdle()

        assertEquals(1, effects.size)
        assertTrue(effects.first() is VoiceEffect.ShowMessage)
        assertFalse(viewModel.uiState.value.isListening)
    }

    // endregion

    // region Settings

    @Test
    fun `with auto-search off the transcript waits for the user`() = runTest {
        val viewModel = createViewModel()
        viewModel.onAutoSearchMillisSelected(VoiceSettings.AUTO_SEARCH_OFF)
        advanceUntilIdle()
        val effects = collectEffects(viewModel)

        viewModel.onResults(resultsOf("kotlin flow"))
        advanceUntilIdle()

        assertEquals("kotlin flow", viewModel.uiState.value.query)
        assertFalse(viewModel.uiState.value.countdownActive)
        assertTrue("searched even though auto-search is off", effects.isEmpty())

        viewModel.searchNow()
        advanceUntilIdle()
        assertEquals(1, effects.size)
    }

    @Test
    fun `the countdown waits exactly as long as configured`() = runTest {
        val viewModel = createViewModel()
        viewModel.onAutoSearchMillisSelected(1_000L)
        advanceUntilIdle()
        val effects = collectEffects(viewModel)

        viewModel.onResults(resultsOf("kotlin"))
        runCurrent()
        advanceTimeBy(900)
        runCurrent()
        assertTrue("fired before the configured delay", effects.isEmpty())

        advanceUntilIdle()
        assertEquals(1, effects.size)
    }

    @Test
    fun `changing the delay drops a countdown already running`() = runTest {
        val viewModel = createViewModel()
        val effects = collectEffects(viewModel)

        viewModel.onResults(resultsOf("kotlin"))
        runCurrent()
        assertTrue(viewModel.uiState.value.countdownActive)

        viewModel.onAutoSearchMillisSelected(5_000L)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.countdownActive)
        assertTrue(effects.isEmpty())
    }

    @Test
    fun `theme and dynamic colour persist`() = runTest {
        val viewModel = createViewModel()

        viewModel.onThemeModeSelected(ThemeMode.DARK)
        viewModel.onDynamicColorChanged(false)
        advanceUntilIdle()

        assertEquals(ThemeMode.DARK, viewModel.uiState.value.settings.themeMode)
        val stored = repository.settings.first()
        assertEquals(ThemeMode.DARK, stored.themeMode)
        assertEquals(false, stored.dynamicColor)
    }

    @Test
    fun `settings are not reported loaded until they arrive`() = runTest {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val viewModel = VoiceSearchViewModel(app, repository)

        assertFalse(viewModel.uiState.value.settingsLoaded)

        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.settingsLoaded)
    }

    // endregion

    private fun TestScope.createViewModel(): VoiceSearchViewModel {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val viewModel = VoiceSearchViewModel(app, repository)
        // Let the init collectors deliver their first stored values before the test acts.
        advanceUntilIdle()
        return viewModel
    }

    private fun TestScope.collectEffects(viewModel: VoiceSearchViewModel): List<VoiceEffect> {
        val effects = mutableListOf<VoiceEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.effects.collect { effects += it }
        }
        return effects
    }

    private fun resultsOf(vararg matches: String): Bundle = Bundle().apply {
        putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, ArrayList(matches.toList()))
    }
}
