package com.nexal.app.ui.profile

import androidx.activity.compose.rememberLauncherForActivityResult
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
    val available: Boolean = false,
    val connected: Boolean = false,
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

    fun refresh() {
        viewModelScope.launch {
            val available = health.isAvailable()
            _state.update {
                it.copy(
                    available = available,
                    connected = available && runCatching { health.hasAllPermissions() }.getOrDefault(false),
                    lastSync = health.lastSync()
                )
            }
        }
    }

    fun onPermissionsResult(granted: Set<String>) {
        if (granted.containsAll(health.permissions)) {
            _state.update { it.copy(connected = true) }
            sync()
        } else {
            _state.update { it.copy(message = "Allow all four Health Connect permissions to sync.") }
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
                            message = "Synced: ${it.stepsToday} steps today, ${it.weightsImported} weigh-ins in, ${it.workoutsExported} workouts out."
                        )
                    },
                    onFailure = { state.copy(syncing = false, message = it.message ?: "Sync failed. Try again.") }
                )
            }
        }
    }
}

/** Profile card: connect and sync Health Connect (Premium). */
@Composable
fun HealthConnectCard(onUpgrade: () -> Unit, viewModel: HealthConnectViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val isPremium by viewModel.isPremium.collectAsStateWithLifecycle()
    val request = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) {
        viewModel.onPermissionsResult(it)
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
                    !state.available -> "Health Connect isn't available on this phone."
                    state.connected && state.lastSync > 0 ->
                        "Steps and weight in, workouts out. Last sync ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(state.lastSync))}."
                    else -> "Bring in steps and weigh-ins from your other apps, and send your Nexal workouts back."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            state.message?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
            if (state.available) {
                Spacer(Modifier.height(12.dp))
                FitButton(
                    text = when {
                        !isPremium -> "Unlock with Premium"
                        state.connected -> "Sync now"
                        else -> "Connect"
                    },
                    onClick = {
                        when {
                            !isPremium -> onUpgrade()
                            state.connected -> viewModel.sync()
                            else -> request.launch(viewModel.permissions)
                        }
                    },
                    loading = state.syncing,
                    variant = ButtonVariant.SECONDARY,
                    icon = Icons.Default.Sync,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}
