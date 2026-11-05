# Food composition data

The food catalog bundled with the app (`app/src/main/assets/food/foods.tsv`: energy, protein, carbohydrate,
sugars, fat, saturated fat, fibre and salt, monounsaturated and polyunsaturated fat, cholesterol, potassium, calcium,
iron, magnesium, zinc and vitamins A, C, D, E, B12 and folate per 100 g or 100 ml, with default portions) and the
ingredients of its dishes (`app/src/main/assets/food/recipes.tsv`) are built by `tools/food-db` (see its README)
from the two open food composition tables below. Each row's `source` column names the food it comes from
(`fdc:<id>` or `ciqual:<code>`); `recipe` rows are computed by us from ingredients of those tables
(`tools/food-db/recipes.toml`). The English and Spanish food names and the recipes are ours.

## USDA FoodData Central

- Publisher: U.S. Department of Agriculture, Agricultural Research Service.
- Licence: public domain, not copyrighted, published under **CC0 1.0 Universal** (stated on
  https://fdc.nal.usda.gov/ and in the data.gov catalog entry); no permission needed, USDA asks to be credited
  as the source.
- Citation (the one suggested in the FoodData Central API guide): U.S. Department of Agriculture, Agricultural
  Research Service. FoodData Central, 2019. fdc.nal.usda.gov.
- Releases used (CSV, https://fdc.nal.usda.gov/download-datasets):
  - Foundation Foods, April 2026 (`FoodData_Central_foundation_food_csv_2026-04-30.zip`)
  - SR Legacy, April 2018 (`FoodData_Central_sr_legacy_food_csv_2018-04.zip`)
  - FNDDS 2019-2020 / Survey Foods, October 2022 (`FoodData_Central_survey_food_csv_2022-10-28.zip`)

## CIQUAL (Anses)

- Publisher: Anses (French Agency for Food, Environmental and Occupational Health & Safety), Ciqual table.
- Licence: **Etalab Open Licence 2.0** (Licence Ouverte 2.0, https://www.etalab.gouv.fr/licence-ouverte-open-licence/),
  which allows reuse, including commercial, with attribution of the source and the date of its last update.
- Attribution: Anses. 2025. Table de composition nutritionnelle des aliments Ciqual 2025 (version of
  2025-11-03). https://ciqual.anses.fr/ — dataset doi:10.57745/RDMHWY on recherche.data.gouv.fr.
- Files used: `alim_2025_11_03.xml`, `compo_2025_11_03.xml`, `const_2025_11_03.xml`.

`tools/food-db/sources_used.tsv` redistributes, under the same licences and attributions, the ~720 USDA and CIQUAL
rows the catalog uses (per-100 g values after the changes below; 1.8.0: ~900 foods, the same releases as before), so the catalog can be rebuilt and checked without
downloading the full tables.

## What we changed

Values are taken as published, then: salt = sodium × 2.5; USDA carbohydrate "by difference" minus dietary fibre
(available carbohydrate, as on EU labels), never below the sugars; CIQUAL "< x" (below the quantification limit)
and "traces" as 0; vitamin A as µg RAE, folate as µg DFE and vitamin D in µg, computed from the source's
components when it lacks the summary value (`tools/food-db/extract.py`); drinks converted to per 100 ml with a
density; rounded to 1 decimal for grams (energy to an integer; mg and µg to at most 2 decimals). Some foods borrow
nutrients the main source lacks from a near-identical food of the other table (`fill=` in
`tools/food-db/mapping.txt`, fatty acids scaled to the food's own fat), take a few clearly wrong values from another
source (`replace=`), or set to 0 nutrients that a kind of food does not contain, such as cholesterol and vitamin B12
in plant foods (`zero=`); the reasons are in `tools/food-db/AUDIT.md`. Dishes marked `recipe` combine ingredient
values by weight and cooking yield; the ingredient lists of other dishes are typical recipes of ours. These derived
values are ours, not Anses' or USDA's, and are estimates: real products and home recipes vary. Four
products that neither table has (creatine monohydrate, BCAA/EAA amino acids, maltodextrin, fino/manzanilla sherry)
have a composition documented by us from their chemistry and the EU energy factors (`composition` in `tools/food-db/recipes.toml`).
No values come from brand labels, supermarket websites, Open Food Facts or BEDCA.

## Open Food Facts (online, barcodes)

When the user scans a barcode, the app looks the product up in Open Food Facts (https://world.openfoodfacts.org).
That data is not bundled; it is downloaded on demand. Open Food Facts database: **Open Database License
(ODbL 1.0)**; database contents: Database Contents License (DbCL 1.0); product images: **Creative Commons
Attribution-ShareAlike 3.0 (CC BY-SA 3.0)**, by the Open Food Facts contributors. The app credits Open Food Facts
where it shows a product found there ("Data: Open Food Facts (ODbL)", or "Data and photo: Open Food Facts (ODbL,
CC BY-SA)" when the product's photo shows too), and in Profile → Open source → Food data (both licences, with the
CC BY-SA 3.0 address), which also covers the photos shown in the day's list and the search.

### Product photos

From the release after 1.7.0, a product found by its barcode also shows the photo of the front of its package
from Open Food Facts (the `image_front_url` field of API v2, about 400 px, in the app's language when Open Food
Facts has one, else in the product's main language; else `image_front_small_url`, 200 px). The app downloads it
from Open Food Facts' image servers (only `https://*.openfoodfacts.org`, following at most 2 redirects and only to
those servers, at most 2 MB, JPEG/PNG/WebP), keeps a
copy of at most 400 px (JPEG) in its private storage, never uploads it and deletes it with the product. No photo is
bundled with the app or its source.

Licence checked on 2026-11-04:
- https://world.openfoodfacts.org/terms-of-use: "Products images are available under the Creative Commons
  Attribution ShareAlike licence", linking to CC BY-SA 3.0; re-users must name the licence and credit Open Food
  Facts, with a link to openfoodfacts.org.
- The image server itself answers every photo with
  `Link: <http://creativecommons.org/licenses/by-sa/3.0/>; rel='license'; title='CC-BY-SA 3.0'`.

The photos may show trademarks and package designs, which Open Food Facts warns can be subject to other rights.
The app only shows them as a small thumbnail to recognise the product, next to its name.

### Search by the words on a package (from 1.8.0)

When the user searches a product by the photo of its package, the words read on the phone (at most five) are
searched in Open Food Facts' full-text search (`https://world.openfoodfacts.org/cgi/search.pl`, live data; when it
answers 503/429 or not JSON, its search service search-a-licious, `https://search.openfoodfacts.org/search`, whose
index is a few months behind). The list shows each product's name, brand, quantity and small front photo
(`image_front_small_url`, 200 px, downloaded from `https://*.openfoodfacts.org` only and kept in memory, never
stored), credited "Data and photo: Open Food Facts (ODbL, CC BY-SA)". The product the user picks is then read by its
barcode (API v2) and kept like a scanned one. Limits checked on 2026-11-04
(https://openfoodfacts.github.io/openfoodfacts-server/api/): 10 searches and 15 product reads per minute and IP
address, no search-as-you-type; the app sends one search per user action, at most 5 a minute, keeps the answers
30 minutes in memory and sends nothing for a minute after a 429. `/cgi/search.pl` answered half of the requests
with a 503 page on 2026-11-04: after a 503 (or an answer that is not JSON) the app asks search-a-licious first for
10 minutes, after a 429 for a minute. search-a-licious was checked to return `product_name_<lc>` and
`image_front_small_url` with the same `fields` list. Same licences as above: the hits are shown, not
copied into the app's catalog.

### Matching a photo with the products already scanned (from 1.8.0)

The app compares a meal photo, on the phone, with the photos of the products the user scanned (the stored front
photo above, and up to five of the user's own package photos logged as that product), using an image embedding
of our own food model (`docs/third-party/food-model/`). It keeps only the embeddings (lists of numbers, in the
app's `noBackupFilesDir`, never uploaded or backed up), computed locally from the photos downloaded to that phone.
The Open Food Facts photos are not used to train any model.
