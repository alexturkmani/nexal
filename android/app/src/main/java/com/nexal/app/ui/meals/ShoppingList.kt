package com.nexal.app.ui.meals

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.nexal.app.domain.model.MealPlan
import com.nexal.app.ui.components.FitButton
import com.nexal.app.ui.components.FitModal
import com.nexal.app.ui.components.PillChip
import com.nexal.app.ui.components.RoundCheck

/** One line on the shopping list: a food, its portion, and how many portions to buy. */
data class ShoppingItem(val name: String, val servingSize: String, val portions: Int)

/** Days you can prep for at once. */
val MEAL_PREP_DAYS = listOf(1, 3, 5, 7)

/**
 * Groups a day's plan into a shopping list for [days] days of meal prep.
 * Same food and portion size are merged; names compare case-insensitively.
 */
fun buildShoppingList(plan: MealPlan, days: Int): List<ShoppingItem> {
    val counts = linkedMapOf<Pair<String, String>, Pair<String, Int>>()
    plan.meals.flatMap { it.foods }.forEach { food ->
        val name = food.name.trim()
        if (name.isEmpty()) return@forEach
        val key = name.lowercase() to food.servingSize.trim().lowercase()
        val (shown, count) = counts[key] ?: (name to 0)
        counts[key] = shown to count + 1
    }
    return counts.map { (key, value) ->
        ShoppingItem(name = value.first, servingSize = key.second, portions = value.second * days.coerceAtLeast(1))
    }.sortedBy { it.name.lowercase() }
}

@Composable
fun ShoppingListModal(plan: MealPlan, onDismiss: () -> Unit) {
    var days by rememberSaveable(plan.id) { mutableIntStateOf(1) }
    var checked by rememberSaveable(plan.id) { mutableStateOf(setOf<String>()) }
    val items = remember(plan, days) { buildShoppingList(plan, days) }

    FitModal(isOpen = true, onDismiss = onDismiss, title = "Shopping list") {
        Text(
            "Meal prep for",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MEAL_PREP_DAYS.forEach { option ->
                PillChip(
                    selected = days == option,
                    onClick = { days = option },
                    label = { Text(if (option == 1) "1 day" else "$option days") }
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        Column(
            modifier = Modifier
                .heightIn(max = 380.dp)
                .verticalScroll(rememberScrollState())
        ) {
            items.forEach { item ->
                val id = "${item.name}|${item.servingSize}"
                val done = id in checked
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RoundCheck(
                        checked = done,
                        onCheckedChange = { checked = if (done) checked - id else checked + id },
                        description = item.name
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            item.name,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            textDecoration = if (done) TextDecoration.LineThrough else null
                        )
                        Text(
                            "${item.portions} × ${item.servingSize.ifBlank { "portion" }}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "${checked.size} of ${items.size} in your basket",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))
        FitButton(text = "Done", onClick = onDismiss, modifier = Modifier.fillMaxWidth())
    }
}
