package com.enjoyyourtime.globalfitness.ui.training

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.records.ExerciseSessionRecord
import com.enjoyyourtime.globalfitness.training.model.Equipment
import com.enjoyyourtime.globalfitness.training.model.Exercise

/**
 * Glyphs of the equipment and of the cardio machines, one per kind so chips, badges and thumbnails
 * tell them apart (the dumbbell of the Entreno tab is not reused). Material Symbols (Apache-2.0)
 * where one fits: "exercise" (dumbbell), "weight" (kettlebell), "accessibility_new", "ecg_heart",
 * "category", "directions_run", "directions_bike", "nordic_walking", "rowing" and "sports_soccer",
 * filled, scaled to the 24-unit grid; "directions_walk" from Material Icons (also Apache-2.0, already
 * on the 24-unit grid). Barbell, machine, cable, Smith machine, band and stairs are
 * drawn for the app in the same style: filled shapes, 2-unit bars, slightly rounded corners. The
 * icons-extended artifact is not used.
 */
object ExerciseIcons {
    /** Path data of every glyph, by name (also checked by a unit test). */
    internal val paths: Map<String, String> = mapOf(
        "Barbell" to "M1.5,11H22.5A0.5,0.5 0 0 1 23,11.5V12.5A0.5,0.5 0 0 1 22.5,13H1.5A0.5,0.5 0 0 1 1,12.5V11.5" +
            "A0.5,0.5 0 0 1 1.5,11ZM6.5,5H7A1,1 0 0 1 8,6V18A1,1 0 0 1 7,19H6.5A1,1 0 0 1 5.5,18V6A1,1 0 0 1 6.5,5Z" +
            "M3.4,8H3.85A0.9,0.9 0 0 1 4.75,8.9V15.1A0.9,0.9 0 0 1 3.85,16H3.4A0.9,0.9 0 0 1 2.5,15.1V8.9A0.9,0.9 0 " +
            "0 1 3.4,8ZM17,5H17.5A1,1 0 0 1 18.5,6V18A1,1 0 0 1 17.5,19H17A1,1 0 0 1 16,18V6A1,1 0 0 1 17,5Z" +
            "M20.15,8H20.6A0.9,0.9 0 0 1 21.5,8.9V15.1A0.9,0.9 0 0 1 20.6,16H20.15A0.9,0.9 0 0 1 19.25,15.1V8.9" +
            "A0.9,0.9 0 0 1 20.15,8Z",
        "Dumbbell" to "M20.975,9.025L14.925,2.975l0.425,-0.425q0.575,-0.575 1.425,-0.562t1.425,0.588l3.225,3.225" +
            "q0.575,0.575 0.575,1.413T21.425,8.625l-0.45,0.4ZM8.65,21.4q-0.575,0.575 -1.413,0.575T5.825,21.4" +
            "L2.6,18.175q-0.575,-0.575 -0.575,-1.413t0.575,-1.413l0.4,-0.4 6.05,6.05 -0.4,0.4Zm3.625,-0.7q-0.3,0.3 " +
            "-0.7,0.3t-0.7,-0.3L3.3,13.125q-0.3,-0.3 -0.3,-0.7t0.3,-0.7l1.425,-1.45q0.3,-0.3 0.713,-0.3t0.713,0.3" +
            "l1.575,1.575 4.15,-4.15 -1.575,-1.575q-0.3,-0.3 -0.3,-0.7t0.3,-0.7l1.425,-1.45q0.3,-0.3 0.713,-0.3" +
            "t0.713,0.3l7.575,7.575q0.3,0.3 0.3,0.713T20.725,12.275l-1.45,1.425q-0.3,0.3 -0.7,0.3t-0.7,-0.3" +
            "l-1.575,-1.575 -4.15,4.15 1.575,1.575q0.3,0.3 0.3,0.713T13.725,19.275l-1.45,1.425Z",
        "Kettlebell" to "M12,7q0.425,0 0.713,-0.288T13,6q0,-0.425 -0.288,-0.713T12,5q-0.425,0 -0.713,0.288T11,6" +
            "q0,0.425 0.288,0.713T12,7Zm2.825,0h1.75q0.75,0 1.3,0.5t0.675,1.225l1.425,10q0.125,0.9 -0.463,1.588" +
            "T18,21H6q-0.925,0 -1.513,-0.688T4.025,18.725l1.425,-10q0.125,-0.725 0.675,-1.225t1.3,-0.5h1.75" +
            "q-0.075,-0.25 -0.125,-0.488t-0.05,-0.513q0,-1.25 0.875,-2.125t2.125,-0.875q1.25,0 2.125,0.875" +
            "t0.875,2.125q0,0.275 -0.05,0.513t-0.125,0.488Z",
        "Machine" to "M3.5,2H4.5A0.5,0.5 0 0 1 5,2.5V21.5A0.5,0.5 0 0 1 4.5,22H3.5A0.5,0.5 0 0 1 3,21.5V2.5A0.5,0.5 " +
            "0 0 1 3.5,2ZM19.5,2H20.5A0.5,0.5 0 0 1 21,2.5V21.5A0.5,0.5 0 0 1 20.5,22H19.5A0.5,0.5 0 0 1 19,21.5" +
            "V2.5A0.5,0.5 0 0 1 19.5,2ZM3.5,2H20.5A0.5,0.5 0 0 1 21,2.5V3.5A0.5,0.5 0 0 1 20.5,4H3.5A0.5,0.5 0 0 1 " +
            "3,3.5V2.5A0.5,0.5 0 0 1 3.5,2ZM11,3H13V9.5H11ZM7.5,9.5H16.5A0.5,0.5 0 0 1 17,10V10.8A0.5,0.5 0 0 1 " +
            "16.5,11.3H7.5A0.5,0.5 0 0 1 7,10.8V10A0.5,0.5 0 0 1 7.5,9.5ZM7.5,12H16.5A0.5,0.5 0 0 1 17,12.5V13.3" +
            "A0.5,0.5 0 0 1 16.5,13.8H7.5A0.5,0.5 0 0 1 7,13.3V12.5A0.5,0.5 0 0 1 7.5,12ZM7.5,14.5H16.5A0.5,0.5 0 0 " +
            "1 17,15V15.8A0.5,0.5 0 0 1 16.5,16.3H7.5A0.5,0.5 0 0 1 7,15.8V15A0.5,0.5 0 0 1 7.5,14.5ZM7.5,17H16.5" +
            "A0.5,0.5 0 0 1 17,17.5V18.3A0.5,0.5 0 0 1 16.5,18.8H7.5A0.5,0.5 0 0 1 7,18.3V17.5A0.5,0.5 0 0 1 7.5,17" +
            "ZM7.5,19.5H16.5A0.5,0.5 0 0 1 17,20V20.8A0.5,0.5 0 0 1 16.5,21.3H7.5A0.5,0.5 0 0 1 7,20.8V20A0.5,0.5 0 " +
            "0 1 7.5,19.5Z",
        "Cable" to "M9,0.5H11A0.5,0.5 0 0 1 11.5,1V2A0.5,0.5 0 0 1 11,2.5H9A0.5,0.5 0 0 1 8.5,2V1A0.5,0.5 0 0 1 " +
            "9,0.5ZM5,7A5,5 0 1 1 15,7A5,5 0 1 1 5,7ZM8.25,7A1.75,1.75 0 1 0 11.75,7A1.75,1.75 0 1 0 8.25,7ZM13,7" +
            "H15V13.5H13ZM14,12L20.5,22H7.5ZM14,15.7L11.2,20H16.8Z",
        "SmithMachine" to "M6,2H7A0.5,0.5 0 0 1 7.5,2.5V21.5A0.5,0.5 0 0 1 7,22H6A0.5,0.5 0 0 1 5.5,21.5V2.5" +
            "A0.5,0.5 0 0 1 6,2ZM17,2H18A0.5,0.5 0 0 1 18.5,2.5V21.5A0.5,0.5 0 0 1 18,22H17A0.5,0.5 0 0 1 16.5,21.5" +
            "V2.5A0.5,0.5 0 0 1 17,2ZM1.5,9H22.5A0.5,0.5 0 0 1 23,9.5V10.5A0.5,0.5 0 0 1 22.5,11H1.5A0.5,0.5 0 0 1 " +
            "1,10.5V9.5A0.5,0.5 0 0 1 1.5,9ZM2.65,5.5H3.1A0.9,0.9 0 0 1 4,6.4V13.6A0.9,0.9 0 0 1 3.1,14.5H2.65" +
            "A0.9,0.9 0 0 1 1.75,13.6V6.4A0.9,0.9 0 0 1 2.65,5.5ZM20.9,5.5H21.35A0.9,0.9 0 0 1 22.25,6.4V13.6" +
            "A0.9,0.9 0 0 1 21.35,14.5H20.9A0.9,0.9 0 0 1 20,13.6V6.4A0.9,0.9 0 0 1 20.9,5.5ZM3.5,20H20.5A0.5,0.5 0 " +
            "0 1 21,20.5V21.5A0.5,0.5 0 0 1 20.5,22H3.5A0.5,0.5 0 0 1 3,21.5V20.5A0.5,0.5 0 0 1 3.5,20Z",
        "Bodyweight" to "M12,6q-0.825,0 -1.413,-0.588T10,4q0,-0.825 0.588,-1.413T12,2q0.825,0 1.413,0.588T14,4" +
            "q0,0.825 -0.588,1.413T12,6ZM9,22v-13q-1.5,-0.125 -3.05,-0.375t-2.95,-0.625l0.5,-2q1.95,0.525 " +
            "4.15,0.763t4.35,0.238q2.15,0 4.35,-0.238T20.5,6l0.5,2q-1.4,0.375 -2.95,0.625t-3.05,0.375v13h-2v-6h-2v6" +
            "h-2Z",
        "Band" to "M1.5,12.5A10.5,6.5 0 1 1 22.5,12.5A10.5,6.5 0 1 1 1.5,12.5ZM3.5,11A8.5,3.6 0 1 0 20.5,11A8.5,3.6 " +
            "0 1 0 3.5,11Z",
        "Cardio" to "M16.125,3q2.5,0 4.188,1.85T22,9.25q0,0.45 -0.05,0.888t-0.175,0.863H15.525l-1.7,-2.55" +
            "q-0.125,-0.2 -0.35,-0.325t-0.475,-0.125q-0.325,0 -0.588,0.2T12.05,8.7l-1.35,4.05 -0.875,-1.3" +
            "q-0.125,-0.2 -0.35,-0.325t-0.475,-0.125H2.225q-0.125,-0.425 -0.175,-0.863T2,9.275q0,-2.575 " +
            "1.675,-4.425t4.175,-1.85q1.2,0 2.263,0.475t1.888,1.325q0.8,-0.85 1.863,-1.325t2.263,-0.475ZM12,21" +
            "q-0.45,0 -0.863,-0.163T10.4,20.35L3.7,13.625q-0.15,-0.15 -0.275,-0.3t-0.25,-0.325h5.275l1.7,2.55" +
            "q0.125,0.2 0.35,0.325t0.475,0.125q0.325,0 0.6,-0.2t0.375,-0.5l1.35,-4.05 0.85,1.3q0.15,0.2 0.375,0.325" +
            "t0.475,0.125h5.8l-0.25,0.3 -0.25,0.3 -6.725,6.75q-0.325,0.325 -0.725,0.488t-0.85,0.163Z",
        "Other" to "M6.5,11l5.5,-9 5.5,9H6.5ZM17.5,22q-1.875,0 -3.188,-1.312T13,17.5q0,-1.875 1.312,-3.188T17.5,13" +
            "q1.875,0 3.188,1.312T22,17.5q0,1.875 -1.312,3.188T17.5,22Zm-14.5,-0.5v-8h8v8H3Z",
        "Run" to "M13,23v-6l-2.1,-2 -1,4.4 -6.9,-1.4 0.4,-2 4.8,1 1.6,-8.1 -1.8,0.7v3.4h-2v-4.7l3.95,-1.7" +
            "q0.875,-0.375 1.288,-0.488T12,6q0.525,0 0.975,0.275t0.725,0.725l1,1.6q0.65,1.05 1.763,1.725T19,11v2" +
            "q-1.65,0 -3.088,-0.688T13.5,10.5l-0.6,3 2.1,2v7.5h-2Zm0.5,-17.5q-0.825,0 -1.413,-0.588T11.5,3.5" +
            "q0,-0.825 0.588,-1.413T13.5,1.5q0.825,0 1.413,0.588T15.5,3.5q0,0.825 -0.588,1.413T13.5,5.5Z",
        "Bike" to "M15.5,5.5q-0.825,0 -1.413,-0.588T13.5,3.5q0,-0.825 0.588,-1.413T15.5,1.5q0.825,0 1.413,0.588" +
            "T17.5,3.5q0,0.825 -0.588,1.413T15.5,5.5ZM10.8,10.5l2.2,2.3v6.2h-2v-5L7.8,11.2q-0.35,-0.3 -0.475,-0.625" +
            "t-0.125,-0.775q0,-0.45 0.138,-0.763T7.8,8.4l2.8,-2.8q0.325,-0.325 0.688,-0.463T12.1,5q0.45,0 " +
            "0.812,0.138T13.6,5.6l1.9,1.9q0.675,0.675 1.575,1.088t2.025,0.413v2q-1.575,0 -2.85,-0.562T14,8.9" +
            "l-0.8,-0.8 -2.4,2.4Zm-5.8,1.5q2.125,0 3.562,1.438T10,17q0,2.125 -1.438,3.562T5,22q-2.125,0 " +
            "-3.562,-1.438T0,17q0,-2.125 1.438,-3.562T5,12Zm0,8.5q1.425,0 2.463,-1.038T8.5,17q0,-1.425 " +
            "-1.038,-2.463T5,13.5q-1.425,0 -2.463,1.038T1.5,17q0,1.425 1.038,2.463T5,20.5Zm14,-8.5q2.125,0 " +
            "3.562,1.438T24,17q0,2.125 -1.438,3.562T19,22q-2.125,0 -3.562,-1.438T14,17q0,-2.125 1.438,-3.562T19,12Z" +
            "m0,8.5q1.425,0 2.463,-1.038T22.5,17q0,-1.425 -1.038,-2.463T19,13.5q-1.425,0 -2.463,1.038T15.5,17" +
            "q0,1.425 1.038,2.463T19,20.5Z",
        "Elliptical" to "M4,23l2,-9h1.525L5.525,23h-1.525Zm3,0l2.8,-14.1 -1.8,0.7v3.4h-2v-4.7l5.05,-2.15q0.725,-0.3 " +
            "1.475,-0.062t1.175,0.913l1,1.6q0.65,1.05 1.763,1.725T19,11v2q-1.65,0 -3.088,-0.688T13.5,10.5l-0.6,3 " +
            "2.1,2v7.5h-2v-6l-2.1,-2 -1.8,8h-2.1Zm6.5,-17.5q-0.825,0 -1.413,-0.588T11.5,3.5q0,-0.825 0.588,-1.413" +
            "T13.5,1.5q0.825,0 1.413,0.588T15.5,3.5q0,0.825 -0.588,1.413T13.5,5.5ZM17.5,23v-9h1.5v9h-1.5Z",
        "Rowing" to "M18,24L15,21v-1.5L7.9,12.4q-0.225,0.05 -0.45,0.075t-0.45,0.025v-2.2q1.25,0.05 2.55,-0.537" +
            "t2.1,-1.463l1.4,-1.55q0.325,-0.375 0.763,-0.562T14.75,6q0.95,0 1.6,0.65t0.65,1.6v5.75q0,0.65 " +
            "-0.238,1.188T16.1,16.15L12.5,12.6v-2.3q-0.5,0.425 -1.075,0.775t-1.225,0.625l6.3,6.3h1.5l3,3L18,24Z" +
            "M5.5,20.5l-1.5,-1.5 4.5,-4.5 2.5,2.5h-2L5.5,20.5Zm9.5,-15.5q-0.825,0 -1.413,-0.588T13,3q0,-0.825 " +
            "0.588,-1.413T15,1q0.825,0 1.413,0.588T17,3q0,0.825 -0.588,1.413T15,5Z",
        "Stairs" to "M3,21H21V3.5h-4.5v4.375h-4.5v4.375h-4.5v4.375H3Z",
        "Walk" to "M13.5,5.5c1.1,0 2,-0.9 2,-2s-0.9,-2 -2,-2 -2,0.9 -2,2 0.9,2 2,2zM9.8,8.9L7,23h2.1l1.8,-8 2.1,2v6h2v-7.5" +
            "l-2.1,-2 0.6,-3C14.8,12 16.8,13 19,13v-2c-1.9,0 -3.5,-1 -4.3,-2.4l-1,-1.6c-0.4,-0.6 -1,-1 -1.7,-1 -0.3,0 " +
            "-0.5,0.1 -0.8,0.1L6,8.3V13h2V9.6l1.8,-0.7",
        "Sport" to "M12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2ZM13,5.3l1.35,-0.95c1.82,0.56 " +
            "3.37,1.76 4.38,3.34l-0.39,1.34 -1.35,0.46L13,6.7V5.3ZM9.65,4.35L11,5.3v1.4L7.01,9.49l-1.35,-0.46 -0.39,-1.34" +
            "c1.01,-1.57 2.56,-2.77 4.38,-3.34ZM7.08,17.11l-1.14,0.1C4.73,15.81 4,13.99 4,12c0,-0.12 0.01,-0.23 0.02,-0.35" +
            "l1,-0.73 1.38,0.48 1.46,4.34 -0.78,1.37ZM14.5,19.59c-0.79,0.26 -1.63,0.41 -2.5,0.41s-1.71,-0.15 -2.5,-0.41" +
            "l-0.69,-1.49 0.64,-1.1h5.11l0.64,1.11 -0.7,1.48ZM14.27,15H9.73l-1.35,-4.02L12,8.44l3.63,2.54L14.27,15Z" +
            "M18.06,17.21l-1.14,-0.1 -0.79,-1.37 1.46,-4.34 1.39,-0.47 1,0.73c0.01,0.11 0.02,0.22 0.02,0.34 0,1.99 " +
            "-0.73,3.81 -1.94,5.21Z",
    )

    /** [autoMirror]: figures moving forward face the reading direction in right-to-left layouts. */
    private fun icon(name: String, autoMirror: Boolean = false): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
            autoMirror = autoMirror,
        ).apply {
            addPath(pathData = addPathNodes(paths.getValue(name)), fill = SolidColor(Color.Black))
        }.build()

    /** A long bar with two plates on each side. */
    val Barbell: ImageVector by lazy { icon("Barbell") }
    val Dumbbell: ImageVector by lazy { icon("Dumbbell") }
    val Kettlebell: ImageVector by lazy { icon("Kettlebell") }

    /** Selectorized machine: frame, rod and weight stack. */
    val Machine: ImageVector by lazy { icon("Machine") }

    /** Pulley, cable and stirrup handle. */
    val Cable: ImageVector by lazy { icon("Cable") }

    /** Two rails, the bar across them and plates outside. */
    val SmithMachine: ImageVector by lazy { icon("SmithMachine") }
    val Bodyweight: ImageVector by lazy { icon("Bodyweight") }

    /** A loop band seen in perspective. */
    val Band: ImageVector by lazy { icon("Band") }

    /** Heart with a pulse line: cardio machines in general. */
    val Cardio: ImageVector by lazy { icon("Cardio") }

    /** A football: sports and outdoor activities. */
    val Sport: ImageVector by lazy { icon("Sport") }
    val Other: ImageVector by lazy { icon("Other") }

    // One machine each (see cardioIcon).
    val Run: ImageVector by lazy { icon("Run", autoMirror = true) }
    val Bike: ImageVector by lazy { icon("Bike", autoMirror = true) }
    val Elliptical: ImageVector by lazy { icon("Elliptical", autoMirror = true) }
    val Rowing: ImageVector by lazy { icon("Rowing", autoMirror = true) }
    val Stairs: ImageVector by lazy { icon("Stairs", autoMirror = true) }

    /** A walking figure (Material Icons "directions_walk"): walking on a treadmill. */
    val Walk: ImageVector by lazy { icon("Walk", autoMirror = true) }
}

/** The equipment's glyph; every equipment has its own. */
val Equipment.icon: ImageVector
    get() = when (this) {
        Equipment.BARBELL -> ExerciseIcons.Barbell
        Equipment.DUMBBELL -> ExerciseIcons.Dumbbell
        Equipment.KETTLEBELL -> ExerciseIcons.Kettlebell
        Equipment.MACHINE -> ExerciseIcons.Machine
        Equipment.CABLE -> ExerciseIcons.Cable
        Equipment.SMITH_MACHINE -> ExerciseIcons.SmithMachine
        Equipment.BODYWEIGHT -> ExerciseIcons.Bodyweight
        Equipment.BAND -> ExerciseIcons.Band
        Equipment.CARDIO_MACHINE -> ExerciseIcons.Cardio
        Equipment.SPORT -> ExerciseIcons.Sport
        Equipment.OTHER -> ExerciseIcons.Other
    }

/**
 * The glyph of an exercise without muscles: a cardio machine's activity from its Health Connect
 * session type (treadmill, treadmill walk, bikes, elliptical, rower, stair climber), else its
 * equipment's glyph.
 */
val Exercise.activityIcon: ImageVector
    get() = when (hcSession) {
        ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL, ExerciseSessionRecord.EXERCISE_TYPE_RUNNING -> ExerciseIcons.Run
        ExerciseSessionRecord.EXERCISE_TYPE_WALKING -> ExerciseIcons.Walk
        ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY, ExerciseSessionRecord.EXERCISE_TYPE_BIKING -> ExerciseIcons.Bike
        ExerciseSessionRecord.EXERCISE_TYPE_ELLIPTICAL -> ExerciseIcons.Elliptical
        ExerciseSessionRecord.EXERCISE_TYPE_ROWING_MACHINE, ExerciseSessionRecord.EXERCISE_TYPE_ROWING -> ExerciseIcons.Rowing
        ExerciseSessionRecord.EXERCISE_TYPE_STAIR_CLIMBING_MACHINE, ExerciseSessionRecord.EXERCISE_TYPE_STAIR_CLIMBING -> ExerciseIcons.Stairs
        else -> equipment.icon
    }
