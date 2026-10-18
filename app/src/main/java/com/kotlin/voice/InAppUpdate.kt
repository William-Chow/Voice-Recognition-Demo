package com.kotlin.voice

import android.app.Activity
import android.graphics.Color
import android.util.Log
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import com.google.android.material.snackbar.Snackbar
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.InstallState
import com.google.android.play.core.install.InstallStateUpdatedListener
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability

class InAppUpdate(private val activity: ComponentActivity) : InstallStateUpdatedListener {

    private val appUpdateManager: AppUpdateManager = AppUpdateManagerFactory.create(activity)

    // Only decides whether a *cancelled* flow is worth re-offering. Resuming an interrupted update
    // is decided from AppUpdateInfo instead, because that survives process death and this does not.
    private var lastRequestedType = AppUpdateType.FLEXIBLE

    // Registered before the activity reaches STARTED (constructed in onCreate). Replaces the
    // deprecated startUpdateFlowForResult(info, type, activity, requestCode) + onActivityResult path.
    private val updateLauncher: ActivityResultLauncher<IntentSenderRequest> =
        activity.registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            when (result.resultCode) {
                Activity.RESULT_OK -> Unit
                // Backing out of a flexible update is a fair choice, so don't nag. An immediate
                // update was deliberate on our side, so offer it again rather than dropping it.
                Activity.RESULT_CANCELED ->
                    if (lastRequestedType == AppUpdateType.IMMEDIATE) {
                        showSnackbar(R.string.update_cancelled, R.string.update_retry, Snackbar.LENGTH_LONG) {
                            checkForUpdate()
                        }
                    }
                // RESULT_IN_APP_UPDATE_FAILED (or anything else): it broke, and until now the user
                // saw nothing at all — only a Log.e that never leaves the device.
                else -> {
                    Log.w(TAG, "Update flow failed, result code ${result.resultCode}")
                    showSnackbar(R.string.update_failed, R.string.update_retry, Snackbar.LENGTH_LONG) {
                        checkForUpdate()
                    }
                }
            }
        }

    init {
        checkForUpdate()
        appUpdateManager.registerListener(this)
    }

    private fun checkForUpdate() {
        appUpdateManager.appUpdateInfo
            .addOnSuccessListener { info ->
                if (info.updateAvailability() != UpdateAvailability.UPDATE_AVAILABLE) {
                    return@addOnSuccessListener
                }
                val type = updateFlowFor(
                    priority = info.updatePriority(),
                    stalenessDays = info.clientVersionStalenessDays(),
                    immediateAllowed = info.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE),
                    flexibleAllowed = info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE),
                )
                if (type != null) startUpdate(info, type)
            }
            .addOnFailureListener { error ->
                // Sideloaded build, no Play Store, no network — nothing the user can act on.
                Log.w(TAG, "Update check failed", error)
            }
    }

    private fun startUpdate(info: AppUpdateInfo, type: Int) {
        lastRequestedType = type
        appUpdateManager.startUpdateFlowForResult(
            info,
            updateLauncher,
            AppUpdateOptions.newBuilder(type).build(),
        )
    }

    fun onResume() {
        appUpdateManager.appUpdateInfo
            .addOnSuccessListener { info ->
                // Decided from the real state Play reports rather than an in-memory flag: after
                // process death mid-update, this process remembers nothing about which flow ran.
                val action = resumeActionFor(
                    updateAvailability = info.updateAvailability(),
                    installStatus = info.installStatus(),
                    priority = info.updatePriority(),
                    stalenessDays = info.clientVersionStalenessDays(),
                )
                when (action) {
                    ResumeAction.RESUME_IMMEDIATE -> startUpdate(info, AppUpdateType.IMMEDIATE)
                    ResumeAction.PROMPT_RESTART -> flexibleUpdateDownloadCompleted()
                    ResumeAction.NONE -> Unit
                }
            }
            .addOnFailureListener { error ->
                Log.w(TAG, "Update info check on resume failed", error)
            }
    }

    private fun flexibleUpdateDownloadCompleted() {
        showSnackbar(R.string.update_downloaded, R.string.update_restart, Snackbar.LENGTH_INDEFINITE) {
            appUpdateManager.completeUpdate()
                .addOnFailureListener { error ->
                    Log.w(TAG, "completeUpdate failed", error)
                    showSnackbar(R.string.update_failed, R.string.update_retry, Snackbar.LENGTH_LONG) {
                        checkForUpdate()
                    }
                }
        }
    }

    private fun showSnackbar(
        @StringRes message: Int,
        @StringRes actionLabel: Int,
        duration: Int,
        action: () -> Unit,
    ) {
        // These arrive from asynchronous Play callbacks, which can outlive the activity.
        if (activity.isFinishing || activity.isDestroyed) return
        val rootView: View = activity.findViewById(android.R.id.content) ?: return
        Snackbar.make(rootView, activity.getString(message), duration).apply {
            setAction(activity.getString(actionLabel)) { action() }
            setActionTextColor(Color.WHITE)
            show()
        }
    }

    fun onDestroy() {
        appUpdateManager.unregisterListener(this)
    }

    override fun onStateUpdate(state: InstallState) {
        when (state.installStatus()) {
            InstallStatus.DOWNLOADED -> flexibleUpdateDownloadCompleted()
            // Previously silent: a download that died left the user waiting for a restart prompt
            // that was never coming.
            InstallStatus.FAILED -> {
                Log.w(TAG, "Update install failed, error code ${state.installErrorCode()}")
                showSnackbar(R.string.update_failed, R.string.update_retry, Snackbar.LENGTH_LONG) {
                    checkForUpdate()
                }
            }
            else -> Unit
        }
    }

    private companion object {
        const val TAG = "InAppUpdate"
    }
}

/**
 * The release policy: how stale a client must be, at each Play Console update priority, before the
 * app interrupts — and with which flow. Null means leave the user alone.
 *
 * Thresholds are the ones this app has always shipped. A staleness Play does not report counts as
 * "not stale", so a freshly published release only interrupts at priority 5. When the store
 * disallows the escalated flow the gentler one still runs, which is what the original chain of
 * if/else did by falling through.
 *
 * Pure and internal so the policy can be tested without a Play Store; [InAppUpdate] only supplies
 * the inputs and acts on the answer.
 */
internal fun updateFlowFor(
    priority: Int,
    stalenessDays: Int?,
    immediateAllowed: Boolean,
    flexibleAllowed: Boolean,
): Int? {
    val staleness = stalenessDays ?: NOT_STALE
    val immediate = if (immediateAllowed) AppUpdateType.IMMEDIATE else null
    val flexible = if (flexibleAllowed) AppUpdateType.FLEXIBLE else null

    fun escalate(immediateAfter: Int, flexibleAfter: Int): Int? = when {
        staleness >= immediateAfter -> immediate ?: flexible
        staleness >= flexibleAfter -> flexible
        else -> null
    }

    return when (priority) {
        5 -> immediate
        4 -> escalate(immediateAfter = 5, flexibleAfter = 3)
        3 -> escalate(immediateAfter = 30, flexibleAfter = 15)
        2 -> escalate(immediateAfter = 90, flexibleAfter = 30)
        1 -> flexible
        else -> null // Priority 0, or anything Play adds later: never interrupt.
    }
}

private const val NOT_STALE = -1

/** What returning to the foreground should do about an update this app already started. */
internal enum class ResumeAction { RESUME_IMMEDIATE, PROMPT_RESTART, NONE }

/**
 * Play reports DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS for *any* update the app started, flexible
 * included, from PENDING all the way to DOWNLOADED — Google's own FakeAppUpdateManager models it
 * that way — and never says which flow it was. Treating that state as "an IMMEDIATE update was
 * interrupted" turned every background flexible download into the full-screen flow, and left the
 * DOWNLOADED restart prompt unreachable.
 *
 * So the flow is re-derived from [updateFlowFor]: the same release inputs give the same answer,
 * and they survive process death. Only an update the policy would force is resumed as IMMEDIATE.
 * The allowed-type flags are not consulted, because Play may stop offering them once an update is
 * under way. A flexible update the policy has since escalated (it went stale enough while
 * downloading) is resumed as IMMEDIATE too, exactly as a fresh check would have chosen.
 */
internal fun resumeActionFor(
    updateAvailability: Int,
    installStatus: Int,
    priority: Int,
    stalenessDays: Int?,
): ResumeAction {
    val inProgress = updateAvailability == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS
    val forced = updateFlowFor(priority, stalenessDays, immediateAllowed = true, flexibleAllowed = true) ==
        AppUpdateType.IMMEDIATE
    return when {
        inProgress && forced -> ResumeAction.RESUME_IMMEDIATE
        installStatus == InstallStatus.DOWNLOADED -> ResumeAction.PROMPT_RESTART
        else -> ResumeAction.NONE
    }
}
