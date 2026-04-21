package com.kotlin.voice

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.ads.MobileAds
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import androidx.core.net.toUri

class MainActivity : AppCompatActivity() {
    private val url = "https://www.google.com/search?client=chrome&q={query}&hl=${Locale.getDefault().language}"
    private lateinit var speechResultLauncher: ActivityResultLauncher<Intent>
    private lateinit var inAppUpdate: InAppUpdate
    private var outputText by mutableStateOf("")
    private var searchJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        inAppUpdate = InAppUpdate(this)
        MobileAds.initialize(this) { }

        speechResultLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                val query = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull().orEmpty()
                outputText = query
                searchJob?.cancel()
                searchJob = lifecycleScope.launch {
                    delay(2000L)
                    openSearchIntent(query)
                }
            }
        }

        setContent {
            MaterialTheme {
                VoiceSearchScreen(
                    outputText = outputText,
                    onMicClick = ::launchSpeechRecognizer
                )
            }
        }
    }

    private fun launchSpeechRecognizer() {
        val speechIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak to text")
        }
        speechResultLauncher.launch(speechIntent)
    }

    private fun openSearchIntent(query: String) {
        if (query.isBlank()) return
        try {
            val browserIntent = Intent(Intent.ACTION_VIEW, url.replace("{query}", query).toUri())
            startActivity(browserIntent)
        } catch (ex: ActivityNotFoundException) {
            Toast.makeText(this, "This application is not found.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        inAppUpdate.onActivityResult(requestCode, resultCode, data)
    }

    override fun onResume() {
        super.onResume()
        inAppUpdate.onResume()
    }

    override fun onDestroy() {
        searchJob?.cancel()
        inAppUpdate.onDestroy()
        super.onDestroy()
    }
}