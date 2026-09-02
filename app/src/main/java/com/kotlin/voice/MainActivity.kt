package com.kotlin.voice

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.android.material.color.DynamicColors
import com.google.firebase.FirebaseApp
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.kotlin.voice.ui.theme.VoiceTheme

class MainActivity : AppCompatActivity() {

    private lateinit var inAppUpdate: InAppUpdate

    // Read by the splash screen to decide when the first frame may be drawn.
    private var settingsLoaded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        // Must run before super.onCreate: it swaps Theme.App.Starting for Theme.Voice.
        val splashScreen = installSplashScreen()
        // Hold the splash until the stored theme arrives, otherwise someone who chose Dark gets a
        // light frame first while DataStore is still reading.
        splashScreen.setKeepOnScreenCondition { !settingsLoaded }
        super.onCreate(savedInstanceState)

        // Compose opts into Material You (see VoiceTheme), and this keeps the View-side theme in
        // step so the in-app-update Snackbar matches the UI drawn above it. Note it is applied
        // before settings load, so it always follows the system rather than the in-app switch;
        // that switch governs the Compose UI, which is everything the user actually looks at.
        DynamicColors.applyToActivityIfAvailable(this)

        // getApps() is empty when the build had no google-services.json; debug builds stay silent
        // so developer crashes never land in the live dashboard.
        if (FirebaseApp.getApps(this).isNotEmpty()) {
            FirebaseCrashlytics.getInstance().isCrashlyticsCollectionEnabled = !BuildConfig.DEBUG
        }

        inAppUpdate = InAppUpdate(this)

        // Collect EEA consent first; the ads SDK is only initialised once we may request ads.
        AdsConsent.gather(this)

        setContent {
            val viewModel: VoiceSearchViewModel = viewModel()
            val state by viewModel.uiState.collectAsStateWithLifecycle()

            LaunchedEffect(state.settingsLoaded) {
                if (state.settingsLoaded) settingsLoaded = true
            }

            VoiceTheme(
                darkTheme = when (state.settings.themeMode) {
                    ThemeMode.SYSTEM -> isSystemInDarkTheme()
                    ThemeMode.LIGHT -> false
                    ThemeMode.DARK -> true
                },
                dynamicColor = state.settings.dynamicColor,
            ) {
                VoiceSearchScreen(viewModel = viewModel)
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
