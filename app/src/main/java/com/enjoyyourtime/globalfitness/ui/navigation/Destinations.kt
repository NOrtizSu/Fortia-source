package com.enjoyyourtime.globalfitness.ui.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Person
import androidx.compose.ui.graphics.vector.ImageVector
import com.enjoyyourtime.globalfitness.R
import com.enjoyyourtime.globalfitness.ui.components.AppIcons

/**
 * The five sections of the bottom navigation bar, in bar order (Hoy, in the middle, is the one the
 * app opens on). Body and Activity live inside Progreso (as tabs) and Devices is a sub-page of
 * Perfil, so every route here owns the bar and the ad banner.
 */
enum class TopLevel(val route: String, @param:StringRes val label: Int, val selectedIcon: ImageVector, val icon: ImageVector) {
    FOOD("comida", R.string.nav_food, AppIcons.Restaurant, AppIcons.Restaurant),
    TRAINING("entreno", R.string.nav_training, AppIcons.FitnessCenter, AppIcons.FitnessCenter),
    TODAY("hoy", R.string.nav_today, Icons.Filled.Home, Icons.Outlined.Home),
    PROGRESS("progreso", R.string.nav_progress, AppIcons.Insights, AppIcons.Insights),
    PROFILE("perfil", R.string.nav_profile, Icons.Filled.Person, Icons.Outlined.Person),
    ;

    companion object {
        /** The section of [route]; null for sub-pages (which hide the bar). */
        fun of(route: String?): TopLevel? = entries.firstOrNull { it.route == route }
    }
}

/** Full-screen pages opened from a section (no bottom bar, no ads). */
object Routes {
    const val ACCOUNT = "cuenta"
    const val REMOVE_ADS = "quitar-anuncios"
    const val PRIVACY_POLICY = "privacidad"
    const val LICENSES = "codigo-abierto"

    /** Scales, bands and watches, Health Connect and BLE diagnostics (from Perfil and Hoy's chips). */
    const val DEVICES = "dispositivos"
}
