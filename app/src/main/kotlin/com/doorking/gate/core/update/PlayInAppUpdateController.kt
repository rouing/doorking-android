package com.doorking.gate.core.update

import android.content.Context
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.InstallStateUpdatedListener
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class InAppUpdateAction {
    NONE,
    START_FLEXIBLE_FLOW,
    PROMPT_INSTALL_READY,
}

internal fun decideInAppUpdateAction(
    updateAvailability: Int,
    installStatus: Int,
    flexibleAllowed: Boolean,
    alreadyPromptedThisSession: Boolean,
): InAppUpdateAction {
    if (installStatus == InstallStatus.DOWNLOADED) {
        return InAppUpdateAction.PROMPT_INSTALL_READY
    }
    if (
        !alreadyPromptedThisSession &&
        updateAvailability == UpdateAvailability.UPDATE_AVAILABLE &&
        flexibleAllowed
    ) {
        return InAppUpdateAction.START_FLEXIBLE_FLOW
    }
    return InAppUpdateAction.NONE
}

/**
 * Coordinates non-blocking Google Play Flexible In-App Updates.
 *
 * Uses Flexible mode intentionally so users arriving at a gate are never blocked by a modal
 * download screen. When Play finishes downloading an update in the background, [updateReadyToInstall]
 * flips to `true` so the UI can show a restart prompt. Safe on sideloaded / non-Play builds
 * (Play API failures are logged and ignored).
 */
class PlayInAppUpdateController(
    context: Context,
    private val launcher: ActivityResultLauncher<IntentSenderRequest>,
    private val appUpdateManager: AppUpdateManager = AppUpdateManagerFactory.create(context.applicationContext),
) {
    private val _updateReadyToInstall = MutableStateFlow(false)
    val updateReadyToInstall: StateFlow<Boolean> = _updateReadyToInstall.asStateFlow()

    private var promptedThisSession = false
    private var listenerRegistered = false

    private val installStateListener = InstallStateUpdatedListener { state ->
        when (state.installStatus()) {
            InstallStatus.DOWNLOADED -> _updateReadyToInstall.value = true
            InstallStatus.INSTALLED,
            InstallStatus.CANCELED,
            InstallStatus.FAILED -> _updateReadyToInstall.value = false
            else -> Unit
        }
    }

    fun onStart() {
        if (!listenerRegistered) {
            runCatching {
                appUpdateManager.registerListener(installStateListener)
                listenerRegistered = true
            }.onFailure {
                Log.d(TAG, "Play in-app update listener unavailable: ${it.message}")
            }
        }
        checkForUpdate(allowStartFlow = true)
    }

    fun onResume() {
        // Re-check whether a background download finished while the activity was paused.
        checkForUpdate(allowStartFlow = false)
    }

    fun onStop() {
        if (listenerRegistered) {
            runCatching { appUpdateManager.unregisterListener(installStateListener) }
            listenerRegistered = false
        }
    }

    fun completeUpdate() {
        _updateReadyToInstall.value = false
        runCatching { appUpdateManager.completeUpdate() }
            .onFailure { Log.w(TAG, "completeUpdate failed: ${it.message}") }
    }

    fun dismissReadyBanner() {
        _updateReadyToInstall.value = false
    }

    private fun checkForUpdate(allowStartFlow: Boolean) {
        runCatching {
            appUpdateManager.appUpdateInfo
                .addOnSuccessListener { info -> handleUpdateInfo(info, allowStartFlow) }
                .addOnFailureListener { err ->
                    // Normal on sideloaded / debug / non-Play devices.
                    Log.d(TAG, "Play in-app update check skipped: ${err.message}")
                }
        }.onFailure { err ->
            Log.d(TAG, "Play in-app update check failed: ${err.message}")
        }
    }

    private fun handleUpdateInfo(info: AppUpdateInfo, allowStartFlow: Boolean) {
        when (
            decideInAppUpdateAction(
                updateAvailability = info.updateAvailability(),
                installStatus = info.installStatus(),
                flexibleAllowed = info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE),
                alreadyPromptedThisSession = promptedThisSession || !allowStartFlow,
            )
        ) {
            InAppUpdateAction.PROMPT_INSTALL_READY -> {
                _updateReadyToInstall.value = true
            }
            InAppUpdateAction.START_FLEXIBLE_FLOW -> {
                promptedThisSession = true
                runCatching {
                    appUpdateManager.startUpdateFlowForResult(
                        info,
                        launcher,
                        AppUpdateOptions.newBuilder(AppUpdateType.FLEXIBLE).build(),
                    )
                }.onFailure { err ->
                    Log.d(TAG, "startUpdateFlowForResult failed: ${err.message}")
                }
            }
            InAppUpdateAction.NONE -> Unit
        }
    }

    private companion object {
        const val TAG = "PlayInAppUpdate"
    }
}
