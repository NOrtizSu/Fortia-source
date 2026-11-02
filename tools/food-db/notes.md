# food-db notes: decisions and caveats

## Sources, in order of preference

1. **CIQUAL 2025** for foods as eaten in Europe: European products (bread, cheeses, charcuterie, yogurts, drinks,
   French/European dishes), EU energy (Regulation 1169/2011 factors) and measured available carbohydrate, so the
   values read like an EU label.
2. **USDA SR Legacy / Foundation** for single foods CIQUAL lacks or has incomplete (avocado, melon, chickpeas,
   black beans, edamame, margarine, cottage cheese, whey, chard, tripe…).
3. **USDA FNDDS 2019-2020** for dishes CIQUAL lacks (fried chicken, burrito, tacos, pad thai, pho, churros,
   pancakes, eggs Benedict…) and to cross-check portion weights.
4. **Recipes** (`recipes.toml`) for typical Spanish dishes that neither table has as such, and for mixes
   (coffee with milk, gin and tonic, a charcuterie board). Ingredient amounts are typical home or bar recipes
   (our own composition); every nutrient comes from a USDA/CIQUAL ingredient. Cooking yields are estimates
   (water lost when frying, roasting or simmering; water absorbed by legumes, rice, pasta); nutrients are kept,
   so a little fat lost in the pan or the broth is still counted (slight overestimates, e.g. cocido).

BEDCA and websites/apps with unclear licences are not used.

## Data cleaning

- Salt = sodium × 2.5 (also for CIQUAL, which publishes its own salt column).
- CIQUAL "< x" (below the quantification limit) and "traces" → 0, as EU labels declare such amounts.
- USDA carbohydrate "by difference" − fibre = available carbohydrate; when that comes out below the measured
  sugars (USDA sauerkraut), carbohydrate = sugars.
- Missing nutrients stay empty (= unknown in the app): durian (sugars, saturated fat), chia seeds, banana bread
  and roast lamb (sugars). A few rows borrow a missing nutrient from a near-identical food (`fill=`): peaches in syrup,
  wheat flour, dry white beans, turkey breast, tzatziki.
- Drinks are per 100 ml: values per 100 g × density (milk 1.03, juices 1.04, beer 1.01, wine 0.99, spirits 0.95…).

## Energy check

`build.py` compares the source energy with 4·protein + 4·(carbs − polyols) + 2.4·polyols + 9·fat + 2·fibre
+ 7·alcohol + 3·organic acids and lists foods off by more than max(15 kcal, 12%). With the current sources no food
is listed. `validate.py` (which has no polyols/organic acids/alcohol columns) warns for two foods whose energy
comes partly from those: dried apricots (22.6 g polyols) and balsamic vinegar (acids); it lets only the alcoholic
drinks it lists exceed the macronutrient energy. Outliers and implausible values found and resolved:
- CIQUAL avocado: carbohydrate 0 but sugars 0.4 → USDA Hass (California) avocado.
- CIQUAL honeydew melon (22 kcal) looked low for Spanish piel de sapo → USDA honeydew (36 kcal).
- CIQUAL "saucisson pur porc cuit" was a cooked-sausage type with no saturated fat → chipolata (fresh pork
  sausage), cooked.
- USDA sauerkraut sugars > available carbohydrate → rule above.
- CIQUAL cooked wholegrain rice (187 kcal, 54% water) was drier than cooked white rice → USDA cooked brown rice
  (123 kcal).
- FNDDS pakora (125 kcal, 4.8 g fat) too lean for a deep-fried food → recipe (~283 kcal, 19 g fat).
- FNDDS bibimbap (81 kcal) mostly vegetables → recipe with rice, beef and a fried egg (~139 kcal).
- Steamed FNDDS dumplings (113 kcal) for a class shown mostly as gyoza → pan-fried pot stickers (192 kcal).
- CIQUAL dry white wine (55 kcal/100 ml, ~9.5% vol) → USDA white table wine (~81 kcal/100 ml, ~11.5%).
- CIQUAL egg custard tart (211 kcal) was a French flan pâtissier → pastel de nata recipe (~300 kcal).
- `creme_brulee` keeps CIQUAL crème brûlée (cream, 269 kcal); crema catalana (milk, ~165 kcal) is its own recipe.
- `squid` (a la plancha) had CIQUAL "fried or pan-fried with fat" values (8.5 g carbs, a coated fry) → recipe of raw
  squid griddled with a little olive oil (127 kcal). Its 2.4 g carbohydrate is CIQUAL's 2.2 g in raw squid,
  concentrated by the water lost on the griddle.
- CIQUAL lemon had no fibre → USDA lemon without peel (2.8 g). CIQUAL marinated anchovies in oil → boquerones en
  vinagre recipe. CIQUAL steamed sole instead of boiled (15 g protein looked raw). Roast lamb leg with its fat
  (USDA, 225 kcal) instead of lean only; bacon cooked (USDA pan-fried) because the class photos and the portion
  are cooked rashers. Chips and tempura salted as served (recipes).

## Portions

Spanish servings chosen by hand, cross-checked against USDA FNDDS/SR portion weights for countable items (egg 50 g,
banana 118 g → 120 g, croissant 57 g → 60 g, pretzel 120 g, burrito 220 g, taco 105 g, pancake 50 g, cinnamon roll
90 g, eggs Benedict 155 g…). Spanish sizes where they differ: churro 20 g (FNDDS 45 g is the long American one),
can 330 ml, caña 200 ml, tercio 330 ml, glass of wine 150 ml, cava 120 ml, spirits 50 ml (Spanish pour), slice of
barra 30 g, sliced bread 25 g, plate of lentils/fabada 350 g, croqueta 30 g, whole tortilla 1250 g (the recipe's
yield: 1 kg potatoes, 6 eggs) and a slice 156 g (1/8), olive oil tablespoon 12 g, yogurt pot 125 g, tin of tuna
56 g drained; beer defaults to a caña (200 ml). Rice and pasta plates are 80 g dry turned into cooked weight with
the sources' own values (rice 180 g, pasta 175 g, brown rice 230 g, wholemeal pasta 160 g), so a plate and 80 g of
the dry food log the same energy. Cooked weights for meat and fish (edible part). The default portion is the one people log most:
one piece for fruit and countable foods, a plate/bowl for dishes, a slice for bread, cold cuts and cheese.

## Model classes (visual = 1)

The 300 class ids of the food-model's final list (`food_classes.csv` of 2026-11-01 = `tools/food-model/classes.csv`;
`_not_food` has no row) are rows with `visual = 1`, set by `build.py --sync-visual <classes.csv>`. Rerun it when the
list changes: it fails if a class has no row yet, and a class that is dropped simply gets `visual = 0` (the food
stays in the catalog). Candidate classes of the model's draft that did not reach the image threshold are kept as
ordinary foods: raisins, quince_paste, leek, kale, grilled_vegetables, baked_beans, avocado_toast, pain_au_chocolat,
brown_rice, deviled_eggs, black_pudding, carpaccio, feta, goat_cheese, turron, patatas_bravas, onion_rings, arancini,
tabbouleh, minestrone, french_onion_soup, clam_chowder, moussaka, spaghetti_bolognese, cannelloni, horchata,
kumquat, mille_feuille, shepherds_pie; dropped from the first published list after de-duplication: celery,
chickpeas, edamame, pork_belly, sauerkraut; and dropped because their Commons photos mostly showed other foods:
almonds, cashews, chestnuts, chocolate_spread, cider, dates, ham, hazelnuts, honey, peanuts, pistachios, sardines,
walnuts (`nuts` stays visual for mixed nuts); and dolma (its Open Images label turned out to be grapevines). Merged classes get the typical Spanish version (`peach` = peach, the class also
covering nectarines; `wrap` = Spanish ham wrap, `bread_with_tomato` = pan con tomate, `lentils` = lentejas,
`bean_stew` = fabada, `fritters` = buñuelos, `fruitcake` = panettone, `sangria` = sangría, `custard` = natillas,
`french_toast` = torrija, `ratatouille` = pisto, `potato_salad` = ensaladilla rusa). `creme_brulee` is crème brûlée;
the Catalan dessert is the separate `crema_catalana`.

## Broad classes and vague foods: which food stands for the id

| Id | Values used |
|---|---|
| `iberico_ham` | **estimate**, no open table has ibérico ham: recipe of serrano ham with 20% pork back fat (337 kcal, 26 g fat) for its marbled, fattier cut; `cured_ham` = serrano |
| `paella` | recipe: Spanish paella mixta (~170 kcal); CIQUAL's French home-made paella (135 kcal, 2.2 g fat) has less oil |
| `bread` | white country-style loaf (CIQUAL pain de campagne); `baguette` = barra |
| `cheese` | semi-cured firm ewe's milk cheese (queso semicurado) |
| `milk` | semi-skimmed milk (the most drunk in Spain); whole and skimmed are separate foods |
| `yogurt` | plain whole-milk yogurt |
| `potato` | boiled potato (photos are mostly cooked potatoes) |
| `lentils` | Spanish lentil stew with chorizo (class "lentil stew / dal"); plain boiled lentils = `cooked_lentils` |
| `beans` | boiled white beans; `bean_stew` = fabada; meatless `potaje_alubias` |
| `cereal` | breakfast cereals, average, dry (milk logged separately) |
| `noodles` | stir-fried noodles (lo mein) |
| `pilaf` | rice pilaf (biryani is close: 104-145 kcal) |
| `steak` | grilled sirloin steak |
| `grilled_chicken` | grilled chicken breast without skin |
| `sausages` | fresh pork sausages (chipolata), cooked; frankfurters separate |
| `meatballs` | Spanish albóndigas with their tomato sauce |
| `breaded_cutlet` | breaded veal escalope (also san jacobo, cachopo, flamenquín) |
| `skewers` | pork pinchos morunos |
| `mixed_grill` | recipe: chicken, ribs, lamb chops, chorizo, morcilla, sausage |
| `suckling_pig` | roast pork shoulder, lean and fat (no open data for cochinillo) |
| `cold_cuts` | recipe: ham, chorizo, salchichón, cured cheese |
| `grilled_fish` | white fish, cooked (CIQUAL average) |
| `fried_fish` | recipe: hake in flour fried in olive oil (pescaíto) |
| `anchovies` | boquerones en vinagre; salted canned anchovies are `canned_anchovies` |
| `octopus` | pulpo a la gallega without potatoes |
| `calamari` | calamares a la romana (fried); grilled squid is `squid` |
| `prawns` | boiled prawns; `gambas_al_ajillo` separate |
| `sushi` | CIQUAL sushi/maki with seafood |
| `sashimi` | recipe: raw salmon and tuna 1:1 |
| `brie` | brie (camembert has ~20% less energy) |
| `cottage_cheese` | cottage cheese 2% (ricotta/requesón similar) |
| `nuts` | mixed nuts, roasted, salted |
| `olives` | green olives in brine |
| `cake` | cake, average (CIQUAL) |
| `cookie` | butter biscuits with chocolate; María biscuits separate |
| `muffin` | magdalena values; a large muffin is the `piece` portion |
| `fruit_pie` | apple pie |
| `tart` | fruit tart, average |
| `custard_tart` | egg custard tart (pastel de nata) |
| `candy` | jelly sweets (gominolas) |
| `chocolate` | chocolate bar, average |
| `bonbons` | chocolates filled with nuts/praline |
| `ice_cream` | ice cream in a tub, one scoop 60 g |
| `turron` | recipe: hard Alicante turrón (soft Jijona is similar in energy) |
| `french_toast` | Spanish torrija (recipe) |
| `pancakes` | plain American pancakes (tortitas), without syrup |
| `soup` | vegetable soup (CIQUAL) |
| `cream_soup` | recipe: zucchini cream soup (stands for vegetable creams) |
| `stew` | recipe: Spanish beef stew with potatoes |
| `curry` | chicken curry without rice |
| `green_salad` | recipe: lettuce, tomato, cucumber, onion, olive oil and vinegar |
| `potato_salad` | Spanish ensaladilla rusa (recipe) |
| `sandwich` | sandwich, average (CIQUAL); `baguette_sandwich` = bocadillo, average |
| `wrap` | wrap with ham and raw vegetables (CIQUAL); a burrito is similar |
| `kebab` | döner in pita with vegetables |
| `pizza` | pizza, average |
| `hamburger` | fast-food hamburger in a bun |
| `empanada` | Galician tuna empanada values (empanadillas similar) |
| `croquettes` | ham croquettes (recipe) |
| `canapes` | recipe: four assorted montaditos |
| `bread_with_tomato` | pan con tomate (bruschetta similar) |
| `coffee_with_milk` | recipe: café con leche, semi-skimmed, no sugar (cappuccino/latte similar) |
| `espresso` | espresso without sugar |
| `hot_chocolate` | Spanish chocolate a la taza (recipe) |
| `juice` | multifruit juice, pure |
| `soft_drink` | fruit-flavoured soft drink with sugar; cola is `cola` |
| `cocktail` | rum-based cocktail (CIQUAL) |
| `sangria` | sangría (tinto de verano and kalimotxo are lighter: see `tinto_de_verano`) |
| `spirits` | whisky values |
| `horchata` | **USDA horchata (rice-based, Latin American)**: there is no open data for Spanish tiger-nut horchata (similar energy, ~3 g more fat per 100 ml). To revisit if an open source appears. |
| `dried_fruit` | dried fruit, average |
| `mushrooms` | button mushrooms, cooked |
| `lettuce` | lettuce, plain (no dressing) |
| `bell_pepper` | raw sweet pepper, green/red/yellow average |
| `seaweed` | seaweed, raw (FNDDS); dried seaweed is ~7x more concentrated |
