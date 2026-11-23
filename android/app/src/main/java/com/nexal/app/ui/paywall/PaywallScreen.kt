package com.nexal.app.ui.paywall

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.nexal.app.BuildConfig
import com.nexal.app.R
import com.nexal.app.data.repository.FREE_AI_CREDITS
import com.nexal.app.data.repository.PlanType
import com.nexal.app.ui.components.*
import com.nexal.app.ui.subscription.SubscriptionUiState
import com.nexal.app.ui.subscription.SubscriptionViewModel
import com.nexal.app.ui.theme.*
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.sin

// LocalContext is not always the Activity itself — it can be a themed wrapper,
// where a direct cast would throw.
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private val Lime = AccentBright
private val PaywallInk = Color(0xFF0A0B0D)

@Composable
fun PaywallScreen(
    onBack: () -> Unit,
    onSubscribed: () -> Unit,
    afterSetup: Boolean = false,
    viewModel: SubscriptionViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    var automaticRetryCount by remember { mutableIntStateOf(0) }
    var celebrating by remember { mutableStateOf(false) }

    LaunchedEffect(uiState.pricingChecked, uiState.pricesLoaded) {
        if (uiState.pricingChecked && !uiState.pricesLoaded && automaticRetryCount < 2) {
            delay(1_500)
            automaticRetryCount++
            viewModel.retryPricing()
        }
    }

    LaunchedEffect(uiState.isActive, uiState.purchaseCompleted) {
        when {
            // A new purchase earns a moment of celebration before the app opens up.
            uiState.purchaseCompleted -> {
                celebrating = true
                delay(2_200)
                onSubscribed()
            }
            uiState.isActive -> onSubscribed()
        }
    }

    BackHandler(onBack = onBack)

    Box {
        PaywallContent(
            state = uiState,
            afterSetup = afterSetup,
            devPricingFallback = BuildConfig.DEBUG && uiState.pricingChecked && !uiState.pricesLoaded,
            onClose = onBack,
            onSelectPlan = viewModel::selectPlan,
            onPurchase = { context.findActivity()?.let { viewModel.purchase(it) } },
            onRestore = { viewModel.restorePurchases() },
            onDevSkip = { viewModel.skipForDev() }
        )
        WelcomeCelebration(visible = celebrating)
    }
}

/** The paywall sits on a photo in both themes, so it always uses the dark glass colours. */
@Composable
internal fun PaywallContent(
    state: SubscriptionUiState,
    afterSetup: Boolean,
    devPricingFallback: Boolean,
    onClose: () -> Unit,
    onSelectPlan: (PlanType) -> Unit,
    onPurchase: () -> Unit,
    onRestore: () -> Unit,
    onDevSkip: () -> Unit
) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = MaterialTheme.typography,
        shapes = MaterialTheme.shapes
    ) {
        PhotoBackdrop(photo = R.drawable.bg_strength) {
            Box(Modifier.fillMaxSize()) {
                AuroraLayer()
                RisingSparks()
                Column(Modifier.fillMaxSize().statusBarsPadding()) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 20.dp)
                            .widthIn(max = 520.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(top = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            GlassIconButton(Icons.Default.Close, "Close", onClose)
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = onRestore, enabled = !state.isLoading) {
                                Text("Restore", color = GlassTextMuted)
                            }
                        }
                        PremiumHero(state, afterSetup)
                        FadeSlideIn(delayMs = 260) { CompareCard() }
                        FadeSlideIn(delayMs = 380) { PlanOptions(state, onSelectPlan) }
                        FadeSlideIn(delayMs = 460) { HowItWorks(state) }
                        PaywallLinks(onClose, onDevSkip)
                        state.error?.let { error ->
                            Text(
                                error,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                    PurchaseBar(state, devPricingFallback, onPurchase, onDevSkip)
                }
            }
        }
    }
}

// ─── Background motion ─────────────────────────────────────────────────────────

/** Two soft lime and teal lights drifting over the photo. */
@Composable
private fun AuroraLayer() {
    val t = rememberInfiniteTransition(label = "aurora")
    val drift by t.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(9_000, easing = LinearEasing), RepeatMode.Reverse),
        label = "drift"
    )
    Canvas(Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        drawRect(
            Brush.radialGradient(
                listOf(Lime.copy(alpha = 0.22f), Color.Transparent),
                center = Offset(w * (0.15f + 0.5f * drift), h * 0.12f),
                radius = w * 0.85f
            )
        )
        drawRect(
            Brush.radialGradient(
                listOf(Color(0xFF2FD3B5).copy(alpha = 0.14f), Color.Transparent),
                center = Offset(w * (0.9f - 0.4f * drift), h * (0.45f + 0.1f * drift)),
                radius = w * 0.75f
            )
        )
    }
}

/** Small lime sparks rising slowly, like chalk dust in gym light. */
@Composable
private fun RisingSparks() {
    val t = rememberInfiniteTransition(label = "sparks")
    val progress by t.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(14_000, easing = LinearEasing)),
        label = "rise"
    )
    Canvas(Modifier.fillMaxSize()) {
        repeat(22) { i ->
            val seed = (i * 0.618f) % 1f
            val y = ((1f - progress - seed) % 1f + 1f) % 1f
            val x = (seed + 0.06f * sin((progress * 2 * PI + i).toFloat())) % 1f
            val alpha = (sin(y * PI).toFloat() * 0.55f).coerceAtLeast(0f)
            drawCircle(
                Lime.copy(alpha = alpha),
                radius = (1.5f + (i % 3)) * density,
                center = Offset(x * size.width, y * size.height)
            )
        }
    }
}

// ─── Hero ──────────────────────────────────────────────────────────────────────

@Composable
private fun PremiumHero(state: SubscriptionUiState, afterSetup: Boolean) {
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        ScalePopIn { PremiumEmblem() }
        ShimmerText("NEXAL PREMIUM")
        FadeSlideIn(delayMs = 120, horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                if (afterSetup) setupHeadline(state.setupDays, state.setupGoal) else "Train smarter.\nEat better. See results.",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                color = GlassText,
                textAlign = TextAlign.Center
            )
        }
        FadeSlideIn(delayMs = 180, horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                if (afterSetup) "Your goal and schedule are saved. Premium builds your full plan from them."
                else "Your coach for every set and every meal, built around you.",
                style = MaterialTheme.typography.bodyMedium,
                color = GlassTextMuted,
                textAlign = TextAlign.Center
            )
        }
    }
}

/** The crown, breathing, with rings of light pulsing out of it. */
@Composable
private fun PremiumEmblem() {
    val t = rememberInfiniteTransition(label = "emblem")
    val pulse by t.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(2_400, easing = LinearOutSlowInEasing)),
        label = "pulse"
    )
    val breathe by t.animateFloat(
        0.96f, 1.04f,
        infiniteRepeatable(tween(1_400, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "breathe"
    )
    Box(Modifier.size(132.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            listOf(0f, 0.5f).forEach { offset ->
                val p = (pulse + offset) % 1f
                drawCircle(
                    Lime.copy(alpha = (1f - p) * 0.5f),
                    radius = size.minDimension * (0.28f + 0.22f * p),
                    style = Stroke(width = 2.dp.toPx())
                )
            }
            drawCircle(
                Brush.radialGradient(listOf(Lime.copy(alpha = 0.45f), Color.Transparent)),
                radius = size.minDimension * 0.42f
            )
        }
        Box(
            Modifier
                .size(64.dp)
                .scale(breathe)
                .clip(CircleShape)
                .background(Brush.linearGradient(listOf(Lime, Color(0xFF8BD63A)))),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.WorkspacePremium, null, tint = PaywallInk, modifier = Modifier.size(34.dp))
        }
    }
}

/** Label with a band of light sweeping across it. */
@Composable
private fun ShimmerText(text: String) {
    val t = rememberInfiniteTransition(label = "shimmer")
    val x by t.animateFloat(
        -300f, 900f,
        infiniteRepeatable(tween(2_600, easing = LinearEasing)),
        label = "x"
    )
    Text(
        text,
        style = TextStyle(
            brush = Brush.linearGradient(
                listOf(Lime, Color.White, Lime),
                start = Offset(x, 0f),
                end = Offset(x + 260f, 0f)
            ),
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 3.sp
        )
    )
}

// ─── Free vs Premium ───────────────────────────────────────────────────────────

private data class CompareRow(val icon: ImageVector, val feature: String, val free: String?, val premium: String)

private val compareRows = listOf(
    CompareRow(Icons.Default.FitnessCenter, "AI workout plans", "Week 1", "Full plan"),
    CompareRow(Icons.Default.Restaurant, "AI meal plans", "Breakfast", "Whole day"),
    CompareRow(Icons.Default.PhotoCamera, "Photo meal logging", null, "Unlimited"),
    CompareRow(Icons.Default.QrCodeScanner, "Barcode scanning", null, "Unlimited"),
    CompareRow(Icons.Default.Insights, "Weekly AI check-in", null, "Every week"),
    CompareRow(Icons.Default.ShoppingCart, "Shopping list & meal prep", null, "Included"),
    CompareRow(Icons.Default.AcUnit, "Streak freeze", null, "1 a week"),
)

@Composable
private fun CompareCard() {
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("What you get", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = GlassText, modifier = Modifier.weight(1f))
                Text("Free", style = MaterialTheme.typography.labelMedium, color = GlassTextMuted, modifier = Modifier.width(64.dp), textAlign = TextAlign.Center)
                Text("Premium", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = Lime, modifier = Modifier.width(76.dp), textAlign = TextAlign.Center)
            }
            Text(
                "Free includes $FREE_AI_CREDITS AI actions to try it all.",
                style = MaterialTheme.typography.bodySmall,
                color = GlassTextMuted
            )
            Spacer(Modifier.height(8.dp))
            val visible = rememberStaggeredVisible(compareRows.size, stepMs = 70)
            compareRows.forEachIndexed { i, row ->
                AnimatedVisibility(
                    visible = visible.getOrElse(i) { true },
                    enter = fadeIn(tween(260)) + scaleIn(initialScale = 0.94f, animationSpec = spring(dampingRatio = 0.7f))
                ) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(row.icon, null, tint = GlassText, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(row.feature, style = MaterialTheme.typography.bodyMedium, color = GlassText, modifier = Modifier.weight(1f))
                        Box(Modifier.width(64.dp), contentAlignment = Alignment.Center) {
                            if (row.free != null) {
                                Text(row.free, style = MaterialTheme.typography.labelSmall, color = GlassTextMuted)
                            } else {
                                Icon(Icons.Default.Remove, null, tint = GlassTextMuted, modifier = Modifier.size(16.dp))
                            }
                        }
                        Box(Modifier.width(76.dp), contentAlignment = Alignment.Center) {
                            Text(row.premium, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Lime)
                        }
                    }
                }
            }
        }
    }
}

// ─── Plans ─────────────────────────────────────────────────────────────────────

@Composable
private fun PlanOptions(state: SubscriptionUiState, onSelectPlan: (PlanType) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (PlanType.YEARLY in state.availablePlans) {
            PlanCard(
                label = "Yearly",
                price = state.yearlyPrice,
                period = state.yearlyPeriod,
                notes = listOfNotNull(
                    state.yearlyPerMonth?.let { "$it a month" },
                    state.yearlyPerWeek?.let { "$it a week" }
                ),
                badge = state.yearlySavingsPercent?.let { "Best value · Save $it%" } ?: "Best value",
                highlight = null,
                selected = state.selectedPlan == PlanType.YEARLY,
                onClick = { onSelectPlan(PlanType.YEARLY) }
            )
        }
        if (PlanType.MONTHLY in state.availablePlans) {
            val intro = state.monthlyIntroPrice
            PlanCard(
                label = "Monthly",
                price = intro ?: state.monthlyPrice,
                period = if (intro != null) "first month" else state.monthlyPeriod,
                notes = listOfNotNull(
                    if (intro != null) "then ${state.monthlyPrice}${state.monthlyPeriod}" else null,
                    state.monthlyPerWeek?.let { "$it a week" }
                ),
                badge = if (intro != null && state.introIsWinback) "Welcome back" else null,
                highlight = if (intro != null) (if (state.introIsWinback) "Comeback price" else "Intro offer") else null,
                selected = state.selectedPlan == PlanType.MONTHLY,
                onClick = { onSelectPlan(PlanType.MONTHLY) }
            )
        }
    }
}

@Composable
private fun PlanCard(
    label: String,
    price: String,
    period: String,
    notes: List<String>,
    badge: String?,
    highlight: String?,
    selected: Boolean,
    onClick: () -> Unit
) {
    val scale by animateFloatAsState(if (selected) 1.02f else 1f, spring(dampingRatio = 0.55f, stiffness = 380f), label = "planScale")
    val spin = rememberInfiniteTransition(label = "border")
    val angle by spin.animateFloat(0f, 360f, infiniteRepeatable(tween(3_200, easing = LinearEasing)), label = "angle")
    val shape = RoundedCornerShape(24.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .semantics {
                this.selected = selected
                role = Role.RadioButton
            }
    ) {
        // Selected: a comet of light runs round the card's edge.
        Box(
            Modifier
                .fillMaxWidth()
                // Room for the badge to sit on the edge without being clipped.
                .padding(top = if (badge != null) 10.dp else 0.dp)
                .clip(shape)
                .drawBehind {
                    if (selected) {
                        rotate(angle) {
                            drawCircle(
                                Brush.sweepGradient(listOf(Lime.copy(alpha = 0.25f), Lime, Lime.copy(alpha = 0.25f), Lime.copy(alpha = 0.25f))),
                                radius = size.maxDimension
                            )
                        }
                    } else {
                        drawRect(Color.White.copy(alpha = 0.14f))
                    }
                }
                .padding(if (selected) 2.dp else 1.dp)
        ) {
        GlassCard(
            modifier = Modifier.fillMaxWidth(),
            cornerRadius = 22.dp,
            onClick = onClick
        ) {
            Row(Modifier.padding(18.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                RadioDot(selected)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = GlassText)
                    highlight?.let {
                        Text(it, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Lime)
                    }
                    notes.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = GlassTextMuted) }
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(price, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = GlassText)
                    Text(period, style = MaterialTheme.typography.bodySmall, color = GlassTextMuted)
                }
            }
        }
        }
        if (badge != null) {
            Text(
                badge,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = PaywallInk,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = (-16).dp)
                    .clip(CircleShape)
                    .background(Lime)
                    .padding(horizontal = 10.dp, vertical = 3.dp)
            )
        }
    }
}

@Composable
private fun RadioDot(selected: Boolean) {
    val fill by animateFloatAsState(if (selected) 1f else 0f, spring(dampingRatio = 0.5f), label = "dot")
    Box(
        Modifier.size(22.dp).border(2.dp, if (selected) Lime else GlassTextMuted, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Box(Modifier.size(12.dp).scale(fill).clip(CircleShape).background(Lime))
    }
}

@Composable
private fun HowItWorks(state: SubscriptionUiState) {
    val intro = state.monthlyIntroPrice?.takeIf { state.selectedPlan == PlanType.MONTHLY }
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("How it works", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = GlassText)
            StepRow(Icons.Default.Bolt, "Today", "Premium unlocks instantly on this account")
            if (intro != null) {
                StepRow(Icons.Default.CalendarMonth, "From month 2", "${state.monthlyPrice}${state.monthlyPeriod}")
            }
            StepRow(Icons.Default.Close, "Anytime", "Cancel in Google Play in two taps")
        }
    }
}

@Composable
private fun StepRow(icon: ImageVector, title: String, detail: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        IconBadge(icon, tint = Lime)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = GlassText)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = GlassTextMuted)
        }
    }
}

@Composable
private fun PaywallLinks(onClose: () -> Unit, onDevSkip: () -> Unit) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        TextButton(onClick = onClose) {
            Text("Continue with free tracking", color = GlassText, fontWeight = FontWeight.SemiBold)
        }
        if (BuildConfig.DEBUG) {
            TextButton(onClick = onDevSkip) {
                Text("Skip (Dev Testing)", color = GlassTextMuted, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

// ─── Purchase ──────────────────────────────────────────────────────────────────

@Composable
private fun PurchaseBar(
    state: SubscriptionUiState,
    devPricingFallback: Boolean,
    onPurchase: () -> Unit,
    onDevSkip: () -> Unit
) {
    val intro = state.monthlyIntroPrice?.takeIf { state.selectedPlan == PlanType.MONTHLY }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Slate950, Slate950)))
            .navigationBarsPadding()
            .padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ShineButton(
            text = when {
                devPricingFallback -> "Continue with Dev Premium"
                !state.pricesLoaded -> "Connecting to Google Play..."
                intro != null -> "Start for $intro"
                else -> "Start Nexal Premium"
            },
            onClick = { if (devPricingFallback) onDevSkip() else onPurchase() },
            loading = state.isLoading,
            enabled = (state.pricesLoaded || devPricingFallback) && !state.isLoading
        )
        Text(
            when {
                devPricingFallback -> "Testing access only. No payment will be made."
                !state.pricesLoaded && state.pricingChecked -> "Could not load Google Play pricing."
                !state.pricesLoaded -> "Secure billing through Google Play"
                intro != null -> "$intro for the first month, then ${state.monthlyPriceText}. Renews automatically. Cancel anytime in Google Play."
                // The yearly base plan is prepaid in Play: one payment, no auto-renewal.
                state.selectedPlan == PlanType.YEARLY ->
                    "${state.yearlyPriceText}, paid once for 12 months. It doesn't renew automatically."
                else -> "${selectedPriceText(state)}, renews automatically. Cancel anytime in Google Play."
            },
            style = MaterialTheme.typography.labelSmall,
            color = GlassTextMuted,
            textAlign = TextAlign.Center
        )
    }
}

/** The main button: lime, with a band of light sweeping across every few seconds. */
@Composable
private fun ShineButton(text: String, onClick: () -> Unit, loading: Boolean, enabled: Boolean) {
    val t = rememberInfiniteTransition(label = "shine")
    val sweep by t.animateFloat(
        -0.4f, 1.6f,
        infiniteRepeatable(tween(2_800, delayMillis = 600, easing = FastOutSlowInEasing)),
        label = "sweep"
    )
    val source = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(18.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .height(58.dp)
            .pressScale(source)
            .clip(shape)
            .background(if (enabled) Lime else Lime.copy(alpha = 0.4f))
            .drawBehind {
                if (enabled) {
                    val x = size.width * sweep
                    drawRect(
                        Brush.linearGradient(
                            listOf(Color.Transparent, Color.White.copy(alpha = 0.55f), Color.Transparent),
                            start = Offset(x - 120f, 0f),
                            end = Offset(x + 120f, size.height)
                        )
                    )
                }
            }
            .clickable(source, null, enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (loading) {
            CircularProgressIndicator(color = PaywallInk, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
        } else {
            Text(text, color = PaywallInk, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
        }
    }
}

/** Shown for a moment after a successful purchase. */
@Composable
private fun WelcomeCelebration(visible: Boolean) {
    AnimatedVisibility(visible = visible, enter = fadeIn(tween(300)), exit = fadeOut()) {
        Box(
            Modifier.fillMaxSize().background(PaywallInk.copy(alpha = 0.88f)),
            contentAlignment = Alignment.Center
        ) {
            val burst = remember { Animatable(0f) }
            LaunchedEffect(Unit) { burst.animateTo(1f, tween(1_400, easing = FastOutSlowInEasing)) }
            Canvas(Modifier.fillMaxSize()) {
                val center = Offset(size.width / 2, size.height / 2)
                repeat(36) { i ->
                    val angle = (i / 36f) * 2 * PI
                    val distance = size.minDimension * 0.45f * burst.value * (0.6f + (i % 4) * 0.12f)
                    drawCircle(
                        (if (i % 3 == 0) Color.White else Lime).copy(alpha = 1f - burst.value * 0.7f),
                        radius = (3 + i % 3) * density,
                        center = Offset(
                            center.x + (kotlin.math.cos(angle) * distance).toFloat(),
                            center.y + (sin(angle) * distance).toFloat()
                        )
                    )
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                ScalePopIn { PremiumEmblem() }
                Spacer(Modifier.height(12.dp))
                Text("Welcome to Premium", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = Color.White)
                Text("Everything is unlocked. Let's build your plan.", style = MaterialTheme.typography.bodyMedium, color = GlassTextMuted)
            }
        }
    }
}

private fun selectedPriceText(state: SubscriptionUiState): String =
    if (state.selectedPlan == PlanType.MONTHLY) state.monthlyPriceText else state.yearlyPriceText
