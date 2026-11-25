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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Primary Android Auto screen showing one-tap gate relay tiles (`GridTemplate`) with lock icons
 * and automatic live status polling (with local cooldown countdown, error backoff, and automatic
 * pause when signed out or when no buzzable gates exist).
 */
class ResidentGateCarScreen(carContext: CarContext) : Screen(carContext) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var uiState: CarGateUiState = CarGateUiState.Loading
    private var pollJob: Job? = null
    private var consecutiveErrors: Int = 0

    init {
        lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    refresh()
                }

                override fun onStop(owner: LifecycleOwner) {
                    pollJob?.cancel()
                    pollJob = null
                }

                override fun onDestroy(owner: LifecycleOwner) {
                    pollJob?.cancel()
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
                .setIcon(relayLockIcon(CarColor.PRIMARY))
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
                .setIcon(relayLockIcon(CarColor.DEFAULT))
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
                .setIcon(relayLockIcon(CarColor.RED))
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
                            tile.requirePasskey || tile.isBusy -> CarColor.YELLOW
                            else -> CarColor.DEFAULT
                        }
                        itemBuilder
                            .setImage(relayLockIcon(tint), GridItem.IMAGE_TYPE_ICON)
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

    private fun relayLockIcon(tint: CarColor): CarIcon {
        val icon = IconCompat.createWithResource(carContext, R.drawable.ic_widget_lock)
        return CarIcon.Builder(icon).setTint(tint).build()
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope.launch {
            var idleTicks = 0
            var localCooldownTicks = 0
            while (isActive) {
                when (val cur = uiState) {
                    CarGateUiState.SignedOut,
                    is CarGateUiState.NoBuzzableGates,
                    -> {
                        // Stop automatic polling until user explicitly taps Refresh / Check again.
                        return@launch
                    }

                    CarGateUiState.Loading -> {
                        delay(COOLDOWN_TICK_MS)
                    }

                    is CarGateUiState.Error -> {
                        val backoffMs = (ERROR_BACKOFF_BASE_MS shl consecutiveErrors.coerceAtMost(3))
                            .coerceAtMost(ERROR_BACKOFF_MAX_MS)
                        delay(backoffMs)
                        if (uiState is CarGateUiState.Error) {
                            fetchAndApply(silent = true)
                        }
                    }

                    is CarGateUiState.Ready -> {
                        val hasBusy = cur.buzzingKey != null || cur.relays.any { it.isBusy }
                        val hasCooldown = !hasBusy && cur.relays.any { it.cooldownSeconds > 0 }
                        when {
                            hasBusy -> {
                                idleTicks = 0
                                localCooldownTicks = 0
                                delay(FAST_POLL_MS)
                                val latest = uiState
                                if (latest is CarGateUiState.Ready && latest.buzzingKey == null) {
                                    refreshStatusesAndApply(latest)
                                }
                            }

                            hasCooldown -> {
                                idleTicks = 0
                                delay(COOLDOWN_TICK_MS)
                                val latest = uiState
                                if (latest is CarGateUiState.Ready && latest.buzzingKey == null) {
                                    val ticked = CarGateRepository.tickLocalCooldown(latest, 1)
                                    if (ticked != latest) {
                                        uiState = ticked
                                        invalidate()
                                    }
                                    localCooldownTicks++
                                    val cooldownFinished = ticked.relays.none { it.cooldownSeconds > 0 }
                                    if (cooldownFinished || localCooldownTicks >= COOLDOWN_RESYNC_TICKS) {
                                        localCooldownTicks = 0
                                        refreshStatusesAndApply(ticked)
                                    }
                                }
                            }

                            else -> {
                                localCooldownTicks = 0
                                delay(IDLE_POLL_MS)
                                val latest = uiState
                                if (latest is CarGateUiState.Ready && latest.buzzingKey == null) {
                                    idleTicks++
                                    if (idleTicks >= FULL_REFRESH_EVERY_IDLE_TICKS) {
                                        idleTicks = 0
                                        fetchAndApply(silent = true)
                                    } else {
                                        refreshStatusesAndApply(latest)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private suspend fun refreshStatusesAndApply(current: CarGateUiState.Ready) {
        val app = carContext.applicationContext as? DoorkingApp ?: return
        if (app.startupCrash != null) return
        val next = withContext(Dispatchers.IO) {
            CarGateRepository.refreshStatusesOnly(
                api = app.container.api,
                current = current,
            )
        }
        val latest = uiState
        if (latest is CarGateUiState.Ready && latest.buzzingKey != null) return
        if (next != uiState) {
            uiState = next
            invalidate()
        }
    }

    private fun refresh() {
        val app = carContext.applicationContext as? DoorkingApp ?: return
        if (app.startupCrash != null) {
            uiState = CarGateUiState.Error("Open Doorking Gate on your phone to recover.")
            invalidate()
            return
        }
        consecutiveErrors = 0
        if (uiState !is CarGateUiState.Ready) {
            uiState = CarGateUiState.Loading
            invalidate()
        }
        scope.launch {
            fetchAndApply(silent = false)
            if (uiState is CarGateUiState.Ready || uiState is CarGateUiState.Error) {
                startPolling()
            } else {
                pollJob?.cancel()
                pollJob = null
            }
        }
    }

    private suspend fun fetchAndApply(silent: Boolean) {
        val app = carContext.applicationContext as? DoorkingApp ?: return
        if (app.startupCrash != null) return
        val next = withContext(Dispatchers.IO) {
            CarGateRepository.fetchState(
                sessionStorage = app.container.sessionStorage,
                api = app.container.api,
            )
        }
        consecutiveErrors = if (next is CarGateUiState.Error) {
            (consecutiveErrors + 1).coerceAtMost(4)
        } else {
            0
        }
        if (!silent || next != uiState) {
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
                CarGateRepository.refreshStatusesOnly(
                    api = app.container.api,
                    current = current.copy(buzzingKey = null),
                )
            }
            uiState = refreshed
            invalidate()
            startPolling()
        }
    }

    private companion object {
        const val COOLDOWN_TICK_MS = 1_000L
        const val COOLDOWN_RESYNC_TICKS = 10
        const val FAST_POLL_MS = 2_000L
        const val IDLE_POLL_MS = 10_000L
        const val FULL_REFRESH_EVERY_IDLE_TICKS = 6
        const val ERROR_BACKOFF_BASE_MS = 10_000L
        const val ERROR_BACKOFF_MAX_MS = 60_000L
    }
}
