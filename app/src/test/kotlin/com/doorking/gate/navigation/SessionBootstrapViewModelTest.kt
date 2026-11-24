package com.doorking.gate.navigation

import com.doorking.gate.core.network.GateInfoDto
import com.doorking.gate.core.network.SessionResponse
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionBootstrapViewModelTest {

    @Test
    fun routesLoggedOutToSignIn() {
        val route = SessionBootstrapViewModel.resolveStartRoute(
            session = SessionResponse(loggedIn = false),
            lastGatePhone = null,
        )
        assertEquals(AppRoute.SignIn, route)
    }

    @Test
    fun routesMustChangePasswordFirst() {
        val route = SessionBootstrapViewModel.resolveStartRoute(
            session = SessionResponse(
                loggedIn = true,
                user = "alice",
                mustChangePassword = true,
                gate = GateInfoDto(phoneNumber = "2145550100", role = "admin"),
            ),
            lastGatePhone = "2145550100",
        )
        assertEquals(AppRoute.ForcedPasswordChange, route)
    }

    @Test
    fun routesResidentOnlyUserToResidentPortal() {
        val route = SessionBootstrapViewModel.resolveStartRoute(
            session = SessionResponse(
                loggedIn = true,
                user = "res_101",
                isResidentOnly = true,
                gate = GateInfoDto(phoneNumber = "2145550100", role = "resident"),
            ),
            lastGatePhone = null,
        )
        assertEquals(AppRoute.ResidentPortal("2145550100"), route)
    }

    @Test
    fun routesStaffWithOpenGateToStaffGate() {
        val route = SessionBootstrapViewModel.resolveStartRoute(
            session = SessionResponse(
                loggedIn = true,
                user = "alice",
                isResidentOnly = false,
                gate = GateInfoDto(phoneNumber = "2145550100", siteName = "Main", role = "admin"),
            ),
            lastGatePhone = null,
        )
        assertEquals(AppRoute.StaffGate("2145550100"), route)
    }

    @Test
    fun routesStaffWithoutOpenGateToGatePicker() {
        val route = SessionBootstrapViewModel.resolveStartRoute(
            session = SessionResponse(loggedIn = true, user = "alice", gate = null),
            lastGatePhone = null,
        )
        assertEquals(AppRoute.GatePicker, route)
    }
}
