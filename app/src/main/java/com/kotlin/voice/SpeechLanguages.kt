package com.kotlin.voice

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import androidx.core.os.LocaleListCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

/** One language the speech service can transcribe. [label] is the language's name in itself. */
data class SpeechLanguage(val tag: String, val label: String)

object SpeechLanguages {

    /**
     * Asks the recognition service which languages it actually supports.
     *
     * Not every engine answers this broadcast, so an unanswered (or slow) query falls back to the
     * locales the device is configured for. Nothing is invented: an engine that reports nothing and
     * a device with one locale yield a single entry, and the UI then hides the picker rather than
     * offering a choice that isn't real.
     */
    suspend fun query(context: Context): List<SpeechLanguage> {
        val reported = withTimeoutOrNull(TIMEOUT_MS) { requestSupportedLanguages(context) }.orEmpty()
        val locales = reported.ifEmpty { configuredLocales() }
        return locales
            .distinctBy { it.toLanguageTag() }
            .map { SpeechLanguage(it.toLanguageTag(), it.getDisplayName(it).replaceFirstChar(Char::uppercase)) }
    }

    private suspend fun requestSupportedLanguages(context: Context): List<Locale> =
        suspendCancellableCoroutine { continuation ->
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(receiverContext: Context?, intent: Intent?) {
                    val extras: Bundle = getResultExtras(true)
                    val tags = extras.getStringArrayList(RecognizerIntent.EXTRA_SUPPORTED_LANGUAGES)
                    // Engines report either BCP-47 ("zh-CN") or Java style ("zh_CN").
                    continuation.resumeWith(
                        Result.success(tags.orEmpty().map { Locale.forLanguageTag(it.replace('_', '-')) })
                    )
                }
            }
            context.sendOrderedBroadcast(
                Intent(RecognizerIntent.ACTION_GET_LANGUAGE_DETAILS),
                null,
                receiver,
                null,
                Activity.RESULT_OK,
                null,
                null,
            )
        }

    private fun configuredLocales(): List<Locale> {
        val configured = LocaleListCompat.getAdjustedDefault()
        return buildList {
            for (index in 0 until configured.size()) configured[index]?.let(::add)
            if (isEmpty()) add(Locale.getDefault())
        }
    }

    private const val TIMEOUT_MS = 2_000L
}
