package com.nexal.app.ui.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexal.app.data.repository.AiRepository
import com.nexal.app.data.repository.AuthRepository
import com.nexal.app.data.repository.NutritionRepository
import com.nexal.app.data.repository.ProfileRepository
import com.nexal.app.data.repository.WorkoutRepository
import com.nexal.app.domain.model.AuthState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class NavViewModel @Inject constructor(
    val paywallPrompt: com.nexal.app.ui.paywall.PaywallPrompt,
    val authRepository: AuthRepository,
    val profileRepository: ProfileRepository,
    private val workoutRepository: WorkoutRepository,
    private val nutritionRepository: NutritionRepository,
    private val aiRepository: AiRepository,
    private val rewardRepository: com.nexal.app.data.repository.RewardRepository,
    private val healthConnect: com.nexal.app.data.repository.HealthConnectRepository
) : ViewModel() {

    init {
        // Keep the free AI allowance current for whoever is signed in.
        viewModelScope.launch {
            authRepository.authState
                .map { (it as? AuthState.Authenticated)?.let { auth -> auth.userId to auth.isPremium } }
                .distinctUntilChanged()
                .collect { signedIn ->
                    if (signedIn != null) aiRepository.refreshFreeCredits(isPremium = signedIn.second)
                    rewardRepository.premium = signedIn?.second == true
                    // Premium + connected: a quiet Health Connect sync on every app open.
                    if (signedIn?.second == true) {
                        runCatching { if (healthConnect.isAvailable() && healthConnect.hasAllPermissions()) healthConnect.sync() }
                    }
                }
        }
        // After login / DB wipe, restore profile + plans so AI screens aren't empty.
        viewModelScope.launch {
            authRepository.authState
                .map { it is AuthState.Authenticated }
                .distinctUntilChanged()
                .collect { authenticated ->
                    if (authenticated) {
                        profileRepository.syncFromServer()
                        workoutRepository.syncFromServer()
                        nutritionRepository.syncFromServer()
                    }
                }
        }
    }
}
