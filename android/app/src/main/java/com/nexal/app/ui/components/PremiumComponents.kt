package com.nexal.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nexal.app.data.repository.FREE_AI_CREDITS
import com.nexal.app.ui.theme.AccentBright
import com.nexal.app.ui.theme.limeEdge

/** "2 of 3 free AI actions left". Hidden for Premium and before the count is known. */
@Composable
fun FreeCreditsNote(
    creditsLeft: Int?,
    onUpgrade: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (creditsLeft == null) return
    val onPhoto = isOnPhoto()
    val text = if (onPhoto) GlassText else MaterialTheme.colorScheme.onSurface
    val accent = if (onPhoto) glassAccent() else limeEdge()
    Surface(
        onClick = onUpgrade,
        shape = RoundedCornerShape(50),
        color = text.copy(alpha = 0.08f),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.AutoAwesome, null, tint = accent, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                if (creditsLeft > 0) "$creditsLeft of $FREE_AI_CREDITS free AI actions left"
                else "Free AI actions used",
                style = MaterialTheme.typography.labelMedium,
                color = text
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "Go Premium",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = accent
            )
        }
    }
}

/** A locked feature: what it is, why it's worth it, and one button to Premium. */
@Composable
fun UpgradeCard(
    title: String,
    message: String,
    onUpgrade: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Default.Lock,
    buttonText: String = "Go Premium"
) {
    val onPhoto = isOnPhoto()
    val text = if (onPhoto) GlassText else MaterialTheme.colorScheme.onSurface
    val accent = if (onPhoto) glassAccent() else limeEdge()
    val body: @Composable ColumnScope.() -> Unit = {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(accent.copy(alpha = 0.16f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(icon, null, tint = accent, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(12.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = text)
            }
            Spacer(Modifier.height(8.dp))
            Text(message, style = MaterialTheme.typography.bodyMedium, color = text.copy(alpha = 0.78f))
            Spacer(Modifier.height(14.dp))
            GradientButton(text = buttonText, onClick = onUpgrade, modifier = Modifier.fillMaxWidth())
        }
    }
    // Dark glass on photo screens; the regular themed card everywhere else.
    if (onPhoto) GlassCard(modifier = modifier.fillMaxWidth(), content = body)
    else FitCard(modifier = modifier.fillMaxWidth(), content = body)
}

/** Small lime badge shown next to a Premium member's name. */
@Composable
fun PremiumBadge(modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(50),
        color = AccentBright,
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.WorkspacePremium, null, tint = Color(0xFF0A0B0D), modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
            Text("PREMIUM", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Color(0xFF0A0B0D))
        }
    }
}

/** True when an error from the server means the user has to upgrade to continue. */
fun String.needsPremium(): Boolean =
    contains("Premium", ignoreCase = true) || contains("free AI actions", ignoreCase = true)
