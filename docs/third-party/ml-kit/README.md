# Google ML Kit text recognition (Fortia 1.8.0)

Used to read the words on the photo of a package ("Search the product by its package", food log), on the phone.

- Artifact: `com.google.android.gms:play-services-mlkit-text-recognition:19.0.2` (Latin script, Text Recognition
  v2), from Google's Maven repository (https://maven.google.com). It is the **unbundled** variant: the app only
  carries a thin client (about 260 KB per Google's size table); the recognizer itself is part of Google Play
  services, which downloads it once (the app asks for it the first time a package is read; meanwhile the page says
  it is being installed). Phones without Google Play services cannot read packages; the barcode photo and the search
  still work.
- The other scripts ML Kit has, for the app's languages that use them: `play-services-mlkit-text-recognition-chinese`,
  `-japanese`, `-korean` and `-devanagari` 16.0.2 (2026-10-26; about 50 KB each, same dependencies, Java only). Each
  also reads Latin text; the app's language picks one (zh, ja, ko, hi; Latin for the others), and when it reads no word
  of its own script the photo is read again with the Latin recognizer (brand names in Latin letters). ML Kit has no Cyrillic
  or Arabic recognizer: in Russian and Arabic the app does not read packages (no button, never by itself).
- Nothing of ML Kit runs at app start: its `MlKitInitProvider` is removed from the merged manifest
  (`tools:node="remove"`) and the app calls `MlKit.initialize` (public API) the first time a package is read; no
  client is created and no download is requested before that.
- Checked on 2026-11-04: version 19.0.2 (2026-10-26, "rebuilt … no API changes") and every artifact it pulls in
  (`play-services-mlkit-text-recognition-common` 19.1.1, `com.google.mlkit:common` 18.11.0, `vision-common`,
  `vision-interfaces`, `play-services-base`/`-basement`/`-tasks`, `odml:image`, `datatransport`), and the four
  script artifacts, are Java libraries without Kotlin metadata, so the project's Kotlin 2.2 compiler reads them.
- Licence: proprietary, under the **ML Kit Terms of Service** (https://developers.google.com/ml-kit/terms, last
  modified 2025-05-14), which incorporate the Google APIs Terms of Service (https://developers.google.com/terms),
  like Firebase, Google Mobile Ads and Play Billing already used by the app. The binaries are not part of the
  published source: Fortia-source builds by downloading them from Google's Maven repository, exactly as for those
  libraries. Nothing in the terms forbids an open-source app; their only open-source rule is that developer
  credentials must not be embedded, and ML Kit uses none.
- Data (https://developers.google.com/ml-kit/android-data-disclosure, last modified 2025-05-14): the image and the
  text recognised are processed on the device and not sent to Google. ML Kit sends Google metrics "for diagnostics
  and usage analytics": device information (manufacturer, model, OS version and build), app information (package
  name, app version), device identifier and per-installation identifiers (unbundled variant), performance metrics,
  API configuration, input/output size, feature version, event type and error codes; encrypted in transit (HTTPS),
  not transferred to third parties. There is no documented switch to turn them off. The terms make the app
  responsible for telling users about Google's processing of these metrics: `docs/privacy-policy.md` (section 10,
  and its English summary) and `docs/privacy-policy.en.md` do; `docs/play-release.md` §6 lists ML Kit with AdMob
  under "Diagnóstico" and "ID de dispositivo u otros IDs".
- Credited in NOTICE ("Libraries") and in Profile → Open source → Libraries ("Google ML Kit (text recognition)",
  Google terms).
