# Food catalog audit (Fortia 1.6.0, 2026-11-02)

Scope: every row of `app/src/main/assets/food/foods.tsv` as published in 1.5.0 (510 foods), its source, its recipe
and its portions, before adding the 14 new nutrient columns, the variants and the ingredient lists.

## What was checked

1. **Values vs the cited source, re-derived from the raw files.** `extract.py` was re-run on the downloads
   (USDA FoodData Central Foundation 2026-04, SR Legacy 2018-04, FNDDS 2019-2020 zips; CIQUAL 2025 XML of
   2025-11-03; same SHA-256 as `download.py`). For all 492 USDA/CIQUAL rows that 1.5.0 used
   (`sources_used.tsv`), the 12 extracted fields and the description are identical to the fresh extraction:
   **0 differences**, and foods.tsv equals a fresh build (`build.py --check`). No transcription error.
2. **Plausibility of every row** (`validate.py`, now also in the build): energy vs 4·P + 4·C + 9·F + 2·fibre
   (+ alcohol, polyols, organic acids), sugars ≤ carbohydrate, saturated ≤ fat, and new in 1.6.0 saturated + mono +
   poly ≤ fat and upper bounds for the 14 micronutrients. Outside the energy tolerance only foods explained by what
   the label columns leave out: dried apricots (polyols), balsamic vinegar and vinegar (acetic acid), alcoholic
   drinks. Salt is sodium × 2.5 for every row (CIQUAL's own salt column is not used).
3. **Recipe dishes vs their ingredients.** All 75 recipes were recomputed; their ingredients are now catalog foods
   and `recipes.tsv` lists them; validate.py checks that the listed ingredients reproduce every nutrient of each
   recipe dish within rounding (100 dishes).
4. **Does the source match the food?** Each id's source description was compared with its name, its model class
   and Spanish usage. Ready meals and US versions that did not match the Spanish dish were replaced (below).
5. **Portions and default portions** were reviewed against Spanish servings (plate, slice, glass, pot…). The
   1.5.0 portions held up; only the pasta plates that became recipes changed (their recipe is one plate).
6. **Micronutrients** (new columns): top values per column and per group medians were reviewed for unit or data
   errors; three CIQUAL values were replaced (below).

## Fixes (id: old → new, why)

Values per 100 g (100 ml for drinks): kcal, protein P, carbohydrate C, sugars S, fat F, saturated SF, salt.

### Source did not match the dish

| Id | Old → new | Why |
|---|---|---|
| `churros` | FNDDS churros 419 kcal, S 23.4, SF 9.2 → recipe 378 kcal, S 0.9, F 20.6, SF 2.3 | FNDDS churros are sugar-and-cinnamon coated (US/Mexican); Spanish churros are flour, water and salt fried in sunflower oil. `churros_with_sugar` is the variant. |
| `yogurt` | CIQUAL plain average 50 kcal, F 1.7 → USDA plain whole-milk 61 kcal, F 3.3 | The note said "plain whole-milk yogurt" but CIQUAL's average mixes low-fat yogurts; Spanish yogur natural has ~3% fat. |
| `carbonara` | CIQUAL prepacked 161 kcal, F 8.7 → recipe 217 kcal, F 10.9; plate 350 → 300 g | A ready meal with little pasta and sauce; Spanish home carbonara (pasta, bacon, cream, cheese). `carbonara_italian` is the variant without cream. |
| `spaghetti_bolognese` | CIQUAL prepacked 116 kcal → recipe 172 kcal; plate 350 → 330 g | Ready meal; home recipe (pasta, minced beef, tomato, olive oil, parmesan). |
| `pasta_tomato` | FNDDS 119 kcal → recipe 159 kcal; plate 350 → 300 g | FNDDS "pasta with tomato sauce and cheese" was mostly sauce; macarrones con tomate y queso. |
| `lasagna`, `cannelloni` | CIQUAL prepacked 134 kcal, F 5.7 → FNDDS restaurant lasagne 185 kcal, F 10.7 | Ready meal; restaurant/home lasagne and canelones have more meat, bechamel and cheese (FNDDS home recipe 206, restaurant 185). |
| `toast` | USDA toasted sliced bread 290 kcal, F 4, S 6.2 → CIQUAL toasted French bread 317 kcal, F 1.3, S 1.4 | A Spanish tostada is toasted barra, not US enriched sandwich bread (whose iron and folic acid are US fortification). |
| `bread_roll` | USDA roll → CIQUAL bun 283 kcal | Same reason (European unfortified flour). |
| `bagel` | USDA enriched bagel 264 kcal → CIQUAL bagel 301 kcal | Same reason. |
| `egg_noodles` | USDA enriched egg noodles → CIQUAL cooked egg pasta 134 kcal | Same reason. |

### Recipes whose ingredients became catalog foods (small changes)

| Id | Change | Why |
|---|---|---|
| `bean_stew` 149 → 143, `potaje_alubias` 117 → 110 kcal | dry beans: USDA navy beans → CIQUAL dry haricot beans (`white_beans_dry`) | the catalog's own dry beans |
| `calamari` 253 → 250, `croquettes` 266 → 265, `empanada` 263 → 257, `fried_fish` 214 → 212, `meatballs`, `stew`, `steamed_buns` 248 → 240, `custard_tart` | flour: USDA enriched flour → CIQUAL type 65 (`wheat_flour`) | the catalog's flour, European |
| `judias_verdes_con_patatas` sugars 2.2 → 0.3, fibre 2.5 → 3.1 | raw beans and potatoes → cooked green beans and boiled potato (the dish as served) | CIQUAL reports no sugars in cooked green beans |
| `ramen`, `hot_pot` | FNDDS salted noodles → `egg_noodles`; beef/meat broth → `broth` | catalog foods |
| `fish_and_chips` salt 0.5 → 0.8 | unsalted chips → `french_fries` (salted as served) | chips are served salted |
| `greek_salad` | red onion → `onion` | same values within 0.1 g |
| `avocado_toast`, `full_breakfast` | follow `toast` above | |
| `oatmeal`, `cortado`, `gin_tonic`, `mojito`, `rum_and_cola`, `tinto_de_verano` (±1 kcal) | milk, wine, sodas and spirits written in ml (their catalog unit) | contract 3: ingredient amounts in the ingredient's unit |
| `lamb` sugars empty → 0 | `zero=sugars` | meat has no sugars; USDA leaves the field blank |
| drinks made from a recipe (café con leche and its variants, cortado, gin-tonic, cubata, mojito, tinto de verano…) ±1-2 kcal | a `density=` for the mixed drink (e.g. café con leche 1.024) | without it "per 100 ml" was per 100 g of the mixture; café bombón 144 → 160 kcal/100 ml, its cup 63 ml |

### Micronutrient values replaced or set

| Id | Value | Why |
|---|---|---|
| `sunflower_seeds` | zinc 36 → 5.29 mg (USDA dry roasted, salted) | CIQUAL's 36 mg is ~7× every other table; other minerals agree with USDA |
| `guacamole` | vitamin C 359 → 10.2 mg (USDA guacamole) | CIQUAL's prepacked guacamole has added ascorbic acid (antioxidant); home or bar guacamole does not |
| `pesto` | vitamin C 92.6 → 3.6 mg (USDA pesto) | same reason |
| all USDA foods with folic acid | folate = food folate only (e.g. toast, rolls, pizza, pancakes, lasagne) | US flour and cereals are fortified with folic acid, European ones are not |
| `sponge_cake`, `wrap` | iron not borrowed (left unknown) | their `fill=` donor is a US food made with enriched flour |
| recipe dishes | a nutrient is unknown when ingredients lacking it weigh ≥ 5% of the ingredients with energy (water and salt no longer dilute the share) | e.g. fabada's vitamin D (salted pork belly has none in the sources) is now unknown instead of a partial sum |

## The 14 new columns: sources and gaps

Values come from the same USDA/CIQUAL row as the other nutrients; recipe dishes add up their ingredients. Where
the main source lacks a value: 47 more foods (52 with those of 1.5.0) borrow it from a near-identical USDA food
(`fill=`, only the missing values;
fatty acids scaled to the food's own fat), e.g. serrano ham from FNDDS prosciutto, chorizo, fuet and mortadella
from USDA cured sausages, cheeses, raw meats, salmon, mackerel, sole, hummus, baked beans, mixed nuts, cream,
mayonnaise-type sauces, jam, popcorn, pizza; 4 dishes estimate them from their typical recipe (`fill=recipe`:
risotto, moussaka, panna cotta, puff pastry); and 45 foods get 0 for nutrients that kind of food does not contain
(`zero=`: cholesterol, vitamin D and B12 in plant foods and breads; vitamin C in fresh meat, fish and cream
cheese; magnesium in brandy).

Coverage (known values out of 595 foods): mono_fat 589, poly_fat 591, cholesterol 593, potassium 594, calcium 594,
iron 592, magnesium 594, zinc 594, vit_a 593, vit_c 589, vit_d 578, vit_e 577, vit_b12 591, folate 590 (and
sugars 592, saturated fat 594 of the v1 columns). Still empty because no open source has them: vitamin D of lamb,
rabbit, tripe, salted pork belly (and so fabada, cocido), bonbons and a few drinks; vitamin E of mackerel, broth,
popcorn, tart, smoothie, soft drinks and some soups; iron of sponge cake and wrap (only US enriched donors);
macarons (5 values); durian (sugars, saturated fat, fatty acids); chia seeds and banana bread (sugars).

## Ingredient lists (recipes.tsv)

An independent review of the tools (2026-11-02) recomputed every recipe dish before rounding: the listed ingredients
reproduce the dish exactly (the build now checks this too). It found that in typical recipes of USDA/CIQUAL dishes a
single ingredient could bring more of a nutrient than the dish has (fried rice: its oil 4 g of fat, the dish 3.2 g),
so "without oil" would have gone below 0. validate.py now rejects that for energy, protein, carbohydrate, fat,
saturated fat and salt, and 29 typical recipes were tuned to their source dish (less oil, rice, pasta, bread or meat;
veal escalope instead of round beef). Sugars, fibre and the micronutrients can still go slightly below 0 when an
ingredient is removed (the source dish and our typical recipe differ), so the app clamps adjusted values at 0.

## Open issues

- **Iron of USDA flour products** (FNDDS pizzas, pancakes, scones, garlic bread, focaccia…) still includes US
  enrichment (~4 mg per 100 g of flour); the folic acid was removed (above) but USDA does not publish the iron
  without enrichment. CIQUAL alternatives were used where they exist (breads, bagel, noodles).
- **Horchata**: still USDA rice horchata; there is no open data for Spanish tiger-nut horchata.
- **Ibérico ham** is an estimate (serrano ham with 20% pork fat); no open table has it.
- **Prepacked CIQUAL dishes kept** (quiche, moussaka, shepherd's pie, chili, Caesar salad, pasta salad, tabbouleh,
  risotto): within the usual range of home recipes; their typical recipes explain their energy within 25%.
- **Guacamole/pesto** keep CIQUAL's other values (prepacked products).
- CIQUAL reports "traces" of sugars in some cooked vegetables (cauliflower, courgette, green beans, spinach); kept as
  0, as EU labels do.
