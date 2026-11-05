@file:OptIn(ExperimentalMaterial3Api::class)

package com.enjoyyourtime.globalfitness.ui.profile

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.enjoyyourtime.globalfitness.R
import com.enjoyyourtime.globalfitness.ui.Fmt
import com.enjoyyourtime.globalfitness.ui.components.AppCard
import com.enjoyyourtime.globalfitness.ui.components.TopBarTitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Bundled from docs/privacy-policy.md (Spanish) and docs/privacy-policy.en.md (English, for every
 * other language) at build time (see app/build.gradle.kts).
 */
private const val POLICY_ASSET_ES = "privacy-policy.md"
private const val POLICY_ASSET_EN = "privacy-policy.en.md"

/** Public privacy policy (Firebase Hosting, hosting/public/privacy.html); the assets are its offline copies. */
const val PRIVACY_POLICY_URL = "https://globalfitness-app.web.app/privacy.html"

/** Language of the web version of [PRIVACY_POLICY_URL]. */
const val PRIVACY_POLICY_WEB_LANGUAGE = "es"

/** Corresponding Source of the app (AGPL-3.0 sections 6 and 13). */
const val SOURCE_CODE_URL = "https://github.com/NOrtizSu/Fortia-source"
private const val LICENSE_URL = "https://www.gnu.org/licenses/agpl-3.0.html"

private sealed interface PolicyState {
    data object Loading : PolicyState
    data class Loaded(val blocks: List<MdBlock>) : PolicyState
    data object Failed : PolicyState
}

/** The privacy policy rendered in-app: the offline copy of [PRIVACY_POLICY_URL]. */
@Composable
fun PrivacyPolicyScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val asset = if (Fmt.locale.language == "es") POLICY_ASSET_ES else POLICY_ASSET_EN
    val policy by produceState<PolicyState>(PolicyState.Loading, asset) {
        value = withContext(Dispatchers.IO) {
            runCatching { context.assets.open(asset).bufferedReader().use { it.readText() } }
                .map { PolicyState.Loaded(MarkdownLite.parse(it)) }
                .getOrDefault(PolicyState.Failed)
        }
    }
    SubPage(title = stringResource(R.string.privacy_policy_title), onBack = onBack) { padding ->
        when (val p = policy) {
            PolicyState.Loading -> Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            PolicyState.Failed -> Text(
                stringResource(R.string.privacy_policy_open_failed),
                modifier = Modifier.padding(padding).padding(16.dp),
            )
            is PolicyState.Loaded -> LazyColumn(contentPadding = PaddingValues(16.dp), modifier = Modifier.padding(padding)) {
                item { MarkdownDocument(p.blocks) }
            }
        }
    }
}

/** [name] and [license] are names (not translated) unless [nameRes] / [licenseRes] are set. */
private data class Notice(
    val name: String,
    val license: String,
    @param:StringRes val text: Int,
    val url: String,
    @param:StringRes val nameRes: Int? = null,
    @param:StringRes val licenseRes: Int? = null,
)

private val PORTED = listOf(
    Notice(
        name = "openScale",
        license = "GPL-3.0",
        text = R.string.notice_openscale,
        url = "https://github.com/oliexdev/openScale",
    ),
    Notice(
        name = "Gadgetbridge",
        license = "AGPL-3.0",
        text = R.string.notice_gadgetbridge,
        url = "https://codeberg.org/Freeyourgadget/Gadgetbridge",
    ),
    Notice(
        name = "react-native-body-highlighter",
        license = "MIT",
        text = R.string.notice_body_highlighter,
        url = "https://github.com/HichamELBSI/react-native-body-highlighter",
    ),
)

private val LIBRARIES = listOf(
    Notice(
        "AndroidX, Jetpack Compose, Material 3",
        "Apache-2.0",
        R.string.notice_androidx,
        "https://developer.android.com/jetpack/androidx",
        nameRes = R.string.notice_androidx_name,
    ),
    Notice("Health Connect (connect-client)", "Apache-2.0", R.string.notice_health_connect, "https://developer.android.com/health-and-fitness/guides/health-connect"),
    Notice("Kotlin, kotlinx.coroutines", "Apache-2.0", R.string.notice_kotlin, "https://kotlinlang.org", nameRes = R.string.notice_kotlin_name),
    Notice("Protocol Buffers (javalite)", "BSD-3-Clause", R.string.notice_protobuf, "https://github.com/protocolbuffers/protobuf"),
    Notice(
        "Firebase, Google Mobile Ads, UMP, Play Billing",
        "",
        R.string.notice_google,
        "https://developers.google.com/terms",
        nameRes = R.string.notice_google_name,
        licenseRes = R.string.notice_google_terms,
    ),
    Notice(
        "Google Play In-App Review",
        "",
        R.string.notice_play_review,
        "https://developer.android.com/guide/playcore/in-app-review",
        licenseRes = R.string.notice_google_terms,
    ),
    Notice("LiteRT (TensorFlow Lite)", "Apache-2.0", R.string.notice_litert, "https://ai.google.dev/edge/litert"),
    Notice("ZXing", "Apache-2.0", R.string.notice_zxing, "https://github.com/zxing/zxing"),
    Notice(
        "Google ML Kit (text recognition)",
        "",
        R.string.notice_mlkit,
        "https://developers.google.com/ml-kit/terms",
        licenseRes = R.string.notice_google_terms,
    ),
)

/** The food data and the food recognizer: their sources and licences (attribution required by most). */
private val FOOD_DATA = listOf(
    // The data (ODbL) and the product photos shown in the food log (CC BY-SA, by the same contributors).
    Notice("Open Food Facts", "ODbL 1.0 · CC BY-SA 3.0", R.string.notice_open_food_facts, "https://world.openfoodfacts.org"),
    Notice("USDA FoodData Central", "CC0 1.0", R.string.notice_usda, "https://fdc.nal.usda.gov"),
    Notice("Ciqual (Anses)", "Licence Ouverte / Etalab 2.0", R.string.notice_ciqual, "https://ciqual.anses.fr"),
    Notice(
        "Fortia food model",
        "",
        R.string.notice_food_model,
        "https://github.com/NOrtizSu/Fortia-source/tree/main/docs/third-party/food-model",
        nameRes = R.string.notice_food_model_name,
        licenseRes = R.string.notice_food_model_license,
    ),
)

/** Licence and source code (AGPL-3.0 "Appropriate Legal Notices"), ported code, the libraries, then the food data. */
@Composable
fun OpenSourceScreen(onBack: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    SubPage(title = stringResource(R.string.open_source_title), onBack = onBack) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(padding),
        ) {
            item {
                AppCard(containerColor = MaterialTheme.colorScheme.primaryContainer) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.open_source_free_software), style = MaterialTheme.typography.titleMedium)
                        Text(
                            stringResource(R.string.open_source_license_text),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Button(onClick = { runCatching { uriHandler.openUri(SOURCE_CODE_URL) } }, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.open_source_view_code))
                        }
                        OutlinedButton(onClick = { runCatching { uriHandler.openUri(LICENSE_URL) } }, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.open_source_read_license))
                        }
                    }
                }
            }
            item {
                Text(
                    stringResource(R.string.open_source_ported_intro),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            items(PORTED) { NoticeItem(it, onOpen = { runCatching { uriHandler.openUri(it.url) } }) }
            item {
                Text(stringResource(R.string.open_source_libraries), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
            }
            items(LIBRARIES) { NoticeItem(it, onOpen = { runCatching { uriHandler.openUri(it.url) } }) }
            item {
                Text(stringResource(R.string.open_source_food_data), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
            }
            items(FOOD_DATA) { NoticeItem(it, onOpen = { runCatching { uriHandler.openUri(it.url) } }) }
        }
    }
}

@Composable
private fun NoticeItem(notice: Notice, onOpen: () -> Unit) {
    AppCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(notice.nameRes?.let { stringResource(it) } ?: notice.name, style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.open_source_license_label, notice.licenseRes?.let { stringResource(it) } ?: notice.license),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(stringResource(notice.text), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = onOpen, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.open_source_open_website)) }
        }
    }
}

/** Secondary page with a back arrow (no bottom bar, no ads). */
@Composable
fun SubPage(title: String, onBack: () -> Unit, content: @Composable (PaddingValues) -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { TopBarTitle(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back)) }
                },
            )
        },
        content = content,
    )
}
