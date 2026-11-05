package com.enjoyyourtime.globalfitness.food.pack

import com.enjoyyourtime.globalfitness.food.recognition.Recognition
import java.text.Normalizer

/**
 * One line of text read on the photo: its words, where it starts and how tall it is (both as shares of
 * the photo's height), and how sure the recognizer is of it (0..1).
 */
data class TextLine(val text: String, val top: Float, val height: Float, val confidence: Float = 1f)

/**
 * From the text read on a package to the words searched in Open Food Facts: the brand and the product
 * name are the biggest text on the front, so the most prominent lines are kept and everything else
 * goes (the nutrition table, ingredients, legal and storage text, quantities, codes, units, small
 * words; nutrient words unless the name says them: "High Protein"). Pure (unit-tested with real
 * package texts).
 */
object PackageText {
    /** At most this many words are searched. */
    const val MAX_WORDS = 5

    /** A line at least this share of the tallest line's height is part of the package's name. */
    private const val PROMINENT = 0.4f

    /** Smaller lines are added while fewer words than this were found (a small brand above a big name). */
    private const val MIN_WORDS = 3

    /** The top food under this confidence is a weak guess (the recognizer's "possible"). */
    const val LOW_CONFIDENCE = 0.2f

    /** A word: letters (with their combining marks: Devanagari vowel signs) and digits, with ' & . - inside. */
    private val WORD = Regex("[\\p{L}\\p{N}](?:[\\p{L}\\p{M}\\p{N}'’&.\\-]*[\\p{L}\\p{M}\\p{N}])?")

    /** A number, maybe with a unit ("330ml", "4x120g", "1,5l"); "7up" is a name. */
    private val QUANTITY = Regex("^\\d+(?:[.,]\\d+)?(?:x\\d+(?:[.,]\\d+)?)?(?:g|gr|grs|kg|mg|ml|cl|dl|l|lt|oz|lb|kcal|kj|ud|uds|u|x)?$")
    private val MARKS = Regex("\\p{Mn}+")

    /**
     * Phrases (accents dropped, lower case, whole words) that make a line small print, not the product's
     * name: the nutrition table, ingredients, allergens, dates, storage, the maker, the net weight. Whole
     * phrases, so brands such as "Optimum Nutrition" stay.
     */
    private val SMALL_PRINT = Regex(
        "\\b(?:" + listOf(
            "valor energetico", "valores nutricionales", "informacion nutricional", "informacao nutricional", "nutrition facts",
            "nutrition information", "nutritional information", "nutrition declaration", "valeurs nutritionnelles",
            "nahrwertangaben", "nahrwerte", "valori nutrizionali", "wartosc odzywcza", "besin degerleri", "informasi nilai gizi",
            "ingredientes", "ingredients", "ingredienti", "ingredienser", "zutaten", "skladniki", "icindekiler", "komposisi",
            "alergenos", "allergens", "puede contener", "may contain", "peut contenir", "kann spuren", "consumir preferentemente",
            "consumir antes", "consumase", "best before", "use by", "a consommer", "mindestens haltbar", "da consumarsi",
            "fecha de caducidad", "caducidad", "lote", "lot", "conservar", "conservese", "store in", "keep in", "distribuido",
            "fabricado", "elaborado", "envasado", "made in", "produced", "product of", "producido", "atencion al cliente",
            "s\\.a", "s\\.l", "ltd", "gmbh", "recicla\\w*", "recycl\\w*", "registro sanitario", "rgseaa", "peso neto",
            "net weight", "net wt", "poids net", "por 100", "per 100", "pour 100", "pro 100", "por racion", "per serving",
            "por porcion", "ingesta de referencia", "reference intake", "cdr",
        ).joinToString("|") + ")\\b",
    )

    /** Small print found anywhere in a line (addresses, contact). */
    private val SMALL_PRINT_MARKS = listOf("www", "http", ".com", "@", "% ir")

    /** Nutrients: the nutrition table's words, kept only on a prominent line (a name: "Zero Azúcar", "High Protein"). */
    private val NUTRIENTS = setOf(
        "energia", "energy", "energie", "grasas", "grasa", "fat", "fats", "saturadas", "saturated", "saturates", "hidratos",
        "carbono", "carbohidratos", "carbohydrate", "carbohydrates", "azucares", "azucar", "sugars", "sugar", "proteinas",
        "proteina", "protein", "proteins", "proteine", "eiweiss", "sal", "salt", "sel", "fibra", "fibre", "fiber", "sodio",
        "sodium", "valor", "valores",
    )

    /** Words that never name a product: units, small words of the languages packages use, offers. */
    private val STOP = setOf(
        // units
        "kcal", "kj", "g", "gr", "grs", "kg", "mg", "ml", "cl", "dl", "l", "lt", "oz", "lb", "ud", "uds", "unidades", "units", "x",
        // small words (es, en, fr, de, it, pt, tr, pl, id, vi)
        "de", "del", "la", "las", "el", "los", "lo", "y", "e", "o", "u", "con", "sin", "en", "a", "al", "para", "por",
        "un", "una", "the", "and", "or", "with", "without", "of", "in", "for", "an", "to", "le", "les", "des", "du", "et",
        "avec", "sans", "au", "aux", "pour", "der", "die", "das", "und", "mit", "ohne", "von", "fur", "il", "gli", "di",
        "da", "per", "do", "dos", "com", "sem", "em", "ve", "ile", "bir", "icin", "bu", "na", "bez", "dla", "oraz", "ze",
        "dan", "dengan", "tanpa", "untuk", "yang", "va", "voi", "khong", "cho", "cua",
        // offers
        "nuevo", "nueva", "new", "nouveau", "nouvelle", "neu", "neue", "nuovo", "novo", "nova", "oferta", "promo",
        "promocion", "gratis", "pack", "formato", "ahorro",
    )

    /**
     * The words to search for the product in [lines] (in reading order, at most [MAX_WORDS], each once),
     * or null when no word of three letters or more is left. Lines the recognizer is unsure of (a logo in
     * a script font read as "Sechera") are left out while surer ones give a search.
     */
    fun query(lines: List<TextLine>): String? =
        wordsOf(lines.filter { it.confidence >= MIN_LINE_CONFIDENCE }) ?: wordsOf(lines)

    /** Below this the recognizer's line is a guess (seen on the emulator: logos 0.3-0.59, clear print 0.75-0.8). */
    const val MIN_LINE_CONFIDENCE = 0.6f

    private fun wordsOf(lines: List<TextLine>): String? {
        val candidates = lines.filter { !smallPrint(it.text) }.map { it to words(it.text) }.filter { it.second.isNotEmpty() }
        if (candidates.isEmpty()) return null
        val tallest = candidates.maxOf { it.first.height }
        // Nutrient words only where the name says them (a prominent line).
        val kept = candidates.map { (line, words) ->
            val prominent = line.height >= tallest * PROMINENT
            Triple(line, if (prominent) words else words.filterNot { key(it) in NUTRIENTS }, prominent)
        }.filter { it.second.isNotEmpty() }
        // The prominent lines first, tallest first; smaller ones only while fewer than MIN_WORDS words were found.
        val chosen = ArrayList<Pair<TextLine, List<String>>>()
        var count = 0
        for ((line, words, prominent) in kept.sortedByDescending { it.first.height }) {
            if (!prominent && count >= MIN_WORDS) break
            chosen += line to words
            count += words.size
            if (count >= MAX_WORDS) break
        }
        val seen = HashSet<String>()
        val out = chosen.sortedBy { it.first.top }.flatMap { it.second }.filter { seen.add(key(it)) }.take(MAX_WORDS)
        if (out.none { letters(it) >= 3 }) return null
        return out.joinToString(" ")
    }

    /**
     * Fewer words to search when [query] found nothing (a word misread, "María" in a script font read as
     * "1aria"): its words of letters only, else its longest word; null when there is nothing broader.
     */
    fun broader(query: String): String? {
        val words = query.split(' ').filter { it.isNotEmpty() }
        if (words.size < 2) return null
        val plain = words.filter { w -> w.none(Char::isDigit) && letters(w) >= 3 }
        val out = if (plain.isNotEmpty() && plain.size < words.size) plain else listOf(words.maxBy(::letters))
        return out.joinToString(" ").takeIf { out.any { letters(it) >= 3 } }
    }

    /**
     * Whether the photo is read again with the Latin recognizer: the app's language has another script's
     * recognizer ([script]), it read some text ([lines]: a photo without text is not read twice), and its
     * words ([query]) are none or have no letter of its own script (packages in Japan or India often carry
     * their brand in Latin letters, which the Latin recognizer reads better: "nutella", not "nutelld").
     * The Latin reading's [latinQuery] words are then used when it gives any.
     */
    fun needsLatin(script: TextScript, lines: List<TextLine>, query: String?): Boolean =
        script != TextScript.LATIN && lines.isNotEmpty() && (query == null || query.none(script::owns))

    /** The words of the Latin reading done by [needsLatin]: only from the lines it is sure of (null: none). */
    fun latinQuery(lines: List<TextLine>): String? = wordsOf(lines.filter { it.confidence >= MIN_LINE_CONFIDENCE })

    /** Longest words typed in the search field that are searched. */
    const val MAX_TYPED = 80

    /** The words the user typed or corrected, as searched (spaces tidied, at most [MAX_TYPED] characters), or null when there is nothing to search. */
    fun typed(input: String): String? {
        val words = input.replace(Regex("\\s+"), " ").trim().take(MAX_TYPED).trim()
        return words.takeIf { w -> w.any(Char::isLetterOrDigit) }
    }

    /** Enough text to search on its own (the photo may then be searched without asking): two words, or one of five letters. */
    fun enough(query: String?): Boolean {
        val words = query?.split(' ')?.filter { it.isNotEmpty() }.orEmpty()
        return words.size >= 2 || words.any { letters(it) >= 5 }
    }

    /**
     * The recognizer saw no food, or only a weak guess (or there is no model): the photo may be a package,
     * so its text is read by itself (and searched when [enough] words were read).
     */
    fun weakGuess(recognition: Recognition?): Boolean {
        val top = recognition?.takeIf { !it.notFood }?.suggestions?.firstOrNull()?.confidence ?: return true
        return top < LOW_CONFIDENCE
    }

    /** The words of [text] that can name a product (lower case; nutrient words included). */
    internal fun words(text: String): List<String> = WORD.findAll(text).map { it.value }.filter(::keeps).map { it.lowercase() }.toList()

    private fun keeps(word: String): Boolean {
        if (letters(word) < 2) return false
        val k = key(word)
        if (k in STOP || QUANTITY.matches(k)) return false
        // A code, a date, a lot number: more digits than letters.
        return word.count(Char::isDigit) <= letters(word)
    }

    private fun smallPrint(text: String): Boolean {
        val k = key(text)
        return SMALL_PRINT.containsMatchIn(k) || SMALL_PRINT_MARKS.any { k.contains(it) }
    }

    private fun letters(word: String): Int = word.count(Char::isLetter)

    /** Lower case without accents: how words are compared. */
    private fun key(s: String): String = MARKS.replace(Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD), "")
}
