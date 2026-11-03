# Fortia Privacy Policy

Last updated: 3 November 2026 · Contact: **enjoyyourtime.online@gmail.com** ·
Web version (Spanish, with an English summary): [globalfitness-app.web.app/privacy.html#en](https://globalfitness-app.web.app/privacy.html#en)

This is the English version of the privacy policy. If it differs from the Spanish version, the
Spanish version prevails.

## 1. Who is responsible

Fortia (formerly GlobalFitness) is an Android app published by **Enjoy Your Time**, which is the data controller.
For any privacy question, write to **enjoyyourtime.online@gmail.com**.

## 2. Summary

- The app works **without an account**: by default all your data is stored **only on your phone**.
- The **cloud backup is optional**: it is only turned on if you sign in with Google, and you can
  delete it (together with the account) from the app whenever you want.
- Your **health data is never used for advertising** and is never sold.
- The app shows **Google AdMob ads**, unless you buy "Remove ads". In the EU we ask for your consent
  before showing personalised ads.

## 3. Data stored on your phone

- **Profile**: name, sex, date of birth, height and target weight (to calculate body composition).
- **Measurements**: date and time, weight, bioelectrical impedance and whether they come from a scale
  or were entered manually. From them the phone calculates the BMI, body fat and other estimates.
- App **settings** and **configured scales** (model, name and options).
- **Training**: the workouts you log (date and time, name and notes, exercises and sets with the weight,
  repetitions, time, distance, level or calories you enter), the machines and exercises you create (name,
  muscles and setting notes), your favourites and, if you add them, **photos of your machines**. You take
  the photos with the camera or pick them one by one with Android's photo picker (the app has no access to
  your gallery); they are downscaled and stored in the app's private storage. With your logged body weight
  the phone calculates the estimated 1RM and the progress per muscle.
- **Food**: what you log that you eat (food, amount, meal, date and time and its nutrient values), the foods
  you create, the products you find by their barcode and your daily goal (with the weight you type if you have
  no measurements yet). If you take a **photo of your plate**, it is downscaled, stored in the app's private
  storage and **analysed on the phone itself** by our recognition models, which find each food on the plate,
  recognise it and estimate its grams as a suggestion you confirm: **the photo never leaves the phone** (it is
  not uploaded to the cloud or to any server) and it is deleted when you delete the food. If you log several foods
  from one photo, each keeps its crop of the photo, which also stays on the phone and is deleted with it. With
  each food the app keeps what the model proposed, how sure it was and how many grams it estimated, and the
  ingredients you change in a dish, only on your phone (and in your cloud backup if you use it), to improve its
  suggestions.
- **Xiaomi Smart Band**: the band's pairing key is stored only on the phone and **never leaves it**
  (not to the cloud, not to Android backups). The heart rate you record in direct mode (date, time
  and beats per minute) is also stored only on the phone.

This data stays on the phone until you delete it or uninstall the app.

## 4. Bluetooth devices

The app uses Bluetooth to read the measurements of your scale and your band. On Android 11 or
earlier, Android requires the location permission to search for Bluetooth devices; the app **does
not use your location** for anything else and does not store it. The Bluetooth addresses of your
devices are stored only on the phone.

**Notification forwarding (optional).** If you turn on notification forwarding to the band and
grant the app notification access, the app reads the app name, title and text of the notifications
of the apps you choose and sends them over Bluetooth to your band, only while it is connected in
direct mode. That content is **not stored, not logged and does not leave the phone** except to your
band. You can revoke the access in the Android settings.

## 5. Health Connect

If you allow it, the app **reads and writes** in Health Connect the data types you grant on the
Android permissions screen (for example weight, body composition or heart rate). You can revoke the
permission at any time in the Health Connect settings.

If you allow it, the app also **writes your finished workouts to Health Connect** as exercise sessions
(start and end, exercise type, the workout's name and notes and one segment per set with its repetitions;
weights are not written) so other fitness apps can see them. If you delete a workout in the app, it is also
deleted from Health Connect; if you turn the option off, the app stops writing and what was already saved is
kept. The app does not read its own sessions back.

If you allow it, the app also **writes what you log that you eat to Health Connect** as nutrition records (date
and time, meal, the food's name, calories, protein, carbohydrates, sugars, fat, saturated fat, fibre and sodium
and, when the food has them, monounsaturated and polyunsaturated fat, cholesterol, potassium, calcium, iron,
magnesium, zinc, vitamins A, C, D, E and B12 and folate).
If you edit or delete a food, it is updated or deleted in Health Connect too; if you turn the option off, the app
stops writing and what was already saved is kept. The app **does not read** nutrition data from Health Connect.

- Health Connect data is used **only to show you your progress and to sync your measurements** with
  other apps you choose. What is read is kept in a cache on the phone.
- It is **not uploaded** to the Fortia cloud backup, **not used for advertising**, not sold
  and not shared with third parties.
- The use of information received from Health Connect complies with the Health Connect permissions
  policy, including the Limited Use requirements.

## 6. Google account and cloud backup (optional)

If you sign in with Google (through Firebase Authentication):

- **Account data**: Firebase user identifier, email address, name and profile photo of your Google
  account. They are used to identify you and to show the account in the app.
- **Backup** (Google Cloud Firestore): your profile (with a random dataset identifier, so data of
  different people is never mixed), your measurements (date, weight, impedance, body composition
  reported by the scale, source and scale model) and your configured scales (model, name and user
  number on the scale), your workouts, your routines and your own machines and exercises with their notes,
  favourites and photos, and what you log in Food (foods, amounts, values and whether you changed them by hand, the ingredients you change and your own foods), **without
  the photos of your meals**. The band key, the encryption keys, PINs or pairing keys of the scales, the
  Bluetooth addresses, Health Connect data and phone-specific settings are **not** uploaded.
  If you bought "Remove ads", an informational `premium` flag is also stored.
- **Where**: on Google Cloud servers in the **European Union (region europe-southwest1, Madrid)**. The
  data travels encrypted (TLS) and Google stores it encrypted at rest.
- **What for**: to keep a copy and restore it if you change phones or reinstall the app, and to keep
  your phones in sync.
- **Legal basis**: your explicit consent (GDPR art. 6(1)(a) and 9(2)(a)), which you give when you sign
  in and can withdraw at any time.
- **How long**: as long as you keep the account. If you sign out, the backup is kept in the cloud (you
  can sign in again to restore it).
- **How to delete it**: in the app, **Profile → Account and cloud backup → "Delete my cloud data and
  account"**. Your Firestore backup and your Firebase account are permanently deleted; the data on
  your phone is not touched. You can also ask for it by email at enjoyyourtime.online@gmail.com. In
  addition, an automatic daily cleanup deletes, within 2 days at most, any cloud data left under an account
  that no longer exists (for example, an account deleted from an older version of the app that did not know
  about newer kinds of data, such as the food log). We run it ourselves with Google's own Firebase APIs; no
  other third party receives any data. Full instructions:
  [globalfitness-app.web.app/delete-account.html](https://globalfitness-app.web.app/delete-account.html)

## 7. Advertising (Google AdMob)

The free version shows **Google AdMob** ads. For that, Google may process device identifiers (such as
the advertising ID), the IP address and ad interaction data, according to the
[Google Privacy Policy](https://policies.google.com/privacy) and
[how Google uses information from sites or apps that use its services](https://policies.google.com/technologies/partner-sites).

- In the European Economic Area and the United Kingdom we use **Google's User Messaging Platform
  (UMP)** to ask for your consent before showing ads. You can change your decision in **Profile →
  "Ad privacy options"**.
- **We never send your health or body data to the ads** (weight, body composition, heart rate,
  profile…): ad requests carry no targeting or keywords from the app.
- No ads are shown on the sign-in, purchase or consent screens.

## 8. "Remove ads" purchase (Google Play Billing)

The purchase is processed by **Google Play**. The app only receives the purchase state (purchased,
pending) and a purchase identifier to confirm it; **we do not receive your payment details**. The
state is stored on the phone so you stay ad-free even without a connection.

## 9. Android backup

If Android backup is on, the system may store an encrypted copy of the app's database and settings in
your Google Drive account and restore it when you reinstall or change phones. **Excluded** are the
bands and watches set up in the app (with their keys, settings and the heart rate recorded in
direct mode), the Health Connect cache, the photos of your machines (because of their size; if you use
the app's cloud backup, they are kept there) and the photos of your meals (kept on the phone only). You can
turn it off in the Android settings.

## 10. Who the data is shared with

Only with the providers needed for the app to work, acting as processors or as independent
controllers depending on the case: **Google** (Firebase Authentication, Cloud Firestore, AdMob/UMP and
Google Play). We do not sell personal data.

**Barcodes (Open Food Facts).** When you take a photo of a product's barcode, the barcode is read on the
phone and **only the barcode number and the app's language** (for the product name in your language) are sent
to **Open Food Facts** (world.openfoodfacts.org, an open database
of food products run by a French non-profit association) to get the product's name, brand and nutrient values.
Open Food Facts receives your IP address and an app User-Agent ("Fortia/", its version and our contact email);
nothing else is sent: no photo, account, identifier or personal data. The product found is kept on the phone (and
in your cloud backup if you use it) and asked again, when you scan it, only if data is missing or it is over 90
days old. It only happens when you scan a barcode; searching the
app's food list sends nothing. Legal basis: providing the service you ask for (GDPR art. 6(1)(b)). Open Food
Facts data is published under the Open Database License (ODbL).

**Music for today (Spotify).** The covers and names of the Spotify playlists the app suggests are only
loaded **if you allow it**: the app asks once, at start, right after the ad consent, and you can change
your answer at any time in **Profile → Training settings → "Show Spotify covers"** (legal basis: your
consent, Art. 6(1)(a) GDPR). Until you allow it, the app sends no request at all to Spotify and the
playlists show their names without covers. If you allow it, the phone requests them directly from
Spotify: from open.spotify.com (its oEmbed service, which gives each playlist's name and cover) and from
its image servers (`*.scdn.co`, `*.spotifycdn.com`). Spotify therefore receives your IP address and a
generic app User-Agent ("Fortia/" and its version, without the phone's model); no account, identifier
or personal data is sent: only the playlist's public link. The names and covers are kept in the phone's
cache and deleted once a playlist has not been suggested for 30 days, and as soon as you turn off the
covers or the suggestions (Android may also clear the cache when it needs space). Opening a playlist is
your choice and does not depend on this permission: it takes you to Spotify's app or website, which are
governed by Spotify's own terms and privacy policy. You can hide all the suggestions in **Profile →
Training settings**.

**Music controls (optional).** In Training and during a workout, the previous, play/pause and next buttons
send the music app that is playing the same media keys a headset sends, with no permission. Only if you turn
on "See what's playing" and grant **notification access** in Android does the app check the state of the
active media players (playing or paused) to pick the one to show, and read only that one's song title,
artist, cover and position and its app's name, to show them to you and control it. That access is **not used
to read or dismiss your notifications or to change Do Not Disturb**: the app ignores your notifications. What
it reads is only shown on screen: it is **not stored, not logged and does not leave the phone**, and no
Spotify (or other) account or service is involved. You can turn it off in **Profile → Training settings** or
in Android's settings.

## 11. Children

The app is not directed at children under 16.

## 12. Your rights

You can access your data, rectify it, erase it, object to or restrict its processing, withdraw your
consent and ask for portability (the app lets you **export your measurements to CSV**). Write to us at
enjoyyourtime.online@gmail.com. If you are not satisfied, you can complain to the **Spanish Data
Protection Agency** (www.aepd.es) or to your local data protection authority.

## 13. Changes

If we change this policy, we will publish the new version with its date and, if the change is
important, we will let you know in the app.
