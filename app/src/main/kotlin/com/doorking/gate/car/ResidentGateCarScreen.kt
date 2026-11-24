package com.doorking.gate.car

import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarIcon
import androidx.car.app.model.GridItem
import androidx.car.app.model.GridTemplate
import androidx.car.app.model.ItemList
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Template
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.doorking.gate.DoorkingApp
import com.doorking.gate.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Primary Android Auto screen showing one-tap resident gate tiles (`GridTemplate`).
 * Uses Car API 6–compatible template builders (`setTitle`, `setHeaderAction`, `ActionStrip`) so it
 * runs cleanly on every IoT-capable Android Auto head unit.
 */
class ResidentGateCarScreen(carContext: CarContext) : Screen(carContext) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var uiState: CarGateUiState = CarGateUiState.Loading

    init {
        lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    refresh()
                }

                override fun onDestroy(owner: LifecycleOwner) {
                    scope.cancel()
                }
            },
        )
    }

    override fun onGetTemplate(): Template {
        return when (val state = uiState) {
            CarGateUiState.Loading -> GridTemplate.Builder()
                .setTitle("Doorking Gate")
                .setHeaderAction(Action.APP_ICON)
                .setLoading(true)
                .build()

            CarGateUiState.SignedOut -> MessageTemplate.Builder(
                "Sign in to Doorking Gate on your phone first to open gates from Android Auto.",
            )
                .setTitle("Doorking Gate")
                .setHeaderAction(Action.APP_ICON)
                .setIcon(gateCarIcon(CarColor.PRIMARY))
                .addAction(
                    Action.Builder()
                        .setTitle("Check again")
                        .setOnClickListener { refresh() }
                        .build(),
                )
                .build()

            is CarGateUiState.NoBuzzableGates -> MessageTemplate.Builder(state.reason)
                .setTitle("Doorking Gate")
                .setHeaderAction(Action.APP_ICON)
                .setIcon(gateCarIcon(CarColor.DEFAULT))
                .addAction(
                    Action.Builder()
                        .setTitle("Refresh")
                        .setOnClickListener { refresh() }
                        .build(),
                )
                .build()

            is CarGateUiState.Error -> MessageTemplate.Builder(state.message)
                .setTitle("Doorking Gate")
                .setHeaderAction(Action.APP_ICON)
                .setIcon(gateCarIcon(CarColor.RED))
                .addAction(
                    Action.Builder()
                        .setTitle("Retry")
                        .setOnClickListener { refresh() }
                        .build(),
                )
                .build()

            is CarGateUiState.Ready -> {
                val listBuilder = ItemList.Builder()
                for (tile in state.relays) {
                    val isBuzzing = state.buzzingKey == tile.key
                    val secondary = if (state.multipleGates) {
                        "${tile.gateName} · ${tile.statusText}"
                    } else {
                        tile.statusText
                    }
                    val itemBuilder = GridItem.Builder()
                        .setTitle(tile.relayName)
                        .setText(secondary)
                    if (isBuzzing) {
                        itemBuilder.setLoading(true)
                    } else {
                        val tint = when {
                            tile.canBuzz -> CarColor.PRIMARY
                            tile.requirePasskey -> CarColor.YELLOW
                            else -> CarColor.DEFAULT
                        }
                        itemBuilder
                            .setImage(gateCarIcon(tint), GridItem.IMAGE_TYPE_ICON)
                            .setOnClickListener { onRelayTapped(tile) }
                    }
                    listBuilder.addItem(itemBuilder.build())
                }
                val refreshIcon = CarIcon.Builder(
                    IconCompat.createWithResource(carContext, R.drawable.ic_widget_refresh),
                ).build()
                val actionStrip = ActionStrip.Builder()
                    .addAction(
                        Action.Builder()
                            .setIcon(refreshIcon)
                            .setOnClickListener { refresh() }
                            .build(),
                    )
                    .build()
                GridTemplate.Builder()
                    .setTitle(state.title)
                    .setHeaderAction(Action.APP_ICON)
                    .setActionStrip(actionStrip)
                    .setSingleList(listBuilder.build())
                    .build()
            }
        }
    }

    private fun gateCarIcon(tint: CarColor): CarIcon {
        val icon = IconCompat.createWithResource(carContext, R.drawable.ic_widget_gate)
        return CarIcon.Builder(icon).setTint(tint).build()
    }

    private fun refresh() {
        val app = carContext.applicationContext as? DoorkingApp ?: return
        if (app.startupCrash != null) {
            uiState = CarGateUiState.Error("Open Doorking Gate on your phone to recover.")
            invalidate()
            return
        }
        if (uiState !is CarGateUiState.Ready) {
            uiState = CarGateUiState.Loading
            invalidate()
        }
        scope.launch {
            val next = withContext(Dispatchers.IO) {
                CarGateRepository.fetchState(
                    sessionStorage = app.container.sessionStorage,
                    api = app.container.api,
                )
            }
            uiState = next
            invalidate()
        }
    }

    private fun onRelayTapped(tile: CarRelayTile) {
        val current = uiState as? CarGateUiState.Ready ?: return
        if (current.buzzingKey != null) return

        if (tile.requirePasskey) {
            CarToast.makeText(
                carContext,
                "Passkey required — open from your phone",
                CarToast.LENGTH_LONG,
            ).show()
            return
        }
        if (!tile.canBuzz) {
            CarToast.makeText(
                carContext,
                "${tile.relayName}: ${tile.statusText}",
                CarToast.LENGTH_SHORT,
            ).show()
            refresh()
            return
        }

        val app = carContext.applicationContext as? DoorkingApp ?: return
        uiState = current.copy(buzzingKey = tile.key)
        invalidate()

        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                CarGateRepository.buzz(app.container.api, tile)
            }
            val toast = when (outcome) {
                is CarBuzzResult.Opened -> outcome.message
                CarBuzzResult.RequiresPhonePasskey -> "Passkey required — open from your phone"
                is CarBuzzResult.Failed -> outcome.message
            }
            CarToast.makeText(carContext, toast, CarToast.LENGTH_LONG).show()
            val refreshed = withContext(Dispatchers.IO) {
                CarGateRepository.fetchState(
                    sessionStorage = app.container.sessionStorage,
                    api = app.container.api,
                )
            }
            uiState = refreshed
            invalidate()
        }
    }
}
