package com.nexal.app.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.ui.unit.IntOffset
import androidx.navigation.NavBackStackEntry

/*
 * Nexal's motion language:
 *  - Tabs zoom through each other and drift the way you're heading on the bar.
 *  - Detail screens arrive like a card dealt on top; the page behind sinks back.
 *  - Premium rises from the bottom like a sheet, with the page behind receding.
 * Springs, not fixed curves, so every move settles naturally.
 */

private typealias NavScope = AnimatedContentTransitionScope<NavBackStackEntry>

private val sheetRoutes = setOf(Screen.Paywall.route, Screen.PaywallSetup.route)
private val tabRoutes = bottomNavItems.map { it.screen.route }

private val NavScope.toSheet get() = targetState.destination.route in sheetRoutes
private val NavScope.fromSheet get() = initialState.destination.route in sheetRoutes

/** -1 moving left on the tab bar, 1 moving right, 0 when not tab to tab. */
private fun NavScope.tabDirection(): Int {
    val from = tabRoutes.indexOf(initialState.destination.route)
    val to = tabRoutes.indexOf(targetState.destination.route)
    return if (from < 0 || to < 0 || from == to) 0 else if (to > from) 1 else -1
}

private val settle = spring<Float>(dampingRatio = 0.82f, stiffness = 340f)
private val settleOffset = spring<IntOffset>(dampingRatio = 0.86f, stiffness = 320f)
private val sheetOffset = spring<IntOffset>(dampingRatio = 0.84f, stiffness = Spring.StiffnessMediumLow)

/** The page behind a sheet or a new card: it sinks back and dims. */
private fun recede(): ExitTransition =
    scaleOut(targetScale = 0.9f, animationSpec = tween(380, easing = FastOutSlowInEasing)) +
        fadeOut(targetAlpha = 0.35f, animationSpec = tween(380))

/** The page coming back from behind a sheet or card. */
private fun resurface(): EnterTransition =
    scaleIn(initialScale = 0.9f, animationSpec = settle) + fadeIn(initialAlpha = 0.35f, animationSpec = tween(320))

// ─── Tabs ──────────────────────────────────────────────────────────────────────

fun NavScope.tabEnterTransition(): EnterTransition {
    // Back from a sheet or a detail screen: the tab rises up again.
    if (fromSheet || initialState.destination.route !in tabRoutes) return resurface()
    val dir = tabDirection()
    return fadeIn(tween(300, delayMillis = 60)) +
        scaleIn(initialScale = 0.93f, animationSpec = settle) +
        slideInHorizontally(settleOffset) { it * dir / 7 }
}

fun NavScope.tabExitTransition(): ExitTransition {
    // Opening a sheet or a detail screen over a tab: the tab sinks back.
    if (toSheet || targetState.destination.route !in tabRoutes) return recede()
    val dir = tabDirection()
    return fadeOut(tween(160)) +
        scaleOut(targetScale = 1.05f, animationSpec = tween(220, easing = FastOutSlowInEasing)) +
        slideOutHorizontally(tween(220, easing = FastOutSlowInEasing)) { -it * dir / 10 }
}

// ─── Detail screens ────────────────────────────────────────────────────────────

/** A new screen is dealt in from the right, on top. */
fun NavScope.pushEnter(): EnterTransition =
    slideInHorizontally(settleOffset) { it } + fadeIn(tween(180))

/** The screen underneath sinks back. */
fun NavScope.pushExit(): ExitTransition = recede()

/** Coming back: the screen underneath rises again. */
fun NavScope.popEnter(): EnterTransition = resurface()

/** The card slides away to the right. */
fun NavScope.popExit(): ExitTransition =
    slideOutHorizontally(tween(300, easing = FastOutSlowInEasing)) { it } + fadeOut(tween(260, delayMillis = 60))

// ─── Premium sheet ─────────────────────────────────────────────────────────────

/** Premium rises from the bottom edge and settles with a little give. */
fun NavScope.sheetEnter(): EnterTransition =
    slideInVertically(sheetOffset) { it } + fadeIn(tween(220))

/** Closing Premium: it drops back down. */
fun NavScope.sheetExit(): ExitTransition =
    slideOutVertically(tween(320, easing = FastOutSlowInEasing)) { it } + fadeOut(tween(260, delayMillis = 80))
