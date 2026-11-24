package com.doorking.gate.core.update

import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayInAppUpdateControllerTest {
    @Test
    fun downloadedUpdateAlwaysPromptsRestart() {
        val action = decideInAppUpdateAction(
            updateAvailability = UpdateAvailability.UPDATE_AVAILABLE,
            installStatus = InstallStatus.DOWNLOADED,
            flexibleAllowed = true,
            alreadyPromptedThisSession = true,
        )
        assertEquals(InAppUpdateAction.PROMPT_INSTALL_READY, action)
    }

    @Test
    fun availableFlexibleUpdateStartsFlowOncePerSession() {
        assertEquals(
            InAppUpdateAction.START_FLEXIBLE_FLOW,
            decideInAppUpdateAction(
                updateAvailability = UpdateAvailability.UPDATE_AVAILABLE,
                installStatus = InstallStatus.UNKNOWN,
                flexibleAllowed = true,
                alreadyPromptedThisSession = false,
            ),
        )
        assertEquals(
            InAppUpdateAction.NONE,
            decideInAppUpdateAction(
                updateAvailability = UpdateAvailability.UPDATE_AVAILABLE,
                installStatus = InstallStatus.UNKNOWN,
                flexibleAllowed = true,
                alreadyPromptedThisSession = true,
            ),
        )
    }

    @Test
    fun noUpdateOrDisallowedTypeIsNoOp() {
        assertEquals(
            InAppUpdateAction.NONE,
            decideInAppUpdateAction(
                updateAvailability = UpdateAvailability.UPDATE_NOT_AVAILABLE,
                installStatus = InstallStatus.UNKNOWN,
                flexibleAllowed = true,
                alreadyPromptedThisSession = false,
            ),
        )
        assertEquals(
            InAppUpdateAction.NONE,
            decideInAppUpdateAction(
                updateAvailability = UpdateAvailability.UPDATE_AVAILABLE,
                installStatus = InstallStatus.UNKNOWN,
                flexibleAllowed = false,
                alreadyPromptedThisSession = false,
            ),
        )
    }
}
