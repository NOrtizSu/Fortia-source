package com.enjoyyourtime.globalfitness.ui.training

import com.enjoyyourtime.globalfitness.training.model.ExerciseKind
import com.enjoyyourtime.globalfitness.training.model.LoadUnit
import com.enjoyyourtime.globalfitness.training.model.WorkoutSet
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.Normalizer
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs

/** What the user typed in a numeric set cell. */
sealed interface NumberInput {
    data object Empty : NumberInput

    data object Invalid : NumberInput

    data class Value(val value: Double) : NumberInput
}

/**
 * Locale-aware text for the set table and the training summaries, free of Android resources so it
 * runs in unit tests. Loads are stored in kilograms and shown in the user's [LoadUnit]; numbers use
 * the locale's digits and decimal separator, never grouping (a load is not a thousands figure).
 */
object TrainingFormat {
    /** Highest load accepted in a cell, in the user's unit (a 1000 lb leg press is the limit). */
    const val MAX_LOAD = 1000.0
    const val MAX_REPS = 999
    const val MAX_LEVEL = 99
    const val MAX_KCAL = 9_999
    const val MAX_DISTANCE_KM = 300.0

    /** Longest duration a cell can hold: 99:59:59. */
    const val MAX_DURATION_DIGITS = 6
    const val MAX_DURATION_SEC = 99 * 3600 + 59 * 60 + 59

    private const val TIMES = "×"
    private val RTL_LANGUAGES = setOf("ar", "fa", "he", "iw", "ur")

    // ---- parsing ---------------------------------------------------------------------------

    /**
     * "82,5", "82.5" or the same typed with the locale's own digits ("٨٢٫٥") as an ASCII number
     * string; bidi marks and spaces are dropped.
     */
    fun ascii(text: String): String = buildString {
        for (ch in text.trim()) {
            when {
                ch in '0'..'9' -> append(ch)
                Character.isDigit(ch) -> append('0' + Character.getNumericValue(ch))
                ch == ',' || ch == '٫' || ch == '.' -> append('.')
                Character.isWhitespace(ch) || Character.getType(ch) == Character.FORMAT.toInt() -> Unit
                else -> append(ch)
            }
        }
    }

    /** A non-negative decimal number, or [NumberInput.Empty] / [NumberInput.Invalid]. */
    fun parseNumber(text: String): NumberInput {
        val s = ascii(text)
        if (s.isEmpty()) return NumberInput.Empty
        if (s.count { it == '.' } > 1 || s.any { it != '.' && it !in '0'..'9' }) return NumberInput.Invalid
        val v = (if (s == ".") null else s.toDoubleOrNull()) ?: return NumberInput.Invalid
        return NumberInput.Value(v)
    }

    /** A load typed in [unit], converted to kilograms. Above [MAX_LOAD] it is invalid. */
    fun parseLoad(text: String, unit: LoadUnit): NumberInput = when (val n = parseNumber(text)) {
        is NumberInput.Value -> if (n.value > MAX_LOAD) NumberInput.Invalid else NumberInput.Value(unit.toKg(n.value))
        else -> n
    }

    /** A whole number in 0..[max]. */
    fun parseCount(text: String, max: Int): NumberInput = when (val n = parseNumber(text)) {
        is NumberInput.Value -> if (n.value % 1.0 != 0.0 || n.value > max) NumberInput.Invalid else n
        else -> n
    }

    /** A distance typed in kilometres, as metres. */
    fun parseDistanceKm(text: String): NumberInput = when (val n = parseNumber(text)) {
        is NumberInput.Value -> if (n.value > MAX_DISTANCE_KM) NumberInput.Invalid else NumberInput.Value(n.value * 1000.0)
        else -> n
    }

    // ---- numbers ---------------------------------------------------------------------------

    private fun numberFormat(locale: Locale, maxDecimals: Int, minIntegerDigits: Int = 1): NumberFormat =
        NumberFormat.getNumberInstance(locale).apply {
            isGroupingUsed = false
            minimumFractionDigits = 0
            maximumFractionDigits = maxDecimals
            minimumIntegerDigits = minIntegerDigits
            roundingMode = RoundingMode.HALF_UP
        }

    /** [v] with at most [maxDecimals] decimals and no trailing zeros ("80", "82,5", "1,25"). */
    fun number(v: Double, maxDecimals: Int, locale: Locale): String {
        val rounded = BigDecimal(v).setScale(maxDecimals, RoundingMode.HALF_UP)
        return numberFormat(locale, maxDecimals).format(if (rounded.signum() == 0) BigDecimal.ZERO else rounded)
    }

    fun count(v: Int, locale: Locale): String = numberFormat(locale, 0).format(v.toLong())

    /** "05" (minutes after the hour). */
    fun twoDigits(v: Int, locale: Locale): String = numberFormat(locale, 0, minIntegerDigits = 2).format(v.toLong())

    /** A stored load (kg) in [unit], e.g. 83.91 kg → "185" lb. */
    fun load(kg: Double, unit: LoadUnit, locale: Locale): String = number(unit.fromKg(kg), 2, locale)

    /** An estimate (1RM) in the user's unit with at most one decimal: "72", not "72,01". */
    fun estimate(kg: Double, unit: LoadUnit, locale: Locale): String = number(unit.fromKg(kg), 1, locale)

    /** Kilometres with up to two decimals ("5", "5,25"). */
    fun distanceKm(meters: Double, locale: Locale): String = number(meters / 1000.0, 2, locale)

    /** Whether the cell text still means [kg] (so editing "80," does not get reset to "80"). */
    fun sameLoad(text: String, kg: Double?, unit: LoadUnit): Boolean {
        val parsed = parseLoad(text, unit)
        return when {
            kg == null -> parsed == NumberInput.Empty
            parsed is NumberInput.Value -> abs(parsed.value - kg) < 1e-6
            else -> false
        }
    }

    // ---- durations -------------------------------------------------------------------------

    /**
     * Seconds of the digits typed in a duration cell, read from the right like a microwave timer:
     * "45" = 0:45, "130" = 1:30, "13000" = 1:30:00. Each group may exceed 59 while typing.
     */
    fun digitsToSeconds(digits: String): Int? {
        val d = digitsOnly(digits)
        if (d.isEmpty()) return null
        val n = d.padStart(6, '0').takeLast(6)
        val h = n.substring(0, 2).toInt()
        val m = n.substring(2, 4).toInt()
        val s = n.substring(4, 6).toInt()
        // "999999" would be 100:40:39, which six digits cannot show back.
        return (h * 3600 + m * 60 + s).coerceAtMost(MAX_DURATION_SEC)
    }

    /** The digits a duration cell shows for [seconds] (90 → "130"), "" for null. */
    fun secondsToDigits(seconds: Int?): String {
        if (seconds == null || seconds < 0) return ""
        val total = seconds.coerceAtMost(MAX_DURATION_SEC)
        val h = total / 3600
        val m = total % 3600 / 60
        val s = total % 60
        val text = if (h > 0) "%d%02d%02d".format(Locale.ROOT, h, m, s) else "%d%02d".format(Locale.ROOT, m, s)
        return text.trimStart('0')
    }

    /** ASCII digits of [text] (locale digits converted), leading zeros dropped, at most six. */
    fun digitsOnly(text: String): String =
        ascii(text).filter { it in '0'..'9' }.trimStart('0').take(MAX_DURATION_DIGITS)

    /** The cell text for typed [digits]: "" → "", "5" → "0:05", "130" → "1:30", "13000" → "1:30:00". */
    fun maskDuration(digits: String, locale: Locale): String {
        val d = digitsOnly(digits)
        if (d.isEmpty()) return ""
        val padded = d.padStart(3, '0')
        val two = numberFormat(locale, 0, minIntegerDigits = 2)
        val one = numberFormat(locale, 0)
        val secs = padded.takeLast(2).toLong()
        val rest = padded.dropLast(2)
        return if (rest.length <= 2) {
            "${one.format(rest.toLong())}:${two.format(secs)}"
        } else {
            val mins = rest.takeLast(2).toLong()
            val hours = rest.dropLast(2).toLong()
            "${one.format(hours)}:${two.format(mins)}:${two.format(secs)}"
        }
    }

    /** A duration for display: "0:45", "12:05", "1:02:03". */
    fun duration(seconds: Int, locale: Locale): String {
        val total = seconds.coerceAtLeast(0)
        val two = numberFormat(locale, 0, minIntegerDigits = 2)
        val one = numberFormat(locale, 0)
        val h = total / 3600
        val m = total % 3600 / 60
        val s = total % 60
        return if (h > 0) "${one.format(h.toLong())}:${two.format(m.toLong())}:${two.format(s.toLong())}" else "${one.format(m.toLong())}:${two.format(s.toLong())}"
    }

    // ---- set summaries ---------------------------------------------------------------------

    private fun isRtl(locale: Locale) = locale.language in RTL_LANGUAGES

    /** "+10"; a left-to-right mark keeps the sign before the digits in right-to-left languages. */
    private fun plus(text: String, locale: Locale) = (if (isRtl(locale)) "‎+" else "+") + text

    /** "−30": assistance on an assisted machine reads as weight taken off the body. */
    private fun minus(text: String, locale: Locale) = (if (isRtl(locale)) "‎−" else "−") + text

    private fun hasLoad(set: WorkoutSet) = (set.weightKg ?: 0.0) > 0.0

    /**
     * The compact "last time" text of a set, units implied by the column headers: "80 × 8",
     * "+10 × 8" / "12" (bodyweight), "30 × 8" (assisted: the assistance, as in its column),
     * "20 × 1:30" / "1:30" (timed), "30:00 · 5" (cardio, km). Null when the set has nothing to show.
     */
    fun previous(set: WorkoutSet, kind: ExerciseKind, unit: LoadUnit, locale: Locale): String? {
        val load = set.weightKg?.takeIf { it > 0 }?.let { load(it, unit, locale) }
        val reps = set.reps?.let { count(it, locale) }
        val time = set.durationSec?.takeIf { it > 0 }?.let { duration(it, locale) }
        return when (kind) {
            ExerciseKind.WEIGHT_REPS, ExerciseKind.ASSISTED_REPS -> when {
                load != null && reps != null -> "$load $TIMES $reps"
                reps != null -> reps
                else -> load
            }
            ExerciseKind.BODYWEIGHT_REPS -> when {
                load != null && reps != null -> "${plus(load, locale)} $TIMES $reps"
                reps != null -> reps
                else -> load?.let { plus(it, locale) }
            }
            ExerciseKind.DURATION -> when {
                load != null && time != null -> "$load $TIMES $time"
                else -> time ?: load
            }
            ExerciseKind.CARDIO -> listOfNotNull(time, set.distanceM?.takeIf { it > 0 }?.let { distanceKm(it, locale) })
                .joinToString(" · ")
                .ifEmpty { null }
        }
    }

    /**
     * One line for an exercise's sets in a history list, consecutive sets with the same load grouped:
     * "80 kg × 8, 8, 7 · 85 kg × 5", "12, 10, 9", "+10 kg × 8, 8", "−30 kg × 8" (assistance),
     * "1:00, 0:45", "30:00 · 5 km". [loadWithUnit] / [kmWithUnit] add the unit to an already
     * formatted number.
     */
    fun setsSummary(
        sets: List<WorkoutSet>,
        kind: ExerciseKind,
        unit: LoadUnit,
        locale: Locale,
        loadWithUnit: (String) -> String,
        kmWithUnit: (String) -> String,
    ): String {
        if (sets.isEmpty()) return ""
        if (kind == ExerciseKind.CARDIO) {
            return sets.mapNotNull { s ->
                listOfNotNull(
                    s.durationSec?.takeIf { it > 0 }?.let { duration(it, locale) },
                    s.distanceM?.takeIf { it > 0 }?.let { kmWithUnit(distanceKm(it, locale)) },
                ).joinToString(" · ").ifEmpty { null }
            }.joinToString(", ")
        }
        val groups = mutableListOf<Pair<Double?, MutableList<WorkoutSet>>>()
        for (s in sets) {
            val key = s.weightKg?.takeIf { it > 0 }?.let { unit.fromKg(it) }?.let { BigDecimal(it).setScale(2, RoundingMode.HALF_UP).toDouble() }
            val last = groups.lastOrNull()
            if (last != null && last.first == key) last.second += s else groups += key to mutableListOf(s)
        }
        return groups.joinToString(" · ") { (loadValue, list) ->
            val values = list.mapNotNull { s ->
                when (kind) {
                    ExerciseKind.DURATION -> s.durationSec?.let { duration(it, locale) }
                    else -> s.reps?.let { count(it, locale) }
                }
            }.joinToString(", ")
            val loadText = loadValue?.let { loadWithUnit(number(it, 2, locale)) }?.let {
                when (kind) {
                    ExerciseKind.BODYWEIGHT_REPS -> plus(it, locale)
                    ExerciseKind.ASSISTED_REPS -> minus(it, locale)
                    else -> it
                }
            }
            when {
                loadText != null && values.isNotEmpty() -> "$loadText $TIMES $values"
                loadText != null -> loadText
                else -> values
            }
        }
    }

    // ---- search ----------------------------------------------------------------------------

    /** Lower case without accents, so "jalon" finds "Jalón" and "PRESS" finds "press". */
    fun normalize(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(DIACRITICS, "")
            .lowercase(Locale.ROOT)

    /** The words of a search query, normalized. */
    fun searchTokens(query: String): List<String> = normalize(query).split(SEPARATORS).filter { it.isNotEmpty() }

    /**
     * Every token is found in [normalizedHaystack] (name, equipment, muscles and search words,
     * normalized; see [tokenMatches]).
     */
    fun matches(tokens: List<String>, normalizedHaystack: String): Boolean = tokens.all { tokenMatches(it, normalizedHaystack) }

    /**
     * Whether one search token is found in [haystack]:
     * - anywhere, as typed ("jalon" in "jalon al pecho", "curl" in "hammer curl");
     * - except that a token of one or two letters of an alphabet with spaces between words (Latin,
     *   Greek, Cyrillic) only counts at the start of a word, so "a" or "de" do not match most of the
     *   catalog (Chinese, Japanese, Korean, Arabic or Hindi tokens keep matching anywhere);
     * - or, failing that, its [stem] at the start of a word: "caminar" finds "caminata", "rows"
     *   finds "row", "sentadillas" finds "sentadilla", "walking" finds "walk".
     */
    fun tokenMatches(token: String, haystack: String): Boolean {
        val spaced = token.all { it.code < SPACED_SCRIPTS_END }
        if (spaced && token.length <= SHORT_TOKEN) return startsWord(token, haystack)
        if (haystack.contains(token)) return true
        val stem = if (spaced) stem(token) else token
        return stem != token && startsWord(stem, haystack)
    }

    /**
     * [token] without one final Spanish or English verb or plural ending, the first of [ENDINGS] it
     * has, as long as enough is left: at least four letters after a verb ending ("caminar" →
     * "camin", but "andar" stays, its stem "and" would find "clean and press"), three after a plural
     * one ("rows" → "row"). Tokens shorter than four letters are kept as they are.
     */
    fun stem(token: String): String {
        if (token.length < 4) return token
        for ((ending, keep) in ENDINGS) {
            if (token.endsWith(ending) && token.length - ending.length >= keep) return token.dropLast(ending.length)
        }
        return token
    }

    /** Whether [part] occurs in [haystack] at the start of a word (after a character that is not a letter or digit). */
    private fun startsWord(part: String, haystack: String): Boolean {
        var i = haystack.indexOf(part)
        while (i >= 0) {
            if (i == 0 || !haystack[i - 1].isLetterOrDigit()) return true
            i = haystack.indexOf(part, i + 1)
        }
        return false
    }

    /** Up to this many letters a token must start a word. */
    private const val SHORT_TOKEN = 2

    /** Code points below this are Latin, Greek and Cyrillic, written with spaces between words. */
    private const val SPACED_SCRIPTS_END = 0x0530

    /** Endings [stem] removes, longest first, with the letters that must remain. */
    private val ENDINGS = listOf("iendo" to 4, "ando" to 4, "ing" to 4, "ar" to 4, "er" to 4, "ir" to 4, "es" to 3, "s" to 3)

    private val DIACRITICS = Regex("\\p{Mn}+")
    private val SEPARATORS = Regex("[\\s,;·/-]+")
}
