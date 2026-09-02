package com.kotlin.voice

import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.appcompat.app.AppCompatDelegate
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.ceil

/** Immutable UI state for the voice search screen. */
data class VoiceUiState(
    val query: String = "",
    val partialText: String = "",
    val isListening: Boolean = false,
    val countdownActive: Boolean = false,
    val countdownProgress: Float = 0f,
    val countdownSecondsLeft: Int = 0,
    val history: List<String> = emptyList(),
    /** Languages the speech service reports; fewer than two means there is nothing to choose. */
    val languages: List<SpeechLanguage> = emptyList(),
    val settings: VoiceSettings = VoiceSettings(),
    /** False until stored settings arrive, so the UI never paints the wrong theme first. */
    val settingsLoaded: Boolean = false,
) {
    val engine: SearchEngine get() = settings.engine
    val languageTag: String? get() = settings.languageTag

    /** A committed result exists (not the live, still-changing partial transcript). */
    val hasResult: Boolean get() = query.isNotBlank()
}

/** One-shot side effects the UI performs (needs Activity context). */
sealed interface VoiceEffect {
    data class OpenUrl(val url: String) : VoiceEffect
    data class ShowMessage(val message: String) : VoiceEffect
}

class VoiceSearchViewModel @JvmOverloads constructor(
    app: Application,
    // @JvmOverloads keeps the single-argument constructor AndroidViewModelFactory looks for.
    private val repository: SearchPreferencesRepository = SearchPreferencesRepository(app),
) : AndroidViewModel(app), RecognitionListener {

    private val _uiState = MutableStateFlow(VoiceUiState())
    val uiState: StateFlow<VoiceUiState> = _uiState.asStateFlow()

    private val _effects = Channel<VoiceEffect>(Channel.BUFFERED)
    val effects: Flow<VoiceEffect> = _effects.receiveAsFlow()

    // Audio level is high-frequency; kept off the main UI state so only the mic animation observes it.
    private val _rms = MutableStateFlow(0f)
    val rms: StateFlow<Float> = _rms.asStateFlow()

    private var recognizer: SpeechRecognizer? = null
    private var countdownJob: Job? = null

    // Set when the user taps Stop so a late onResults/onError from the aborted session is ignored.
    private var aborted = false

    // One on-device retry per session after the online service turns out to be unreachable.
    private var retriedOffline = false

    init {
        viewModelScope.launch {
            repository.history.collect { list ->
                _uiState.update { it.copy(history = list) }
            }
        }
        viewModelScope.launch {
            repository.settings.collect { stored ->
                _uiState.update { it.copy(settings = stored, settingsLoaded = true) }
            }
        }
        viewModelScope.launch {
            val languages = SpeechLanguages.query(getApplication())
            _uiState.update { it.copy(languages = languages) }
        }
    }

    // region Speech recognition

    fun startListening() {
        val context = getApplication<Application>()
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            emit(VoiceEffect.ShowMessage(context.getString(R.string.speech_not_supported)))
            return
        }
        cancelCountdown()
        aborted = false
        retriedOffline = false
        _rms.value = 0f
        _uiState.update { it.copy(isListening = true, partialText = "", query = "") }
        startRecognition(preferOffline = false)
    }

    private fun startRecognition(preferOffline: Boolean) {
        val context = getApplication<Application>()
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(this@VoiceSearchViewModel)
            }
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, activeLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            // Some recognition services reject requests that don't identify the caller.
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            if (preferOffline) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }
        recognizer?.startListening(intent)
    }

    /**
     * The language to transcribe in: the user's explicit pick, else the language the app itself is
     * running in. Locale.getDefault() alone would ignore a per-app language chosen in Settings.
     */
    private fun activeLanguageTag(): String {
        _uiState.value.languageTag?.let { return it }
        val appLocales = AppCompatDelegate.getApplicationLocales()
        return (appLocales[0] ?: Locale.getDefault()).toLanguageTag()
    }

    /** User-initiated abort: cancel() (unlike stopListening) does NOT deliver a final result. */
    fun stopListening() {
        aborted = true
        recognizer?.cancel()
        _rms.value = 0f
        _uiState.update { it.copy(isListening = false, partialText = "") }
    }

    override fun onReadyForSpeech(params: Bundle?) = Unit
    override fun onBeginningOfSpeech() = Unit
    override fun onBufferReceived(buffer: ByteArray?) = Unit
    override fun onEvent(eventType: Int, params: Bundle?) = Unit

    override fun onRmsChanged(rmsdB: Float) {
        _rms.value = rmsdB
    }

    override fun onEndOfSpeech() {
        // Keep isListening=true (shows "processing") until onResults/onError arrives, to avoid UI flicker.
        _rms.value = 0f
    }

    override fun onPartialResults(partialResults: Bundle?) {
        firstResult(partialResults)?.let { text ->
            _uiState.update { it.copy(partialText = text) }
        }
    }

    override fun onResults(results: Bundle?) {
        _rms.value = 0f
        if (aborted) {
            aborted = false
            return
        }
        val text = firstResult(results).orEmpty()
        _uiState.update { it.copy(isListening = false, partialText = "", query = text) }
        // With auto-search off the transcript just sits there until the user taps Search.
        if (text.isNotBlank() && _uiState.value.settings.autoSearchEnabled) startCountdown()
    }

    override fun onError(error: Int) {
        _rms.value = 0f
        if (aborted) {
            // Error from the user-cancelled session (e.g. ERROR_CLIENT) — don't nag with a toast.
            aborted = false
            _uiState.update { it.copy(isListening = false, partialText = "") }
            return
        }
        val networkFailure = error == SpeechRecognizer.ERROR_NETWORK ||
            error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT
        if (networkFailure && !retriedOffline) {
            // Airplane mode or dead network: retry once against the on-device model before giving
            // up. Stays isListening so the UI doesn't flicker between the two attempts.
            retriedOffline = true
            startRecognition(preferOffline = true)
            return
        }
        _uiState.update { it.copy(isListening = false, partialText = "") }
        val context = getApplication<Application>()
        val message = when (error) {
            SpeechRecognizer.ERROR_NETWORK,
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> context.getString(R.string.speech_offline_unavailable)
            SpeechRecognizer.ERROR_NO_MATCH,
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> context.getString(R.string.speech_no_match)
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> context.getString(R.string.permission_denied)
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> return // transient; ignore
            else -> context.getString(R.string.speech_error)
        }
        emit(VoiceEffect.ShowMessage(message))
    }

    private fun firstResult(bundle: Bundle?): String? =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()

    // endregion

    // region Countdown + search

    private fun startCountdown() {
        countdownJob?.cancel()
        val total = _uiState.value.settings.autoSearchMillis
        countdownJob = viewModelScope.launch {
            var elapsed = 0L
            _uiState.update {
                it.copy(countdownActive = true, countdownProgress = 1f, countdownSecondsLeft = seconds(total))
            }
            while (elapsed < total) {
                delay(STEP_MS)
                elapsed += STEP_MS
                val remaining = (total - elapsed).coerceAtLeast(0)
                _uiState.update {
                    it.copy(
                        countdownProgress = remaining.toFloat() / total,
                        countdownSecondsLeft = seconds(remaining),
                    )
                }
            }
            performSearch()
        }
    }

    fun cancelCountdown() {
        countdownJob?.cancel()
        countdownJob = null
        _uiState.update { it.copy(countdownActive = false, countdownProgress = 0f, countdownSecondsLeft = 0) }
    }

    fun searchNow() = performSearch()

    private fun performSearch() {
        countdownJob?.cancel()
        countdownJob = null
        val state = _uiState.value
        val query = state.query.trim()
        _uiState.update { it.copy(countdownActive = false, countdownProgress = 0f, countdownSecondsLeft = 0) }
        if (query.isEmpty()) return
        viewModelScope.launch { repository.add(query) }
        emit(VoiceEffect.OpenUrl(state.settings.engine.urlFor(query)))
    }

    // endregion

    // region UI intents

    fun onQueryChange(text: String) {
        if (_uiState.value.countdownActive) cancelCountdown()
        _uiState.update { it.copy(query = text) }
    }

    fun onLanguageSelected(tag: String?) {
        updateSettings({ it.copy(languageTag = tag) }) { repository.setLanguageTag(tag) }
    }

    fun onEngineSelected(engine: SearchEngine) {
        updateSettings({ it.copy(engine = engine) }) { repository.setEngine(engine) }
    }

    fun onThemeModeSelected(mode: ThemeMode) {
        updateSettings({ it.copy(themeMode = mode) }) { repository.setThemeMode(mode) }
    }

    fun onDynamicColorChanged(enabled: Boolean) {
        updateSettings({ it.copy(dynamicColor = enabled) }) { repository.setDynamicColor(enabled) }
    }

    fun onAutoSearchMillisSelected(millis: Long) {
        // A countdown already on screen was started with the old delay; drop it rather than
        // letting it finish on a value the user just changed.
        cancelCountdown()
        updateSettings({ it.copy(autoSearchMillis = millis) }) { repository.setAutoSearchMillis(millis) }
    }

    /** Applies the change to state at once so controls react, then persists it. */
    private inline fun updateSettings(
        crossinline transform: (VoiceSettings) -> VoiceSettings,
        crossinline persist: suspend () -> Unit,
    ) {
        _uiState.update { it.copy(settings = transform(it.settings)) }
        viewModelScope.launch { persist() }
    }

    fun onClearQuery() {
        cancelCountdown()
        _uiState.update { it.copy(query = "", partialText = "") }
    }

    fun onHistoryClick(query: String) {
        _uiState.update { it.copy(query = query) }
        performSearch()
    }

    fun removeHistory(query: String) {
        viewModelScope.launch { repository.remove(query) }
    }

    fun clearHistory() {
        viewModelScope.launch { repository.clear() }
    }

    /** Called when the UI leaves the foreground: stop capturing and don't auto-search in the background. */
    fun onStop() {
        cancelCountdown()
        if (_uiState.value.isListening) stopListening()
    }

    fun onPermissionDenied() {
        emit(VoiceEffect.ShowMessage(getApplication<Application>().getString(R.string.permission_denied)))
    }

    // endregion

    private fun emit(effect: VoiceEffect) {
        _effects.trySend(effect)
    }

    private fun seconds(millis: Long): Int = ceil(millis / 1000f).toInt()

    override fun onCleared() {
        countdownJob?.cancel()
        recognizer?.destroy()
        recognizer = null
        super.onCleared()
    }

    private companion object {
        const val STEP_MS = 50L
    }
}
