# food-db: the offline food catalog

Builds `app/src/main/assets/food/foods.tsv` (the foods the app can log, with nutrients per 100 g or 100 ml and
default portions) and `tools/i18n/src/master/97_foods.txt` (their English and Spanish names) from two open food
composition tables, USDA FoodData Central (CC0) and Anses CIQUAL 2025 (Etalab 2.0), plus recipes for dishes
neither table has. Sources, licences and attributions: `docs/third-party/food-data/README.md`.

## Reproduce end to end

Python 3.11+, standard library only. The downloads go to a work folder outside git: `--dir DIR`, or the
environment variable `FOODDB_DIR`, default `./fooddb-data` (ignored by git).

```sh
python tools/food-db/download.py           # DIR/raw: 3 USDA zips + 4 CIQUAL XML files, checked by SHA-256 (~80 MB)
python tools/food-db/extract.py            # DIR/work/nutrients.tsv (17 370 foods) + portions.tsv (USDA measures)
python tools/food-db/build.py              # foods.tsv + 97_foods.txt + sources_used.tsv; counts and energy check
python tools/food-db/validate.py -v        # contract checks, outputs = fresh build, model classes if present
python tools/i18n/gen.py app/src/main/res  # regenerate values/ and values-es/ strings
python -m unittest discover -s tools/food-db -p "test_*.py"
./gradlew :app:testDebugUnitTest --tests "*FoodsTsvContractTest"
```

Once the food-model branch is merged (`tools/food-model/classes.csv` exists), run the build as
`python tools/food-db/build.py --sync-visual tools/food-model/classes.csv`: it first sets the visual column of
mapping.txt from the model's classes (failing if a class has no row). Leave `--sync-visual` out before that.

Without the downloads, `build.py` uses `sources_used.tsv`, the committed copy of the source rows the catalog uses,
so names, portions, groups, options and recipes can be changed and rebuilt offline; only a new source code needs the
full tables. `build.py --check` (also run by validate.py and the tests) fails if foods.tsv or 97_foods.txt are not
exactly what a fresh build from sources_used.tsv gives.

## Files

| File | What |
|---|---|
| `mapping.txt` | **The catalog, one food per line** (human-reviewable): id, group, unit, visual, source code, options, portions, EN/ES names, the source's own description (checked by the build) and a note. Edit here. |
| `recipes.toml` | Dishes computed from ingredients: grams of each USDA/CIQUAL ingredient for a whole recipe and the cooking yield. |
| `sources_used.tsv` | The USDA/CIQUAL rows used by mapping.txt and recipes.toml, as extract.py writes them (written by build.py). |
| `notes.md` | Decisions: how vague model classes were resolved, portions, energy-check outliers. |
| `download.py`, `extract.py` | Get and flatten the source tables. |
| `search.py` | Find source codes: `python tools/food-db/search.py -d ciqual lentil` or `-k ciqual:20587` (with USDA portions). |
| `build.py` | Generates the outputs. `--fix-descriptions` refreshes the description column after a source change. |
| `validate.py`, `test_fooddb.py` | Checks of the outputs and unit tests of the tools. |

## Rules the build follows

- Values per 100 g (per 100 ml for `unit = ml`, using the `density=` option), as eaten: cooked staples are cooked
  weights, dishes are as served. Energy from the source (kcal); recipes add up the ingredients' energy.
- Carbohydrate is available carbohydrate (EU label style): CIQUAL "glucides"; USDA "by difference" minus fibre
  (never below the sugars). Salt = sodium × 2.5. CIQUAL "< x" (below the quantification limit) and "traces" → 0,
  as EU labels declare them. Missing values stay empty unless `fill=` borrows them.
- Energy check: source kcal vs 4·protein + 4·(carbs − polyols) + 2.4·polyols + 9·fat + 2·fibre + 7·alcohol
  + 3·organic acids; the build lists foods off by more than max(15 kcal, 12%), validate.py fails above
  max(25 kcal, 20%), except the alcoholic drinks listed in validate.py (alcohol is not a column).
- Portions: Spanish servings (plate of lentils ~350 g, slice of bread 30 g, egg 50 g, glass of wine 150 ml,
  can 330 ml…), only the keys `unit piece slice plate small_plate bowl cup glass can bottle tbsp tsp handful serving`;
  the default portion is one of them.
- Ids are `[a-z0-9_]+`; the on-device model's class ids (`tools/food-model/classes.csv`) are kept exactly and have
  `visual = 1`. Names are the strings `food_<id>` and `food_group_<group>` (English in British spelling, Spanish
  es-ES); 97_foods.txt is generated, so change names in mapping.txt. Translators add
  `tools/i18n/src/<locale>/97_foods.txt` (same keys).
- Never use BEDCA or values copied from websites or apps with unclear licences.
