package com.nexal.app.ui.profile

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.nexal.app.data.repository.HealthStatus
import com.nexal.app.data.repository.HealthSyncResult
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.PermissionController
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.nexal.app.data.repository.AuthRepository
import com.nexal.app.data.repository.HealthConnectRepository
import com.nexal.app.domain.model.AuthState
import com.nexal.app.ui.components.FitButton
import com.nexal.app.ui.components.FitCard
import com.nexal.app.ui.components.ButtonVariant
import com.nexal.app.ui.components.PremiumBadge
import com.nexal.app.ui.theme.limeEdge
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject

data class HealthConnectUiState(
    val status: HealthStatus = HealthStatus.UNSUPPORTED,
    /** Nexal has at least one Health Connect permission. */
    val connected: Boolean = false,
    /** Some, but not all, of the permissions are allowed. */
    val partial: Boolean = false,
    /** The permission sheet came back with nothing; it may not show again, so offer settings. */
    val needsSettings: Boolean = false,
    val syncing: Boolean = false,
    val message: String? = null,
    val lastSync: Long = 0
)

@HiltViewModel
class HealthConnectViewModel @Inject constructor(
    private val health: HealthConnectRepository,
    authRepository: AuthRepository
) : ViewModel() {
    val isPremium: StateFlow<Boolean> = authRepository.authState
        .map { (it as? AuthState.Authenticated)?.isPremium == true }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _state = MutableStateFlow(HealthConnectUiState())
    val state: StateFlow<HealthConnectUiState> = _state.asStateFlow()

    val permissions: Set<String> get() = health.permissions

    init { refresh() }

    /** Re-read status and permissions, e.g. after coming back from Health Connect. */
    fun refresh() {
        viewModelScope.launch {
            val status = health.status()
            val granted = if (status == HealthStatus.AVAILABLE) health.grantedPermissions() else emptySet()
            val wasConnected = _state.value.connected
            _state.update {
                it.copy(
                    status = status,
                    connected = granted.isNotEmpty(),
                    partial = granted.isNotEmpty() && !granted.containsAll(health.permissions),
                    needsSettings = if (granted.isNotEmpty()) false else it.needsSettings,
                    lastSync = health.lastSync()
                )
            }
            // Just allowed in Health Connect's own screen: sync straight away.
            if (!wasConnected && granted.isNotEmpty() && isPremium.value) sync()
        }
    }

    fun onPermissionsResult(granted: Set<String>) {
        val allowed = granted.intersect(health.permissions)
        if (allowed.isNotEmpty()) {
            _state.update {
                it.copy(
                    connected = true,
                    partial = !allowed.containsAll(health.permissions),
                    needsSettings = false
                )
            }
            sync()
        } else {
            _state.update {
                it.copy(
                    needsSettings = true,
                    message = "Nexal wasn't given access. Open Health Connect and allow Nexal under App permissions."
                )
            }
        }
    }

    fun sync() {
        if (_state.value.syncing) return
        _state.update { it.copy(syncing = true, message = null) }
        viewModelScope.launch {
            val result = runCatching { health.sync() }
            _state.update { state ->
                result.fold(
                    onSuccess = {
                        state.copy(
                            syncing = false,
                            lastSync = health.lastSync(),
                            message = syncSummary(it)
                        )
                    },
                    onFailure = {
                        android.util.Log.w("HealthConnect", "Sync failed", it)
                        state.copy(
                            syncing = false,
                            message = when (it) {
                                is SecurityException -> "Health Connect blocked part of the sync. Check Nexal's permissions in Health Connect."
                                is IllegalStateException -> it.message
                                else -> "Sync failed. Try again in a moment."
                            }
                        )
                    }
                )
            }
        }
    }

    private fun syncSummary(r: HealthSyncResult): String = buildList {
        r.stepsToday?.let { add("${count(it, "step")} today") }
        add("${count(r.weightsImported.toLong(), "weigh-in")} in")
        if (r.weightsExported > 0) add("${count(r.weightsExported.toLong(), "weigh-in")} out")
        add("${count(r.workoutsExported.toLong(), "workout")} out")
    }.joinToString(", ", prefix = "Synced: ", postfix = ".")
}

private fun count(n: Long, word: String) = "$n $word" + if (n == 1L) "" else "s"

/** Opens Nexal's page in Health Connect, where the user can allow or remove permissions. */
private fun openHealthConnectSettings(context: Context) {
    val intents = buildList {
        // Apps can't open their own page in Health Connect directly (that needs a
        // system permission), so open its home, where App permissions lists Nexal.
        if (Build.VERSION.SDK_INT >= 34) add(Intent("android.health.connect.action.HEALTH_HOME_SETTINGS"))
        add(Intent("androidx.health.ACTION_HEALTH_CONNECT_SETTINGS"))
    }
    for (intent in intents) {
        val opened = runCatching {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.isSuccess
        if (opened) return
    }
}

/** Sends the user to Google Play to install or update Health Connect (Android 13 and below). */
private fun installHealthConnect(context: Context) {
    val pkg = HealthConnectRepository.PROVIDER
    val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg&url=healthconnect%3A%2F%2Fonboarding"))
        .setPackage("com.android.vending")
        .putExtra("overlay", true)
        .putExtra("callerId", context.packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val web = Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$pkg"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (runCatching { context.startActivity(market) }.isFailure) runCatching { context.startActivity(web) }
}

/** Profile card: connect and sync Health Connect (Premium). */
@Composable
fun HealthConnectCard(
    onUpgrade: () -> Unit,
    viewModel: HealthConnectViewModel = hiltViewModel(),
    /** Preview only: show the Premium version without a real subscription. */
    forcePremium: Boolean = false
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val premiumState by viewModel.isPremium.collectAsStateWithLifecycle()
    val isPremium = premiumState || forcePremium
    val context = LocalContext.current
    val request = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) {
        viewModel.onPermissionsResult(it)
    }

    // Coming back from Health Connect or Play: pick up what changed.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    FitCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.FavoriteBorder, null, tint = limeEdge(), modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text("Health Connect", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                if (!isPremium) PremiumBadge()
            }
            Spacer(Modifier.height(6.dp))
            Text(
                when {
                    state.status == HealthStatus.UNSUPPORTED -> "Health Connect isn't supported on this phone."
                    state.status == HealthStatus.NEEDS_INSTALL -> "Install or update Health Connect from Google Play to sync your steps, weight and workouts."
                    state.connected && state.lastSync > 0 ->
                        "Steps and weight in, workouts out. Last sync ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(state.lastSync))}."
                    else -> "Bring in steps and weigh-ins from your other apps, and send your Nexal workouts back."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (state.connected && state.partial) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "Some permissions are off, so only part of your data syncs.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            state.message?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
            when {
                state.status == HealthStatus.UNSUPPORTED -> Unit
                !isPremium -> {
                    Spacer(Modifier.height(12.dp))
                    FitButton(
                        text = "Unlock with Premium",
                        onClick = onUpgrade,
                        variant = ButtonVariant.SECONDARY,
                        icon = Icons.Default.Sync,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                state.status == HealthStatus.NEEDS_INSTALL -> {
                    Spacer(Modifier.height(12.dp))
                    FitButton(
                        text = "Get Health Connect",
                        onClick = { installHealthConnect(context) },
                        variant = ButtonVariant.SECONDARY,
                        icon = Icons.Default.Sync,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                else -> {
                    Spacer(Modifier.height(12.dp))
                    FitButton(
                        text = when {
                            state.connected -> "Sync now"
                            state.needsSettings -> "Open Health Connect"
                            else -> "Connect"
                        },
                        onClick = {
                            when {
                                state.connected -> viewModel.sync()
                                state.needsSettings -> openHealthConnectSettings(context)
                                else -> request.launch(viewModel.permissions)
                            }
                        },
                        loading = state.syncing,
                        variant = ButtonVariant.SECONDARY,
                        icon = Icons.Default.Sync,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (state.connected) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                            if (state.partial) {
                                TextButton(onClick = { request.launch(viewModel.permissions) }) { Text("Allow all data") }
                            }
                            TextButton(onClick = { openHealthConnectSettings(context) }) { Text("Manage in Health Connect") }
                        }
                    }
                }
            }
        }
    }
}
