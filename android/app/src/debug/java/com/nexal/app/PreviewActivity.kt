package com.nexal.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.lifecycle.lifecycleScope
import com.nexal.app.data.repository.FastingRepository
import com.nexal.app.data.repository.NutritionRepository
import com.nexal.app.data.repository.ProfileRepository
import com.nexal.app.data.repository.RewardRepository
import com.nexal.app.data.repository.RewardType
import com.nexal.app.data.repository.WorkoutRepository
import com.nexal.app.domain.model.ActivityLevel
import com.nexal.app.domain.model.CardioLogEntry
import com.nexal.app.domain.model.Exercise
import com.nexal.app.domain.model.ExerciseLog
import com.nexal.app.domain.model.FitnessGoal
import com.nexal.app.domain.model.FoodItem
import com.nexal.app.domain.model.FoodLogEntry
import com.nexal.app.domain.model.FoodSource
import com.nexal.app.domain.model.MacroNutrients
import com.nexal.app.domain.model.Meal
import com.nexal.app.domain.model.MealPlan
import com.nexal.app.domain.model.MealSlot
import com.nexal.app.domain.model.SetLog
import com.nexal.app.domain.model.UserProfile
import com.nexal.app.domain.model.WaterLogEntry
import com.nexal.app.domain.model.WeightEntry
import com.nexal.app.domain.model.WorkoutDay
import com.nexal.app.domain.model.WorkoutLog
import com.nexal.app.domain.model.WorkoutPlan
import com.nexal.app.ui.components.PhotoBackdrop
import com.nexal.app.ui.dashboard.DashboardScreen
import com.nexal.app.ui.fasting.FastingScreen
import com.nexal.app.ui.meals.MealsScreen
import com.nexal.app.ui.navigation.MainScaffold
import com.nexal.app.ui.nutrition.NutritionScreen
import com.nexal.app.ui.onboarding.OnboardingScreen
import com.nexal.app.data.repository.PlanType
import com.nexal.app.ui.paywall.PaywallContent
import com.nexal.app.ui.paywall.PaywallPrompt
import com.nexal.app.ui.paywall.PaywallScreen
import com.nexal.app.ui.subscription.SubscriptionUiState
import com.nexal.app.ui.profile.ProfileScreen
import com.nexal.app.ui.progress.ProgressScreen
import com.nexal.app.ui.rewards.RewardOverlay
import com.nexal.app.ui.scanner.ScannerScreen
import com.nexal.app.ui.subscription.SubscriptionScreen
import com.nexal.app.ui.theme.NexalTheme
import com.nexal.app.ui.workouts.CustomWorkoutsScreen
import com.nexal.app.ui.workouts.WorkoutLogScreen
import com.nexal.app.ui.workouts.WorkoutsScreen
import com.nexal.app.util.todayFormatted
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp

/**
 * Debug builds only: opens one real screen with sample local data, so the design
 * can be reviewed without an account. With nobody signed in, the repositories
 * write to the local database and skip Supabase.
 *
 * adb shell am start -n com.nexal.app.dev/com.nexal.app.PreviewActivity --es screen home
 */
@AndroidEntryPoint
class PreviewActivity : ComponentActivity() {
    @Inject lateinit var profiles: ProfileRepository
    @Inject lateinit var workouts: WorkoutRepository
    @Inject lateinit var nutrition: NutritionRepository
    @Inject lateinit var fasting: FastingRepository
    @Inject lateinit var rewards: RewardRepository
    @Inject lateinit var paywallPrompt: PaywallPrompt

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val screen = intent.getStringExtra("screen") ?: "home"

        lifecycleScope.launch {
            seedSampleData(planStartedDaysAgo = if (screen == "workouts_week1") 2 else 10)
            when (screen) {
                "after_setup" -> paywallPrompt.queue()
                "fasting" -> fasting.end()
                "fasting_active" -> fasting.start("16_8", System.currentTimeMillis() - 9L * 3_600_000L - 1_260_000L)
                "fasting_done" -> fasting.start("16_8", System.currentTimeMillis() - 16L * 3_600_000L - 60_000L)
                "reward" -> rewards.award(RewardType.WORKOUT, dedupeKey = System.nanoTime().toString())
            }
            setContent {
                NexalTheme {
                    when (screen) {
                        // The real app shell, with working navigation and its own reward overlay.
                        "app" -> MainScaffold(isPremium = true)
                        "free" -> MainScaffold(isPremium = false)
                        "after_setup" -> MainScaffold(isPremium = false, paywallPrompt = paywallPrompt)
                        else -> {
                            SinglePage(screen)
                            RewardOverlay()
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun SinglePage(screen: String) {
        when (screen) {
            "workouts" -> WorkoutsScreen({ _, _ -> }, {}, {}, isPremium = true)
            // Free account views: plan locked after week 1, breakfast only, upgrade cards.
            "workouts_free", "workouts_week1" -> WorkoutsScreen({ _, _ -> }, {}, {}, isPremium = false)
            "meals" -> PhotoBackdrop(R.drawable.bg_meals) { MealsScreen(isPremium = true, onUpgrade = {}) }
            "meals_free" -> PhotoBackdrop(R.drawable.bg_meals) { MealsScreen(isPremium = false, onUpgrade = {}) }
            "diary_free" -> NutritionScreen(onNavigateToScanner = {}, isPremium = false)
            "progress_free" -> ProgressScreen(isPremium = false)
            "scanner_free" -> ScannerScreen(onBack = {}, onUpgrade = {}, isPremium = false)
            "fasting", "fasting_active", "fasting_done" -> FastingScreen(onBack = {})
            "diary" -> NutritionScreen(onNavigateToScanner = {}, isPremium = true)
            "progress" -> ProgressScreen(isPremium = true)
            "profile" -> ProfileScreen(onNavigateToSubscription = {}, onBack = {})
            "health" -> androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize().padding(16.dp)) {
                com.nexal.app.ui.profile.HealthConnectCard(onUpgrade = {}, forcePremium = true)
            }
            "subscription" -> SubscriptionScreen(onBack = {})
            "paywall" -> PaywallScreen(onBack = {}, onSubscribed = {})
            "paywall_setup" -> PaywallScreen(onBack = {}, onSubscribed = {}, afterSetup = true)
            "paywall_priced", "paywall_monthly", "paywall_winback" -> PaywallContent(
                state = SubscriptionUiState(
                    selectedPlan = if (screen == "paywall_priced") PlanType.YEARLY else PlanType.MONTHLY,
                    monthlyPrice = "$12.99", yearlyPrice = "$109.99",
                    monthlyPriceText = "$12.99/month", yearlyPriceText = "$109.99/year",
                    pricesLoaded = true, pricingChecked = true,
                    availablePlans = setOf(PlanType.MONTHLY, PlanType.YEARLY),
                    yearlyPerMonth = "$9.17", yearlySavingsPercent = 29,
                    monthlyIntroPrice = if (screen == "paywall_winback") "$6.49" else "$4.99",
                    introIsWinback = screen == "paywall_winback",
                    monthlyPerWeek = "$3.00", yearlyPerWeek = "$2.12",
                    setupDays = 4, setupGoal = "Muscle Gain"
                ),
                afterSetup = false, devPricingFallback = false, onClose = {}, onSelectPlan = {},
                onPurchase = {}, onRestore = {}, onDevSkip = {}
            )
            "custom" -> CustomWorkoutsScreen(onBack = {})
            "workout_log" -> WorkoutLogScreen(planId = "preview-plan", dayId = "d1", onBack = {})
            "scanner" -> ScannerScreen(onBack = {}, onUpgrade = {}, isPremium = true)
            "onboarding" -> OnboardingScreen(onComplete = {})
            else -> DashboardScreen({}, {}, {}, {}, {}, {}, {}, {}, isPremium = true)
        }
    }

    private suspend fun seedSampleData(planStartedDaysAgo: Int) {
        profiles.saveProfile(
            UserProfile(
                id = "preview", name = "Sam", weight = 72.0, height = 172.0, age = 29,
                activityLevel = ActivityLevel.MODERATELY_ACTIVE,
                fitnessGoals = listOf(FitnessGoal.GENERAL_FITNESS),
                targetWeight = 68.0, onboardingCompleted = true
            )
        )
        workouts.savePlan(
            WorkoutPlan(
                id = "preview-plan",
                startDate = java.time.Instant.now().minus(java.time.Duration.ofDays(planStartedDaysAgo.toLong())).toString(),
                aiNotes = "Three days a week to build strength and keep cardio up. Add weight when every set feels solid.",
                days = listOf(
                    WorkoutDay(
                        id = "d1", dayNumber = 1, dayLabel = "Upper strength",
                        exercises = listOf(
                            Exercise("e1", "Bench press", "Chest", 4, "8", 90, "Keep your shoulders back."),
                            Exercise("e2", "Bent-over row", "Back", 4, "10", 75),
                            Exercise("e3", "Overhead press", "Shoulders", 3, "10", 75),
                        )
                    ),
                    WorkoutDay(
                        id = "d2", dayNumber = 2, dayLabel = "Interval run",
                        exercises = listOf(Exercise("e4", "Run intervals", "Cardio", 6, "400m", 60))
                    ),
                    WorkoutDay(id = "d3", dayNumber = 3, dayLabel = "Rest", isRestDay = true),
                )
            )
        )
        nutrition.addFoodLogEntry(
            FoodLogEntry(
                id = "preview-food", date = todayFormatted(), foodName = "Chicken and rice",
                servingSize = "1 bowl", quantity = 1,
                macros = MacroNutrients(calories = 640, protein = 48, carbs = 70, fats = 16),
                source = FoodSource.MANUAL, mealSlot = MealSlot.entries.first(),
                createdAt = todayFormatted()
            )
        )
        nutrition.addWaterLogEntry(
            WaterLogEntry(id = "preview-water", date = todayFormatted(), amount = 750, createdAt = todayFormatted())
        )
        nutrition.addCardioLogEntry(
            CardioLogEntry(
                id = "preview-cardio", date = todayFormatted(), type = "Running", durationMinutes = 25,
                estimatedCaloriesBurnt = 240, createdAt = todayFormatted()
            )
        )
        listOf("2026-10-01" to 74.2, "2026-10-20" to 73.1, todayFormatted() to 72.0).forEach { (date, kg) ->
            nutrition.logWeight(WeightEntry(date = date, weight = kg))
        }
        val oats = MacroNutrients(calories = 420, protein = 24, carbs = 58, fats = 10)
        val salad = MacroNutrients(calories = 560, protein = 46, carbs = 42, fats = 22)
        val salmon = MacroNutrients(calories = 640, protein = 44, carbs = 60, fats = 24)
        val yogurt = MacroNutrients(calories = 250, protein = 22, carbs = 28, fats = 6)
        val day = MacroNutrients(calories = 1870, protein = 136, carbs = 188, fats = 62)
        nutrition.saveMealPlan(
            MealPlan(
                id = "preview-meals", date = todayFormatted(),
                meals = listOf(
                    Meal("m1", "Breakfast", listOf(FoodItem("f1", "Protein oats", "1 bowl", oats)), oats),
                    Meal("m2", "Lunch", listOf(FoodItem("f2", "Chicken salad", "1 plate", salad)), salad),
                    Meal("m3", "Dinner", listOf(FoodItem("f3", "Salmon, rice and greens", "1 plate", salmon)), salmon),
                    Meal("m4", "Snack", listOf(FoodItem("f4", "Greek yogurt and berries", "1 cup", yogurt)), yogurt),
                ),
                dailyTotals = day, dailyTargets = MacroNutrients(calories = 1925, protein = 140, carbs = 190, fats = 64),
                aiNotes = "High protein to keep you full while you cut. Prep lunch and dinner on Sunday to save time.",
                createdAt = todayFormatted()
            )
        )
        workouts.saveLog(
            WorkoutLog(
                id = "preview-log", date = todayFormatted(), planId = "preview-plan", dayId = "d1",
                dayLabel = "Upper strength", duration = 48,
                exercises = listOf(
                    ExerciseLog("e1", "Bench press", listOf(SetLog(1, 60.0, 8, true), SetLog(2, 60.0, 8, true)))
                ),
                createdAt = todayFormatted()
            )
        )
    }
}
