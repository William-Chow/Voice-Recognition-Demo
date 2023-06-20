package com.kotlin.voice

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.speech.RecognizerIntent
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.example.voice.R
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : AppCompatActivity() {
    // on below line we are creating variables
    // for text view and image view
    private lateinit var outputTV: TextView
    private lateinit var micIV: ImageView

    private val url = "https://www.google.com/search?client=chrome&q={query}&hl=${Locale.getDefault().language}"

    // on below line we are creating a constant value
    private val REQUEST_CODE_SPEECH_INPUT = 1

    private lateinit var someActivityResultLauncher: ActivityResultLauncher<Intent>

    @OptIn(DelicateCoroutinesApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        someActivityResultLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                if (result.resultCode == Activity.RESULT_OK) {
                    if (result.data != null) {
                        val query = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.let { if (it.size > 0) it[0] else null } ?: ""
                        // Log.d("William", "MY_DATA:$query")
                        outputTV.text = query
                        GlobalScope.launch {
                            delay(2000L)
                            intent(query)
                        }
                    }
                }
            }

        // initializing variables of list view with their ids.
        outputTV = findViewById(R.id.idTVOutput)
        micIV = findViewById(R.id.idIVMic)

        // on below line we are adding on click
        // listener for mic image view.
        micIV.setOnClickListener {
            // on below line we are calling speech recognizer intent.
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)

            // on below line we are passing language model
            // and model free form in our intent
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)

            // on below line we are passing our
            // language as a default language.
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())

            // on below line we are specifying a prompt
            // message as speak to text on below line.
            intent.putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak to text")

            // on below line we are specifying a try catch block.
            // in this block we are calling a start activity
            // for result method and passing our result code.
            someActivityResultLauncher.launch(intent)
        }
    }

    private fun intent(query: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url.replace("{query}", query)))
            this@MainActivity.startActivity(intent)
        } catch (ex: ActivityNotFoundException) {
            Toast.makeText(this@MainActivity, "This application is not found.", Toast.LENGTH_SHORT).show()
        }
    }
}