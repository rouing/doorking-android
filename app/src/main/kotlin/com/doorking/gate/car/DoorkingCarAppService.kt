package com.doorking.gate.car

import android.content.Intent
import android.content.pm.ApplicationInfo
import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.SessionInfo
import androidx.car.app.validation.HostValidator
import com.doorking.gate.R

/**
 * Android Auto IoT service (`androidx.car.app.category.IOT`).
 *
 * Exposes resident Buzz Me In gate controls on the car head unit while reusing the existing
 * encrypted session in `DoorkingApp.container`.
 */
class DoorkingCarAppService : CarAppService() {

    override fun createHostValidator(): HostValidator {
        return if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        } else {
            HostValidator.Builder(applicationContext)
                .addAllowedHosts(R.array.car_hosts_allowlist)
                .build()
        }
    }

    override fun onCreateSession(sessionInfo: SessionInfo): Session = DoorkingCarSession()
}

class DoorkingCarSession : Session() {
    override fun onCreateScreen(intent: Intent): Screen = ResidentGateCarScreen(carContext)
}
