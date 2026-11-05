package com.enjoyyourtime.globalfitness.ui.food

import com.enjoyyourtime.globalfitness.food.pack.PackFind
import com.enjoyyourtime.globalfitness.food.pack.PackageText
import com.enjoyyourtime.globalfitness.food.recognition.Recognition

/** When the photo page looks for a packaged product by itself (pure, unit-tested; the view model follows it). */
object PhotoPackRules {
    /**
     * The barcode read on the photo found its product ([BarcodeState.Found]): it is the answer, the photo
     * is neither compared with the scanned products nor searched by its words. A barcode that was not
     * found (unknown product, offline) leaves both ways open.
     */
    fun barcodeDecides(barcode: BarcodeState?): Boolean = barcode is BarcodeState.Found

    /**
     * The product found on the photo's barcode opens by itself unless the user already left the photo
     * page (chose a food, a product or the search, or the page was covered after it showed). It opens
     * once the page is in front (resumed): a lookup that ends while the page is still coming in waits.
     */
    fun opensBarcodePage(barcode: BarcodeState, pageLeft: Boolean): Boolean = barcode is BarcodeState.Found && !pageLeft

    /**
     * The photo's words are read by themselves (and searched when enough are read): one food photo (not a
     * plate), no product found by its barcode nor matched among the scanned ones, the recognizer sees no
     * food or only a weak guess, packages can be read in the app's language, and the user did not leave
     * the photo page.
     */
    fun readsByItself(
        plate: Boolean,
        barcode: BarcodeState?,
        find: PackFind?,
        recognition: Recognition?,
        readable: Boolean,
        pageLeft: Boolean,
    ): Boolean = !plate && !barcodeDecides(barcode) && find !is PackFind.Match && PackageText.weakGuess(recognition) && readable && !pageLeft

    /** The words read are searched: by itself only with [PackageText.enough] words, when asked with any word. */
    fun searches(words: String?, auto: Boolean): Boolean = if (auto) PackageText.enough(words) else !words.isNullOrEmpty()

    /**
     * What the editable search field starts with for [text]: the words searched (to correct them and search
     * again), empty when no usable words were read (the user types the brand and name instead of a dead
     * end), null while nothing is shown or the package is still being read.
     */
    fun wordsField(text: PackText): String? = when (text) {
        PackText.Idle, PackText.Reading -> null
        is PackText.Searching -> text.query
        is PackText.Results -> text.query
        is PackText.NotFound -> text.query
        is PackText.Offline -> text.query
        is PackText.Busy -> text.query
        is PackText.Failed -> text.query
        PackText.NoText, PackText.ReadFailed, is PackText.Unavailable -> ""
    }

    /**
     * What the search field shows when the words searched change to [start] (a new search, or a search
     * retried with fewer words): those words, unless the user is editing the field ([edited]: their
     * typing stays, [current]).
     */
    fun fieldAfter(current: String, edited: Boolean, start: String): String = if (edited) current else start
}
