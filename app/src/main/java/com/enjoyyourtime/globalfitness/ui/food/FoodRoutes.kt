package com.enjoyyourtime.globalfitness.ui.food

import android.net.Uri
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.navArgument
import com.enjoyyourtime.globalfitness.ui.navigation.TopLevel
import com.enjoyyourtime.globalfitness.ui.navigation.openSubPage
import com.enjoyyourtime.globalfitness.ui.navigation.subPage
import com.enjoyyourtime.globalfitness.ui.navigation.subPageWithArgs

/** Sub-pages of the Comida tab (registered by [foodGraph]). */
object FoodRoutes {
    const val ARG_ENTRY_ID = "entryId"

    /** A meal photo with the recognizer's suggestions. */
    const val PHOTO = "comida/foto"

    /** Search the catalog and the user's foods. */
    const val SEARCH = "comida/buscar"

    /** A barcode photo looked up in Open Food Facts. */
    const val BARCODE = "comida/codigo"

    /** Confirm a food: amount, meal, time. */
    const val CONFIRM = "comida/confirmar"

    /** A food entered by hand. */
    const val MANUAL = "comida/manual"

    /** The daily goal (also opened from Perfil). */
    const val GOAL = "comida/objetivo"

    /** Edit (or delete) a logged entry. */
    const val EDIT = "comida/editar/{$ARG_ENTRY_ID}"

    fun edit(id: String) = "comida/editar/${Uri.encode(id)}"
}

fun NavGraphBuilder.foodGraph(navController: NavHostController) {
    val open: (String) -> Unit = { navController.openSubPage(it) }
    // After saving: back to the photo (to add the next food of the plate) or to the day.
    val saved: (Boolean) -> Unit = { fromPhoto ->
        val target = if (fromPhoto) FoodRoutes.PHOTO else TopLevel.FOOD.route
        if (!navController.popBackStack(target, inclusive = false)) navController.popBackStack(TopLevel.FOOD.route, inclusive = false)
    }
    subPage(FoodRoutes.PHOTO, navController) { back -> FoodPhotoScreen(onOpen = open, onDone = back) }
    subPage(FoodRoutes.SEARCH, navController) { back -> FoodSearchScreen(onOpen = open, onBack = back) }
    subPage(FoodRoutes.BARCODE, navController) { back -> FoodBarcodeScreen(onOpen = open, onBack = back) }
    subPage(FoodRoutes.MANUAL, navController) { back -> FoodManualScreen(onOpen = open, onBack = back) }
    subPage(FoodRoutes.CONFIRM, navController) { back -> FoodConfirmScreen(editId = null, onSaved = saved, onBack = back) }
    subPage(FoodRoutes.GOAL, navController) { back -> FoodGoalScreen(onBack = back) }
    subPageWithArgs(
        FoodRoutes.EDIT,
        navController,
        arguments = listOf(navArgument(FoodRoutes.ARG_ENTRY_ID) { type = NavType.StringType }),
    ) { back, entry ->
        val id = entry.arguments?.getString(FoodRoutes.ARG_ENTRY_ID).orEmpty()
        FoodConfirmScreen(editId = id, onSaved = { back() }, onBack = back)
    }
}
