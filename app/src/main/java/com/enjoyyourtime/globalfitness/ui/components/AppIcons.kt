package com.enjoyyourtime.globalfitness.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** A few Material Symbols paths, so the large icons-extended artifact is not needed. */
object AppIcons {
    /** [autoMirror]: directional glyphs are flipped in right-to-left layouts. */
    private fun icon(name: String, vararg paths: String, evenOdd: Boolean = false, autoMirror: Boolean = false): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
            autoMirror = autoMirror,
        ).apply {
            for (p in paths) {
                addPath(
                    pathData = addPathNodes(p),
                    pathFillType = if (evenOdd) PathFillType.EvenOdd else PathFillType.NonZero,
                    fill = SolidColor(Color.Black),
                )
            }
        }.build()

    /**
     * App glyph (the launcher icon's drawing): a dumbbell whose bar is a heartbeat line. The pulse
     * is a little heavier than a plain scale-down so it still reads at 24 dp.
     */
    val Logo: ImageVector by lazy {
        ImageVector.Builder("Logo", 24.dp, 24.dp, 24f, 24f).apply {
            val ink = SolidColor(Color.Black)
            addPath(
                pathData = addPathNodes(
                    "M2.1,9.6H3.15A0.75,0.75 0 0 1 3.9,10.35V13.65A0.75,0.75 0 0 1 3.15,14.4H2.1A0.75,0.75 0 0 1 1.35,13.65V10.35A0.75,0.75 0 0 1 2.1,9.6Z" +
                        "M20.85,9.6H21.9A0.75,0.75 0 0 1 22.65,10.35V13.65A0.75,0.75 0 0 1 21.9,14.4H20.85A0.75,0.75 0 0 1 20.1,13.65V10.35A0.75,0.75 0 0 1 20.85,9.6Z" +
                        "M4.35,6.6H5.25A1.05,1.05 0 0 1 6.3,7.65V16.35A1.05,1.05 0 0 1 5.25,17.4H4.35A1.05,1.05 0 0 1 3.3,16.35V7.65A1.05,1.05 0 0 1 4.35,6.6Z" +
                        "M18.75,6.6H19.65A1.05,1.05 0 0 1 20.7,7.65V16.35A1.05,1.05 0 0 1 19.65,17.4H18.75A1.05,1.05 0 0 1 17.7,16.35V7.65A1.05,1.05 0 0 1 18.75,6.6Z",
                ),
                fill = ink,
            )
            addPath(
                pathData = addPathNodes("M6.3,12L8.4,12L9.75,8.25L11.85,15.75L13.8,9.45L15.3,12L17.7,12"),
                stroke = ink,
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    val Bluetooth: ImageVector by lazy {
        icon(
            "Bluetooth",
            "M17.71,7.71L12,2h-1v7.59L6.41,5 5,6.41 10.59,12 5,17.59 6.41,19 11,14.41L11,22h1l5.71,-5.71 -4.3,-4.29 4.3,-4.29zM13,5.83l1.88,1.88L13,9.59L13,5.83zM14.88,16.29L13,18.17v-3.76l1.88,1.88z",
        )
    }

    val Bolt: ImageVector by lazy {
        icon("Bolt", "M11,21h-1l1,-7H7.5c-0.88,0 -0.33,-0.75 -0.31,-0.78C8.48,10.94 10.42,7.54 13.01,3h1l-1,7h3.51c0.4,0 0.62,0.19 0.4,0.66C12.97,17.55 11,21 11,21z")
    }

    /** Bathroom scale glyph. */
    val Scale: ImageVector by lazy {
        icon(
            "Scale",
            "M6,3h12a3,3 0 0 1 3,3v12a3,3 0 0 1 -3,3H6a3,3 0 0 1 -3,-3V6a3,3 0 0 1 3,-3z" +
                "M9,5.5h6a1,1 0 0 1 1,1v1.5a1,1 0 0 1 -1,1H9a1,1 0 0 1 -1,-1V6.5a1,1 0 0 1 1,-1z" +
                "M7.5,15a1.5,2.5 0 1 0 3,0a1.5,2.5 0 1 0 -3,0z" +
                "M13.5,15a1.5,2.5 0 1 0 3,0a1.5,2.5 0 1 0 -3,0z",
            evenOdd = true,
        )
    }

    val Watch: ImageVector by lazy {
        icon(
            "Watch",
            "M20,12c0,-2.54 -1.19,-4.81 -3.04,-6.27L16,0H8l-0.95,5.73C5.19,7.19 4,9.45 4,12s1.19,4.81 3.05,6.27L8,24h8l0.96,-5.73C18.81,16.81 20,14.54 20,12zM6,12c0,-3.31 2.69,-6 6,-6s6,2.69 6,6 -2.69,6 -6,6 -6,-2.69 -6,-6z",
        )
    }

    val Cloud: ImageVector by lazy {
        icon(
            "Cloud",
            "M19.35,10.04C18.67,6.59 15.64,4 12,4 9.11,4 6.6,5.64 5.35,8.04 2.34,8.36 0,10.91 0,14c0,3.31 2.69,6 6,6h13c2.76,0 5,-2.24 5,-5 0,-2.64 -2.05,-4.78 -4.65,-4.96z",
        )
    }

    val Upload: ImageVector by lazy {
        icon("Upload", "M5,20h14v-2H5V20zM5,10h4v6h6v-6h4l-7,-7L5,10z")
    }

    val Download: ImageVector by lazy {
        icon("Download", "M19,9h-4V3H9v6H5l7,7L19,9zM5,18v2h14v-2H5z")
    }

    val ExpandMore: ImageVector by lazy {
        icon("ExpandMore", "M16.59,8.59L12,13.17 7.41,8.59 6,10l6,6 6,-6z")
    }

    val ExpandLess: ImageVector by lazy {
        icon("ExpandLess", "M12,8l-6,6 1.41,1.41L12,10.83l4.59,4.58L18,14z")
    }

    val ChevronRight: ImageVector by lazy {
        icon("ChevronRight", "M10,6L8.59,7.41 13.17,12l-4.58,4.59L10,18l6,-6z", autoMirror = true)
    }

    /** Globe (app language). */
    val Language: ImageVector by lazy {
        icon(
            "Language",
            "M11.99,2C6.47,2 2,6.48 2,12s4.47,10 9.99,10C17.52,22 22,17.52 22,12S17.52,2 11.99,2zM18.92,8h-2.95c-0.32,-1.25 -0.78,-2.45 " +
                "-1.38,-3.56 1.84,0.63 3.37,1.91 4.33,3.56zM12,4.04c0.83,1.2 1.48,2.53 1.91,3.96h-3.82c0.43,-1.43 1.08,-2.76 1.91,-3.96zM4.26,14" +
                "C4.1,13.36 4,12.69 4,12s0.1,-1.36 0.26,-2h3.38c-0.08,0.66 -0.14,1.32 -0.14,2 0,0.68 0.06,1.34 0.14,2L4.26,14zM5.08,16h2.95c0.32," +
                "1.25 0.78,2.45 1.38,3.56 -1.84,-0.63 -3.37,-1.9 -4.33,-3.56zM8.03,8L5.08,8c0.96,-1.66 2.49,-2.93 4.33,-3.56C8.81,5.55 8.35,6.75 " +
                "8.03,8zM12,19.96c-0.83,-1.2 -1.48,-2.53 -1.91,-3.96h3.82c-0.43,1.43 -1.08,2.76 -1.91,3.96zM14.34,14L9.66,14c-0.09,-0.66 -0.16," +
                "-1.32 -0.16,-2 0,-0.68 0.07,-1.35 0.16,-2h4.68c0.09,0.65 0.16,1.32 0.16,2 0,0.68 -0.07,1.34 -0.16,2zM14.59,19.56c0.6,-1.11 1.06," +
                "-2.31 1.38,-3.56h2.95c-0.96,1.65 -2.49,2.93 -4.33,3.56zM16.36,14c0.08,-0.66 0.14,-1.32 0.14,-2 0,-0.68 -0.06,-1.34 -0.14,-2h3.38" +
                "c0.16,0.64 0.26,1.31 0.26,2s-0.1,1.36 -0.26,2h-3.38z",
        )
    }

    /** Source code ("< >"). */
    val Code: ImageVector by lazy {
        icon("Code", "M9.4,16.6L4.8,12l4.6,-4.6L8,6l-6,6 6,6 1.4,-1.4zM14.6,16.6l4.6,-4.6 -4.6,-4.6L16,6l6,6 -6,6 -1.4,-1.4z")
    }

    /** Star outline ("star_border"): rate the app (the filled star is "Remove ads"). */
    val StarOutline: ImageVector by lazy {
        icon(
            "StarOutline",
            "M22,9.24l-7.19,-0.62L12,2 9.19,8.63 2,9.24l5.46,4.73L5.82,21 12,17.27 18.18,21l-1.63,-7.03L22,9.24z" +
                "M12,15.4l-3.76,2.27 1,-4.28 -3.32,-2.88 4.38,-0.38L12,6.1l1.71,4.04 4.38,0.38 -3.32,2.88 1,4.28L12,15.4z",
            evenOdd = true,
        )
    }

    val TrendUp: ImageVector by lazy {
        icon("TrendUp", "M16,6l2.29,2.29 -4.88,4.88 -4,-4L2,16.59 3.41,18l6,-6 4,4 6.3,-6.29L22,12V6z", autoMirror = true)
    }

    val TrendDown: ImageVector by lazy {
        icon("TrendDown", "M16,18l2.29,-2.29 -4.88,-4.88 -4,4L2,7.41 3.41,6l6,6 4,-4 6.3,6.29L22,12v6z", autoMirror = true)
    }

    val TrendFlat: ImageVector by lazy {
        icon("TrendFlat", "M22,12l-4,-4v3H3v2h15v3z", autoMirror = true)
    }

    /** Dumbbell ("fitness_center"): the Entreno tab and training entries. */
    val FitnessCenter: ImageVector by lazy {
        icon(
            "FitnessCenter",
            "M20.57,14.86L22,13.43 20.57,12 17,15.57 8.43,7 12,3.43 10.57,2 9.14,3.43 7.71,2 5.57,4.14 4.14,2.71 2.71,4.14l1.43,1.43L2,7.71" +
                "l1.43,1.43L2,10.57 3.43,12 7,8.43 15.57,17 12,20.57 13.43,22l1.43,-1.43L16.29,22l2.14,-2.14 1.43,1.43 1.43,-1.43 -1.43,-1.43L22,16.29z",
        )
    }

    /** Fork and knife ("restaurant"): the Comida tab and meals. */
    val Restaurant: ImageVector by lazy {
        icon(
            "Restaurant",
            "M11,9H9V2H7v7H5V2H3v7c0,2.12 1.66,3.84 3.75,3.97V22h2.5v-9.03C11.34,12.84 13,11.12 13,9V2h-2V9z" +
                "M16,6v8h2.5v8H21V2C18.24,2 16,4.24 16,6z",
        )
    }

    /** A product barcode (bars of different widths). */
    val Barcode: ImageVector by lazy {
        icon("Barcode", "M2,5h2v14H2zM5,5h1v14H5zM7,5h3v14H7zM11,5h1v14h-1zM14,5h2v14h-2zM17,5h1v14h-1zM19,5h3v14h-3z")
    }

    /** Flag ("flag"): the daily food goal. */
    val Flag: ImageVector by lazy {
        icon("Flag", "M14.4,6L14,4H5v17h2v-7h5.6l0.4,2h7V6z")
    }

    val ChevronLeft: ImageVector by lazy {
        icon("ChevronLeft", "M15.41,7.41L14,6l-6,6 6,6 1.41,-1.41L10.83,12z", autoMirror = true)
    }

    /** Line chart with sparkles ("insights"): the Progreso tab. */
    val Insights: ImageVector by lazy {
        icon(
            "Insights",
            "M21,8c-1.45,0 -2.26,1.44 -1.93,2.51l-3.55,3.56c-0.3,-0.09 -0.74,-0.09 -1.04,0l-2.55,-2.55C12.27,10.45 11.46,9 10,9" +
                "c-1.45,0 -2.27,1.44 -1.93,2.52l-4.56,4.55C2.44,15.74 1,16.55 1,18c0,1.1 0.9,2 2,2c1.45,0 2.26,-1.44 1.93,-2.51" +
                "l4.55,-4.56c0.3,0.09 0.74,0.09 1.04,0l2.55,2.55C12.73,16.55 13.54,18 15,18c1.45,0 2.27,-1.44 1.93,-2.52l3.56,-3.55" +
                "C21.56,12.26 23,11.45 23,10C23,8.9 22.1,8 21,8z",
            "M15,9l0.94,-2.07l2.06,-0.93l-2.06,-0.93l-0.94,-2.07l-0.92,2.07l-2.08,0.93l2.08,0.93z",
            "M3.5,11l0.5,-2l2,-0.5l-2,-0.5l-0.5,-2l-0.5,2l-2,0.5l2,0.5z",
        )
    }
}
