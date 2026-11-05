# food-db: the offline food catalog

Builds `app/src/main/assets/food/foods.tsv` (the foods the app can log, with 22 nutrients per 100 g or 100 ml,
default portions and dish variants), `app/src/main/assets/food/recipes.tsv` (the ingredients of the dishes, so the
user can adjust them when logging) and `tools/i18n/src/master/97_foods.txt` (English and Spanish names) from two
open food composition tables, USDA FoodData Central (CC0) and Anses CIQUAL 2025 (Etalab 2.0), plus recipes of ours.
Sources, licences and attributions: `docs/third-party/food-data/README.md`. What was checked and fixed in 1.6.0,
and what 1.8.0 added (595 → 917 foods, the supplements group): `AUDIT.md`.

## Reproduce end to end

Python 3.11+, standard library only. The downloads go to a work folder outside git: `--dir DIR`, or the
environment variable `FOODDB_DIR`, default `./fooddb-data` (ignored by git).

```sh
python tools/food-db/download.py           # DIR/raw: 3 USDA zips + 4 CIQUAL XML files, checked by SHA-256 (~80 MB)
python tools/food-db/extract.py            # DIR/work/nutrients.tsv (17 370 foods, 26 nutrients) + portions.tsv
python tools/food-db/build.py              # foods.tsv + recipes.tsv + 97_foods.txt + sources_used.tsv; report
python tools/food-db/validate.py -v        # contract checks, outputs = fresh build, model classes if present
python tools/i18n/gen.py app/src/main/res  # regenerate values/ and values-es/ strings
python -m unittest discover -s tools/food-db -p "test_*.py"
./gradlew :app:testDebugUnitTest --tests "*FoodsTsvContractTest" --tests "*RecipesTsvContractTest"
```

When the model's class list changes, run the build as `python tools/food-db/build.py --sync-visual <classes.csv>`
(`tools/food-model/classes.csv`, or the `id,name_en,name_es,status` list food-model2 publishes: rows with status
`dropped` are not classes): it first sets the visual column of mapping.txt from the classes, failing if a class has
no row or is a variant. 1.6.0: every class of food_classes_v2.csv has a row and none is a variant; the visual column
still follows the 1.5.0 `tools/food-model/classes.csv` until food-model2's branch (329 classes) is merged, then run
`python tools/food-db/build.py --sync-visual tools/food-model/classes.csv` and commit the result.

Without the downloads, `build.py` uses `sources_used.tsv`, the committed copy of the source rows the catalog uses,
so names, portions, groups, options and recipes can be changed and rebuilt offline; only a new source code needs the
full tables. `build.py --check` (also run by validate.py and the tests) fails if foods.tsv, recipes.tsv or
97_foods.txt are not exactly what a fresh build from sources_used.tsv gives.

## Files

| File | What |
|---|---|
| `mapping.txt` | **The catalog, one food per line** (human-reviewable): id, group, unit, visual, source code, options, portions, EN/ES names, the source's own description (checked by the build) and a note. Edit here. |
| `recipes.toml` | Ingredients of dishes (catalog food ids and amounts for a whole recipe, cooking yield): recipe dishes are computed from them; for USDA/CIQUAL dishes they are a typical recipe for adjusting. Format at the top of the file. |
| `sources_used.tsv` | The USDA/CIQUAL rows used by mapping.txt and recipes.toml, as extract.py writes them (written by build.py). |
| `notes.md` | Decisions: how vague model classes were resolved, portions, energy-check outliers. |
| `AUDIT.md` | The 1.6.0 audit (what was checked, every value fixed: old → new, why) and the 1.8.0 additions (sources per group, checks, coverage), gaps and open issues. |
| `download.py`, `extract.py` | Get and flatten the source tables. |
| `search.py` | Find source codes: `python tools/food-db/search.py -d ciqual lentil` or `-k ciqual:20587` (with USDA portions). |
| `build.py` | Generates the outputs. `--fix-descriptions` refreshes the description column after a source change. |
| `validate.py`, `test_fooddb.py` | Checks of the outputs and unit tests of the tools. |

## mapping.txt options (`;`-separated)

| Option | Meaning |
|---|---|
| `density=<g/ml>` | ml foods: per 100 ml = per 100 g × density. |
| `fill=<key>` | Nutrients the source lacks are borrowed from another USDA/CIQUAL food (fatty acids scaled to this food's fat). `fill=recipe`: from the food's own typical recipe in recipes.toml. |
| `zero=<n>,<n>` | Nutrients the source lacks that this kind of food does not contain (cholesterol, vitamin D or B12 in plant foods, vitamin C in fresh meat, fish and cheese…) are 0. |
| `replace=<key>:<n>,<n>` | A value of the source that is clearly wrong is taken from another source instead (AUDIT.md says why). |
| `unknown=<n>,<n>` | Values of the source that do not describe the food as eaten here (the added vitamins of a US fortified product, a "< x" that contradicts the raw food) are left unknown (1.8.0; AUDIT.md says why). |
| `variant_of=<id>` | This food is a variant of a base food (contract 2): visual = 0, the base exists and is not a variant itself. |

## Rules the build follows

- Values per 100 g (per 100 ml for `unit = ml`, using the `density=` option), as eaten: cooked staples are cooked
  weights, dishes are as served. Energy from the source (kcal); recipes add up the ingredients' energy.
- foods.tsv v2 (contract 2 of `.omc/handoffs/2026-11-02/common.md`): the 15 v1 columns, then `mono_fat poly_fat`
  (g, 1 decimal), `cholesterol potassium calcium iron magnesium zinc` (mg), `vit_a` (µg RAE), `vit_c` (mg), `vit_d`
  (µg), `vit_e` (mg alpha-tocopherol), `vit_b12` (µg), `folate` (µg DFE), mg/µg rounded to an integer from 100,
  1 decimal from 10, else 2 decimals; then `variant_of`. Empty = unknown.
- Carbohydrate is available carbohydrate (EU label style): CIQUAL "glucides"; USDA "by difference" minus fibre
  (never below the sugars). Salt = sodium × 2.5. CIQUAL "< x" (below the quantification limit) and "traces" → 0,
  as EU labels declare them. Missing values stay empty unless `fill=` or `zero=` give them.
- Recipes: values = sum(ingredient value × amount) / (total weight × yield). A nutrient of a recipe dish is unknown
  when the ingredients that lack it weigh 5% or more of the recipe; smaller ones (a spice) are left out of the sum.
- recipes.tsv (contract 3): one row per dish and ingredient, `grams` = amount of the ingredient in its own unit
  (ml for ml foods) in 100 g of the finished dish (100 ml for ml dishes), 1 decimal, rows under 0.05 left out;
  `adjustable` 0 for water, salt, spices, garlic, vinegar, lemon/lime juice and broth, else 1. For recipe dishes the
  rows reproduce the dish's values within rounding (validate.py); for USDA/CIQUAL dishes the typical recipe must
  explain the dish's energy within 25%.
- Energy check: source kcal vs 4·protein + 4·(carbs − polyols) + 2.4·polyols + 9·fat + 2·fibre + 7·alcohol
  + 3·organic acids; the build lists foods off by more than max(15 kcal, 12%), validate.py fails above
  max(25 kcal, 20%), except the alcoholic drinks listed in validate.py (alcohol is not a column). validate.py also
  checks saturated + mono + poly fat ≤ fat and plausible upper bounds for the micronutrients.
- Documented compositions (1.8.0): a few products that no open table has (creatine, BCAA/EAA amino acids,
  maltodextrin, fino/manzanilla sherry) are `recipe` foods whose recipes.toml entry has `composition = {kcal = …, …}` per 100 g instead of
  ingredients, `list = false` and a `basis` saying where the values come from (chemistry and the EU energy
  factors); the nutrients it does not list are unknown.
- Groups: the 15 of 1.5.0 plus `supplements` (1.8.0: protein powders and shakes, bars, gels, creatine, amino acids…;
  `whey_protein` moved there from dairy).
- Portions: Spanish servings (plate of lentils ~350 g, slice of bread 30 g, egg 50 g, glass of wine 150 ml,
  can 330 ml…), only the keys
  `unit piece slice plate small_plate bowl cup glass can bottle tbsp tsp handful serving scoop` (`scoop`, 1.8.0:
  the measuring scoop of a protein tub, ~30 g); the default portion is one of them.
- Search: the app finds foods by the words of their name in the user's language (no synonyms), so names carry the
  usual alternatives ("Café con leche o latte", "Mandarina o clementina"); `test_fooddb.SearchTest` checks that
  common Spanish and English searches find their food, and that no two foods share a name.
- Ids are `[a-z0-9_]+` and never renamed or deleted (saved entries point to them); the on-device model's class ids
  are kept exactly and have `visual = 1`. Names are the strings `food_<id>` and `food_group_<group>` (English in
  British spelling, Spanish es-ES); 97_foods.txt is generated, so change names in mapping.txt. Translators add
  `tools/i18n/src/<locale>/97_foods.txt` (same keys).
- Never use BEDCA or values copied from websites or apps with unclear licences.
