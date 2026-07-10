package com.kotlin.voice

import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
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
    val engine: SearchEngine = SearchEngine.GOOGLE,
    val isListening: Boolean = false,
    val countdownActive: Boolean = false,
    val countdownProgress: Float = 0f,
    val countdownSecondsLeft: Int = 0,
    val history: List<String> = emptyList(),
) {
    /** A committed result exists (not the live, still-changing partial transcript). */
    val hasResult: Boolean get() = query.isNotBlank()
}

/** One-shot side effects the UI performs (needs Activity context). */
sealed interface VoiceEffect {
    data class OpenUrl(val url: String) : VoiceEffect
    data class ShowMessage(val message: String) : VoiceEffect
}

class VoiceSearchViewModel(app: Application) : AndroidViewModel(app), RecognitionListener {

    private val repository = SearchHistoryRepository(app)

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

    init {
        viewModelScope.launch {
            repository.history.collect { list ->
                _uiState.update { it.copy(history = list) }
            }
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
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(this@VoiceSearchViewModel)
            }
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        _rms.value = 0f
        _uiState.update { it.copy(isListening = true, partialText = "", query = "") }
        recognizer?.startListening(intent)
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
        if (text.isNotBlank()) startCountdown()
    }

    override fun onError(error: Int) {
        _rms.value = 0f
        _uiState.update { it.copy(isListening = false, partialText = "") }
        if (aborted) {
            // Error from the user-cancelled session (e.g. ERROR_CLIENT) — don't nag with a toast.
            aborted = false
            return
        }
        val context = getApplication<Application>()
        val message = when (error) {
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
        countdownJob = viewModelScope.launch {
            var elapsed = 0L
            _uiState.update {
                it.copy(countdownActive = true, countdownProgress = 1f, countdownSecondsLeft = seconds(COUNTDOWN_MS))
            }
            while (elapsed < COUNTDOWN_MS) {
                delay(STEP_MS)
                elapsed += STEP_MS
                val remaining = (COUNTDOWN_MS - elapsed).coerceAtLeast(0)
                _uiState.update {
                    it.copy(
                        countdownProgress = remaining.toFloat() / COUNTDOWN_MS,
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
        emit(VoiceEffect.OpenUrl(state.engine.urlFor(query)))
    }

    // endregion

    // region UI intents

    fun onQueryChange(text: String) {
        if (_uiState.value.countdownActive) cancelCountdown()
        _uiState.update { it.copy(query = text) }
    }

    fun onEngineSelected(engine: SearchEngine) {
        _uiState.update { it.copy(engine = engine) }
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
        const val COUNTDOWN_MS = 2000L
        const val STEP_MS = 50L
    }
}
