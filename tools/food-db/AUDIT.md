# Food catalog audit (Fortia 1.6.0, 2026-11-02; additions of 1.8.0 at the end)

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

# 1.8.0 additions (2026-11-04)

595 → 917 foods (+322; 268 variants; 284 dishes with ingredients, 237 of them computed from their recipe; 351
model classes of food-model3's class list v3 are visual, 329 before). Every
1.7.0 id is kept; `whey_protein` moved from dairy to the new group `supplements` (same id and values). New portion
key `scoop` (the measuring scoop of a protein tub). Sources unchanged: USDA FoodData Central (Foundation, SR Legacy,
FNDDS) and CIQUAL 2025, the same downloads and checksums as 1.6.0, plus our recipes; no BEDCA, no Open Food Facts
data, no supermarket or brand websites.

## Counts per group and sources of the new rows

| Group | 1.7.0 | 1.8.0 | new rows: CIQUAL / USDA / recipe |
|---|---|---|---|
| dishes | 96 | 148 | 0 / 0 / 52 |
| sweets | 67 | 102 | 24 / 1 / 10 |
| drinks | 60 | 96 | 13 / 15 / 8 |
| meat | 57 | 81 | 11 / 6 / 7 |
| vegetables | 51 | 73 | 19 / 3 / 0 |
| fish | 37 | 68 | 13 / 2 / 16 |
| fruit | 44 | 58 | 12 / 2 / 0 |
| grains | 39 | 55 | 9 / 5 / 2 |
| fast_food | 30 | 49 | 0 / 0 / 19 |
| dairy | 31 | 43 | 12 / 1 / 0 |
| fats_nuts | 19 | 31 | 11 / 1 / 0 |
| snacks | 18 | 29 | 1 / 3 / 7 |
| legumes | 14 | 22 | 5 / 3 / 0 |
| sauces | 20 | 24 | 1 / 0 / 3 |
| eggs | 12 | 19 | 1 / 0 / 6 |
| supplements | 0 | 19 (18 new + `whey_protein` moved) | 4 / 6 / 8 |

- **Basics** (fruit, vegetables, legumes, cereals and flours, meat cuts and offal, fish and shellfish, cheeses and
  dairy, nuts, seeds, oils): CIQUAL first (European products, EU-style energy and available carbohydrate), USDA SR
  Legacy where CIQUAL lacks the food or a value (swordfish, cooked chicken liver, drumstick, minced pork/turkey,
  pinto and lupini beans, almond butter, oat and wholemeal flour, rice noodles, turnip greens, kimchi). Raw forms
  were added where recipes or people weigh them raw (raw salmon and tuna for poke and sushi, raw cod, cuttlefish,
  raw button mushrooms, baby spinach), cooked forms where people log them cooked.
- **Dishes** (Spanish regional and world): all computed from recipes of ours (`recipes.toml`, typical home or
  bar proportions, every nutrient from the USDA/CIQUAL food behind each ingredient), so Σ ingredients reproduces
  the row exactly before rounding and within rounding after it (validate.py, `RecipesTsvContractTest`). Every
  listed ingredient is adjustable except water, salt, spices, garlic, vinegar, lemon juice and broth.
- **Drinks and snacks**: generic values only — USDA "NFS"/generic rows or CIQUAL averages, never a brand's row
  (USDA's brand-named FNDDS/SR rows were not used); mixes (whisky-cola, vodka con naranja/limón, rebujito,
  agua de Valencia, cappuccino, té con leche) are recipes of their catalog drinks, like gin-tonic in 1.6.0.
- **Supplements** (`supplements`): USDA protein powder (whey, already in 1.6.0), CIQUAL soy protein (plant protein
  powder), USDA FNDDS generic high-protein ready-to-drink shake, generic nutrition bar (protein bar), electrolyte
  solution and sports drink powder, USDA defatted peanut flour (what peanut butter powders are made of), USDA
  unflavoured gelatin (collagen peptides have its composition), salmon oil (omega-3 capsule, CIQUAL with USDA's
  missing values), CIQUAL cereal bar with fruit (energy bar) and low-calorie ready-to-drink meal replacement;
  recipes: protein shake with milk / water / oat drink (30 g whey in 250 ml), mass gainer (whey + maltodextrin
  30 : 70, the usual composition), energy gel (USDA glucose syrup + water + salt); **documented compositions**
  (new `composition` entries, see README): creatine monohydrate (0 for every nutrient: it is not protein,
  carbohydrate or fat under EU labelling, and EU labels declare 0 kcal), BCAA/EAA (free amino acids counted as
  protein at 4 kcal/g; minerals and vitamins unknown), maltodextrin (95 g carbohydrate, 380 kcal; sugars unknown,
  they depend on the dextrose equivalent).

## Checks on every new row (same rules as 1.6.0)

- validate.py: energy ≈ 4P + 4C + 9F + 2·fibre within max(25 kcal, 20%) (warnings above max(15 kcal, 12%)), sugars ≤
  carbohydrate, saturated (and saturated + mono + poly) ≤ fat, salt = sodium × 2.5, micronutrient upper bounds,
  portions, variants, names. New rows outside the 12% band: only redcurrants (organic acids, like vinegar) and the
  alcoholic drinks (listed in validate.py's ALCOHOLIC: their alcohol energy is not a column).
- Recipes: build.py's exact self-check and validate.py's rounding check for all 133 new listed recipe dishes (the energy gel and the four compositions are values only).
- Plausibility review of every new row's values per 100 g and per default portion against typical labels and the
  1.7.0 neighbours (e.g. marmitako 80 kcal, cachopo 323, poke bowl 145, biryani 181, protein shake 84/100 ml).
- Search: `test_fooddb.SearchTest` checks ~170 common Spanish and English searches against the names with the
  app's own normalisation and ranking tiers (FoodSearch.kt), and that no two foods share a name in a language.

## Values chosen or corrected while adding

| Id | Choice | Why |
|---|---|---|
| `chicken_liver` | USDA cooked chicken liver (vitamin A 3,980 µg) instead of CIQUAL poultry liver | CIQUAL's 18,700 µg RAE is above validate.py's bound and ~4× USDA; the other values agree |
| `fish_oil` | CIQUAL salmon oil (900 kcal), missing values from USDA salmon oil | USDA oils are 902 kcal per 100 g, above the 900 kcal bound |
| `cooked_wine` (new ingredient) | CIQUAL "wine, cooked (alcohol evaporated)", 15 kcal/100 ml | stews and sauces simmer the alcohol away; counting raw wine (75-81 kcal) overstated rabo de toro by ~20 kcal/100 g. Used in every 1.8.0 dish with wine |
| `oxtail` | CIQUAL boiled oxtail, missing values from braised beef short ribs | the source has no salt, minerals or vitamins; short ribs are the closest fatty braised cut |
| `orange_soda` | USDA orange soda, sugars from CIQUAL's fruit soft drink | USDA has no sugars value |
| `cardoon`, `cuajada`, `lactose_free_milk`, `cassava`, `liquorice` | missing values borrowed (`fill=`) from raw cardoon, whole cow's milk, semi-skimmed milk, raw cassava, jelly sweets | the source lacks them; the donors are the closest foods |
| `cecina`, `butifarra`, `flat_peach`, `veal_fillet` | CIQUAL Grisons dried beef, Toulouse sausage, peach, veal chop | no open table has the Spanish product; these are the same kind of food (said in mapping.txt's note) |
| 25 plant foods, oils and drinks | `zero=` for vitamin B12 / D, cholesterol, fatty acids they do not contain | as in 1.6.0 |
| `mandarin`, `coffee_with_milk`, `roasted_peppers`, `fresh_cheese`, `croissant` (names only) | "Mandarina o clementina", "Café con leche o latte", "Pimientos asados o del piquillo", "Queso fresco (de Burgos)", "Cruasán o croissant" (EN "Mandarin or clementine", "Coffee with milk or latte") | the app has no synonyms: these common searches found nothing |

## Class list v3 (food-model3, 2026-11-04)

351 classes = the 329 of v2 + 22 new. Twelve existing rows and nine 1.8.0 rows became visual; `baklava` got a row
(CIQUAL baklava with almonds and syrup, 459 kcal; FNDDS baklava has a third of the sugars of a syrup-soaked one);
`patatas_bravas` (of french_fries) and `marshmallows` (of candy) are no longer variants, since a class cannot be one.
notes.md lists them. The visual column was synced with `build.py --sync-visual` from a copy of the published
`food_classes_v3.csv` (its `id` and `status` columns; no class is dropped).

## Review fixes (independent data review of the branch, 2026-11-04)

| Id | Old → new | Why |
|---|---|---|
| `sherry` | USDA dry dessert wine 150 kcal, C 11.7 → documented composition 87 kcal/100 ml, C 1, S 0.5 | fino and manzanilla are dry and 15% vol; USDA's "dry dessert wine" is a sweeter, stronger wine. `rebujito` follows: 73 → 55 kcal/100 ml |
| `cantaloupe` | CIQUAL 60 kcal, C 14.8 → USDA cantaloupe 34 kcal, C 7.9; no longer a variant of melon | CIQUAL's value is implausible at 84% water; the new values equal melon's |
| `apple_turnover` | ES "Napolitana o lazo de manzana" → "Hojaldre de manzana (lazo)" | a napolitana in Spain is the chocolate one (`pain_au_chocolat`) |
| `ginger` | CIQUAL 33 kcal, C 3.4 → USDA ginger root 80 kcal, C 15.8 | CIQUAL's is far below every other table for the fresh root |
| `green_tea` | CIQUAL 5 kcal, C 1.1 → USDA brewed green tea 1 kcal; no longer a variant of tea | brewed tea has ~no energy |
| `prickly_pear` | CIQUAL 52 kcal, S 1.0 of C 9.8 → USDA 41 kcal, sugars unknown | CIQUAL's sugars looked wrong; USDA has none |
| `baked_apple` | fibre 0 → unknown; no longer a variant of apple | CIQUAL "< 1" contradicts the 2.5 g of raw flesh; its values equal apple's |
| `protein_bar`, `protein_shake_rtd`, `flavoured_water`, `isotonic_powder` | added vitamins and minerals → unknown (`unknown=`, new option) | the USDA generic rows are US fortified products (bar: vitamin A 1,050 µg, C 120 mg per 100 g) |
| `horse_mackerel` (new, with `millet`, `buckwheat`) | CIQUAL autumn-winter horse mackerel, missing values from USDA jack mackerel | CIQUAL's average row has more saturated + mono + poly fat than fat |
| `peanut_butter_powder` | name "Peanut butter powder" → "Defatted peanut flour (peanut powder)" | the values are those of defatted peanut flour (0.6 g fat); powders that keep fat have ~12 g |
| `sweet_wine`, `heavy_cream`, `duck_meat`, `chocolate_wafer`, `hard_candy`, `sandwich_cookies`, `dark_beer` | names: "(moscatel)" only, "(30%)", no "(magret)", "Barquillo con chocolate", "Boiled sweets", "biscuits", "Cerveza negra" | the name said more than the values (PX, 35%) or read badly; "tostada" made dark beer a hit for toast |
| `venison` | no longer a variant of beef steak | not beef; like wild boar |
| `bcaa`, `provolone` | scoop 10 → 7 g; default portion 100 → 20 g (a slice; the grilled 100 g stays a portion) | usual servings |
| recipes | parsley, basil and ginger not adjustable (like spices); yakisoba's 0.4 g of sugar not adjustable; kung pao's spring onion adjustable (a vegetable there, as onion) | tiny amounts are not worth a control |

Kept as variants although their values are close to the base (the reviewer suggested plain foods): cheeses under
`cheese` (gouda, emmental, cheddar, provolone), `chocolate_donut` under `donut`, and the other close pairs (soups, rice
dishes, croquettes, cubatas, basmati rice). Variants are also the list the app offers when the base is recognised
in a photo or picked (contract 2), which is how 1.6.0 linked edam, cheese slices and the milks; there the user picks
what they are eating even when the energy is similar. `tonic_zero` stays a variant of `soft_drink`: `tonic_water`
is itself a variant of it and a variant cannot be a base, so both tonics are listed together under soft drinks.

## Coverage of the new rows (known values out of 322)

kcal, protein, carbs, fat, saturated fat, salt 322; fibre 321; sugars 317; mono_fat 321, poly_fat 321,
cholesterol 321, potassium 318, calcium 315, iron 316, magnesium 314, zinc 315, vit_a 316, vit_c 311, vit_d 311,
vit_e 292, vit_b12 312, folate 311. All 917 rows: sugars 909, sat_fat 916, fibre 916, mono_fat 910, poly_fat 912,
cholesterol 914, potassium 912, calcium 909, iron 908, magnesium 908, zinc 909, vit_a 909, vit_c 900, vit_d 889,
vit_e 869, vit_b12 903, folate 901.

Still unknown (no open source has them): sugars of lupini, strong beer, prickly pear, maltodextrin and so the
gainer; fibre of baked apple; the added minerals and vitamins of the USDA protein bar, ready-to-drink shake and
flavoured water (US fortified products, `unknown=`); the
minerals and vitamins of maltodextrin, gainer and BCAA; vitamin E of broth (and so the rice dishes, soups and stews
made with it), of palm hearts, red lentils, canned fruit; a few values of tonic zero, cachopo, langoustines,
oat bran and maize flour (folate).

## Not added, and why

- **Casein, pre-workout, protein pudding**: no USDA/CIQUAL row and no composition that can be documented without a
  brand's label (casein powders and pre-workouts vary widely); users can scan the product's barcode (Open Food
  Facts) or create a custom food. Whey covers the usual protein powder.
- **Carrilleras, torreznos, lacón, lomo embuchado, chistorra, ibérico cuts (secreto, presa)**: no open values for the
  cut or product; recipes would only shift the guess (torreznos: the fat lost in the pan is not modelled).
- **Sweetener sachets, sugar-free gum**: only brand-named USDA rows; gum's polyol energy is not a column.

## Open issues (1.8.0)

- **Generic USDA rows that come from one product** (FNDDS "nutrition bar, NFS", "high-protein shake, NFS",
  "energy drink, sugar free"): USDA's generic codes, not a brand's entry, but their values were taken by USDA from a
  representative product; real bars and shakes vary ±20%.
- **Textured soy (FNDDS)** lists all its carbohydrate as sugars (16 g); kept as published.
- **Energy gel** sugars are those of glucose syrup (65 g); maltodextrin gels have the same energy and fewer sugars.
- **Canned mackerel** has CIQUAL's values in brine; Spanish tins in olive oil, drained, have ~2 g more fat.
- **Strong beer** is CIQUAL's > 8% vol; a 5-6% craft beer is between it and `beer`.
- **Desalted cod** dishes use fresh cooked cod plus some salt (CIQUAL's boiled salt cod keeps 5.8 g of salt, far
  more than a properly desalted piece).
