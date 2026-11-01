package com.kotlin.voice

import android.app.Activity
import android.content.Context
import android.util.Log
import com.google.android.gms.ads.MobileAds
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * EEA/UK consent gate for ads, backed by Google's User Messaging Platform.
 *
 * The ads SDK is not initialised — and no [com.google.android.gms.ads.AdView] is even created —
 * until UMP reports that we may request ads, so a user who still owes a consent decision is never
 * served one. Outside the EEA `canRequestAds()` is true immediately and nothing is shown.
 *
 * Process-scoped on purpose: consent survives Activity recreation (rotation), so the banner does
 * not have to re-run the whole flow on every configuration change.
 *
 * Requires a GDPR message published in the AdMob console under Privacy & messaging. Without one,
 * `canRequestAds()` stays false in the EEA and the banner simply never appears.
 *
 * Where a choice was asked for, Google's EU consent policy also requires a way back to it, and the
 * consent message itself tells people to look for one in the app. [privacyOptionsRequired] says
 * when Settings must offer it, and [showPrivacyOptions] opens the form.
 */
object AdsConsent {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val adsInitialized = AtomicBoolean(false)

    private val _canRequestAds = MutableStateFlow(false)

    /** True once consent (where required) is on file and the ads SDK has finished initialising. */
    val canRequestAds: StateFlow<Boolean> = _canRequestAds.asStateFlow()

    private val _privacyOptionsRequired = MutableStateFlow(false)

    /** True when this user must be able to revisit their consent choice (EEA, UK, Switzerland). */
    val privacyOptionsRequired: StateFlow<Boolean> = _privacyOptionsRequired.asStateFlow()

    fun gather(activity: Activity) {
        val consentInformation = UserMessagingPlatform.getConsentInformation(activity)

        // Consent already on file from an earlier session: don't make the banner wait on the network.
        if (consentInformation.canRequestAds()) initializeAds(activity)

        consentInformation.requestConsentInfoUpdate(
            activity,
            ConsentRequestParameters.Builder().build(),
            {
                UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { formError ->
                    if (formError != null) {
                        Log.w(TAG, "Consent form failed: ${formError.errorCode} ${formError.message}")
                    }
                    applyConsentState(activity, consentInformation)
                }
            },
            { requestError ->
                // Network or config failure — fall back to the consent state already cached on device.
                Log.w(TAG, "Consent info update failed: ${requestError.errorCode} ${requestError.message}")
                applyConsentState(activity, consentInformation)
            },
        )
    }

    /** Opens Google's form for reviewing or changing the consent choice. */
    fun showPrivacyOptions(activity: Activity) {
        UserMessagingPlatform.showPrivacyOptionsForm(activity) { formError ->
            if (formError != null) {
                Log.w(TAG, "Privacy options form failed: ${formError.errorCode} ${formError.message}")
            }
            // A choice made here can allow ads that were not allowed before.
            applyConsentState(activity, UserMessagingPlatform.getConsentInformation(activity))
        }
    }

    private fun applyConsentState(activity: Activity, consentInformation: ConsentInformation) {
        _privacyOptionsRequired.value = consentInformation.privacyOptionsRequirementStatus ==
            ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED
        if (consentInformation.canRequestAds()) initializeAds(activity)
    }

    private fun initializeAds(context: Context) {
        if (!adsInitialized.compareAndSet(false, true)) return
        val appContext = context.applicationContext
        // Off the main thread: MobileAds.initialize does disk I/O on the calling thread.
        scope.launch {
            MobileAds.initialize(appContext) { _canRequestAds.value = true }
        }
    }

    private const val TAG = "AdsConsent"
}
