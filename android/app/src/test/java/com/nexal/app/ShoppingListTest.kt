package com.nexal.app

import com.nexal.app.domain.model.FoodItem
import com.nexal.app.domain.model.Meal
import com.nexal.app.domain.model.MealPlan
import com.nexal.app.ui.meals.ShoppingItem
import com.nexal.app.ui.meals.buildShoppingList
import org.junit.Assert.assertEquals
import org.junit.Test

class ShoppingListTest {
    private val plan = MealPlan(
        id = "p",
        meals = listOf(
            Meal("1", "Breakfast", listOf(FoodItem("a", "Oats", "80 g"), FoodItem("b", "Banana", "1 medium"))),
            Meal("2", "Snack", listOf(FoodItem("c", "banana", "1 medium"), FoodItem("d", " ", "1"))),
            Meal("3", "Dinner", listOf(FoodItem("e", "Oats", "40 g"))),
        )
    )

    @Test
    fun `merges the same food and portion, ignoring case`() {
        val list = buildShoppingList(plan, 1)
        assertEquals(
            listOf(
                ShoppingItem("Banana", "1 medium", 2),
                ShoppingItem("Oats", "80 g", 1),
                ShoppingItem("Oats", "40 g", 1),
            ),
            list
        )
    }

    @Test
    fun `multiplies portions by meal prep days`() {
        val list = buildShoppingList(plan, 5)
        assertEquals(10, list.first { it.name == "Banana" }.portions)
    }

    @Test
    fun `empty plan gives an empty list`() {
        assertEquals(emptyList<ShoppingItem>(), buildShoppingList(MealPlan(), 3))
    }
}
