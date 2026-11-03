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

`tools/food-db/sources_used.tsv` redistributes, under the same licences and attributions, the ~540 USDA and CIQUAL
rows the catalog uses (per-100 g values after the changes below), so the catalog can be rebuilt and checked without
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
values are ours, not Anses' or USDA's, and are estimates: real products and home recipes vary.

## Open Food Facts (online, barcodes)

When the user scans a barcode, the app looks the product up in Open Food Facts (https://world.openfoodfacts.org).
That data is not bundled; it is downloaded on demand. Open Food Facts database: **Open Database License
(ODbL 1.0)**; database contents: Database Contents License (DbCL 1.0); product images: CC BY-SA 4.0. The app
credits Open Food Facts where it shows a product found there.
