import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.protobuf)
    alias(libs.plugins.google.services)
}

// AdMob ids. Debug builds always use Google's official TEST ids. Release builds read the real ids
// from Gradle properties (-PADMOB_APP_ID=... or ~/.gradle/gradle.properties) or from the untracked
// local.properties, and fall back to the test ids with a warning.
val admobTestIds = mapOf(
    "ADMOB_APP_ID" to "ca-app-pub-3940256099942544~3347511713",
    "ADMOB_BANNER_ID" to "ca-app-pub-3940256099942544/9214589741",
    "ADMOB_INTERSTITIAL_ID" to "ca-app-pub-3940256099942544/1033173712",
)
val localProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
}
val onlyDebugTasks = gradle.startParameter.taskNames.let { tasks ->
    tasks.isNotEmpty() && tasks.all { it.contains("debug", ignoreCase = true) }
}

fun releaseAdmobId(key: String): String {
    val configured = (providers.gradleProperty(key).orNull ?: localProperties.getProperty(key))
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
    if (configured != null) return configured
    if (!onlyDebugTasks) {
        logger.warn("WARNING: $key is not set (Gradle property or local.properties); the release build uses the AdMob TEST id.")
    }
    return admobTestIds.getValue(key)
}

// Release signing with the Play upload key. The keystore and its passwords never live in the repo: they
// are Gradle properties (~/.gradle/gradle.properties, see docs/play-release.md). Without them the
// release build is left unsigned.
val uploadKey = listOf(
    "GLOBALFITNESS_UPLOAD_STORE_FILE",
    "GLOBALFITNESS_UPLOAD_STORE_PASSWORD",
    "GLOBALFITNESS_UPLOAD_KEY_ALIAS",
    "GLOBALFITNESS_UPLOAD_KEY_PASSWORD",
).associateWith { key -> providers.gradleProperty(key).orNull?.trim()?.takeIf { it.isNotEmpty() } }
val uploadStoreFile = uploadKey["GLOBALFITNESS_UPLOAD_STORE_FILE"]?.let { file(it) }
val hasUploadKey = uploadKey.values.all { it != null } && uploadStoreFile?.isFile == true
if (!hasUploadKey && !onlyDebugTasks) {
    logger.warn("WARNING: the GLOBALFITNESS_UPLOAD_* signing properties are missing or the keystore does not exist; the release build is UNSIGNED.")
}

// versionCode = 1000 + build number (gradle.properties); bump the build number for every Play upload.
val appVersionName = providers.gradleProperty("globalfitness.versionName").get()
val appBuildNumber = providers.gradleProperty("globalfitness.buildNumber").get().toInt()

android {
    namespace = "com.enjoyyourtime.globalfitness"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.enjoyyourtime.globalfitness"
        minSdk = 26
        targetSdk = 36
        versionCode = 1000 + appBuildNumber
        versionName = appVersionName
    }

    signingConfigs {
        if (hasUploadKey) {
            create("release") {
                storeFile = uploadStoreFile
                storePassword = uploadKey.getValue("GLOBALFITNESS_UPLOAD_STORE_PASSWORD")
                keyAlias = uploadKey.getValue("GLOBALFITNESS_UPLOAD_KEY_ALIAS")
                keyPassword = uploadKey.getValue("GLOBALFITNESS_UPLOAD_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            manifestPlaceholders["admobAppId"] = admobTestIds.getValue("ADMOB_APP_ID")
            buildConfigField("String", "ADMOB_BANNER_ID", "\"${admobTestIds.getValue("ADMOB_BANNER_ID")}\"")
            buildConfigField("String", "ADMOB_INTERSTITIAL_ID", "\"${admobTestIds.getValue("ADMOB_INTERSTITIAL_ID")}\"")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
            manifestPlaceholders["admobAppId"] = releaseAdmobId("ADMOB_APP_ID")
            buildConfigField("String", "ADMOB_BANNER_ID", "\"${releaseAdmobId("ADMOB_BANNER_ID")}\"")
            buildConfigField("String", "ADMOB_INTERSTITIAL_ID", "\"${releaseAdmobId("ADMOB_INTERSTITIAL_ID")}\"")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    // The food recognizer is memory-mapped from the APK: it must be stored uncompressed.
    androidResources {
        noCompress += "tflite"
    }

    // Users can pick the app language in the system settings (Android 13+, res/xml/locales_config.xml):
    // keep every language in the base APK so the chosen one is always installed.
    bundle {
        language {
            enableSplit = false
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// Xiaomi band protocol messages (src/main/proto), generated as protobuf-javalite classes.
protobuf {
    protoc {
        artifact = libs.protobuf.protoc.get().toString()
    }
    generateProtoTasks {
        all().configureEach {
            builtins {
                create("java") {
                    option("lite")
                }
            }
        }
    }
}

/**
 * Ships the privacy policy as app assets, shown in Profile → Privacy policy: docs/privacy-policy.md
 * (Spanish) and docs/privacy-policy.en.md (English, for every other language), same file names.
 */
abstract class PrivacyPolicyAssetTask : DefaultTask() {
    @get:InputFiles
    abstract val policies: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val out = outputDir.get().asFile
        out.deleteRecursively()
        out.mkdirs()
        policies.files.forEach { it.copyTo(out.resolve(it.name)) }
    }
}

val privacyPolicyAssets = tasks.register<PrivacyPolicyAssetTask>("privacyPolicyAssets") {
    policies.from(rootProject.layout.projectDirectory.files("docs/privacy-policy.md", "docs/privacy-policy.en.md"))
    outputDir.set(layout.buildDirectory.dir("generated/privacyPolicyAssets"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(privacyPolicyAssets, PrivacyPolicyAssetTask::outputDir)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.navigation.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.protobuf.javalite)
    implementation(libs.androidx.health.connect.client)
    // Optional Google account + cloud backup, ads (with GDPR consent) and the "remove ads" purchase.
    implementation(libs.androidx.lifecycle.process)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    implementation(libs.firebase.firestore)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services.auth)
    implementation(libs.googleid)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.play.services.ads)
    implementation(libs.ump)
    implementation(libs.billing.ktx)
    // Asks for a Google Play rating with Google's own card (no screen of ours).
    implementation(libs.play.review)
    // Comida: the food recognizer runs on the phone (LiteRT, no Play services) and barcodes are read
    // from a photo (ZXing core, no camera preview).
    implementation(libs.litert)
    implementation(libs.zxing.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.sqlite.jdbc)
}
