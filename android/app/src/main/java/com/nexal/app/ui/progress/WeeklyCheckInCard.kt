package com.nexal.app.ui.progress

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nexal.app.domain.model.WeeklyCheckIn
import com.nexal.app.ui.components.FitCard
import com.nexal.app.ui.components.GradientButton
import com.nexal.app.ui.theme.limeEdge
import java.util.concurrent.TimeUnit

/** Days since the check-in ran, for "2 days ago". */
internal fun checkInAgeDays(createdAt: Long, now: Long = System.currentTimeMillis()): Int =
    TimeUnit.MILLISECONDS.toDays((now - createdAt).coerceAtLeast(0)).toInt()

@Composable
fun WeeklyCheckInCard(
    checkIn: WeeklyCheckIn?,
    loading: Boolean,
    error: String?,
    onRun: () -> Unit
) {
    FitCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.AutoAwesome, null, tint = limeEdge(), modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("WEEKLY CHECK-IN", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                if (checkIn != null) {
                    val age = checkInAgeDays(checkIn.createdAt)
                    Text(
                        when (age) { 0 -> "Today"; 1 -> "Yesterday"; else -> "$age days ago" },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(10.dp))

            if (checkIn == null) {
                Text("See how your week really went", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text(
                    "AI reviews your workouts, food and weight from the last 7 days and sets targets for next week.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(checkIn.headline, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Text("${checkIn.score}", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = limeEdge())
                }
                if (checkIn.summary.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(checkIn.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                CheckInList("Wins", checkIn.wins, Icons.Default.CheckCircle)
                CheckInList("Next week", checkIn.focus, Icons.Default.TrendingUp)
                if (checkIn.liftTargets.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text("Lift targets", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                    checkIn.liftTargets.forEach { target ->
                        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                            Text(target.exercise, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                            Text(target.target, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = limeEdge())
                        }
                    }
                }
                if (checkIn.calorieAdjustment != 0) {
                    Spacer(Modifier.height(10.dp))
                    val sign = if (checkIn.calorieAdjustment > 0) "+" else ""
                    Text(
                        "Calories: $sign${checkIn.calorieAdjustment} a day next week",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(14.dp))
            GradientButton(
                text = if (checkIn == null) "Run my check-in" else "New check-in",
                onClick = onRun,
                loading = loading,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun CheckInList(title: String, items: List<String>, icon: ImageVector) {
    if (items.isEmpty()) return
    Spacer(Modifier.height(10.dp))
    Text(title, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
    items.forEach { line ->
        Row(Modifier.padding(top = 4.dp)) {
            Icon(icon, null, tint = limeEdge(), modifier = Modifier.size(14.dp).padding(top = 2.dp))
            Spacer(Modifier.width(6.dp))
            Text(line, style = MaterialTheme.typography.bodySmall, color = Color.Unspecified)
        }
    }
}
