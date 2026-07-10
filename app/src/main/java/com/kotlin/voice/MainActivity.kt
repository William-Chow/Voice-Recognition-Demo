package com.kotlin.voice

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.ads.MobileAds
import com.kotlin.voice.ui.theme.VoiceTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var inAppUpdate: InAppUpdate

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        inAppUpdate = InAppUpdate(this)

        // Initialize the ads SDK off the main thread so it doesn't block first frame.
        lifecycleScope.launch(Dispatchers.IO) {
            MobileAds.initialize(this@MainActivity) { }
        }

        setContent {
            VoiceTheme {
                VoiceSearchScreen()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        inAppUpdate.onResume()
    }

    override fun onDestroy() {
        inAppUpdate.onDestroy()
        super.onDestroy()
    }
}
