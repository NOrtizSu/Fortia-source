# Model card: Fortia food recognizer (`food_model.tflite`)

## Summary

| | |
|---|---|
| What | Image classifier: photo of a meal → probabilities over **300 foods/dishes + `_not_food`** (`classes.csv`, in output order) |
| Use in the app | Comida tab: the top 5 are shown as suggestions; **the user always confirms** (or searches). Runs on the phone, offline; the photo never leaves the device. |
| Architecture | EfficientNet-Lite0 (3.8 M parameters with this head), 224 × 224 RGB input, new 301-way linear head |
| Starting weights | ImageNet-1k EfficientNet-Lite0 by Google (Apache-2.0), via timm `tf_efficientnet_lite0.in1k` |
| Training photos | 127 281 photos (Open Images V7 96 503, CC BY 2.0; Wikimedia Commons 30 778, CC0/PD/CC BY/CC BY-SA) |
| File | 4.3 MB (4 343 160 bytes), TFLite with int8 weights and activations, float32 input/output (LiteRT) |
| Test accuracy | **top-1 50 %, top-5 77 %** (int8 TFLite: 49.8 % / 77.2 %) on 10 157 held-out photos |
| Trained | 2026-11-01/02, `tools/food-model/train.py` (runs `run2` + `run2b`, then `drop_classes.py` → `final`) |

## Intended use and limits

- Meant for: one dish or one food per photo, taken from above or at an angle, as people photograph their
  plate. The answer is a **suggestion**; nutrients come from the food catalogue and the portion the user picks.
- Not meant for: estimating portions or calories from the photo (it doesn't), mixed plates with several
  dishes (it names one of them at best), packaged products (use the barcode), drinks in opaque cups, medical
  or dietary decisions.
- Classes are visual groups: several similar foods share one class when they look alike or have nearly the
  same nutrients (e.g. `peach` = peach/nectarine, `wrap` = wrap/burrito, `bread_with_tomato` = pan con tomate /
  bruschetta, `cured_ham` = jamón serrano/prosciutto, `lentils` = lentil stew/dal, `fruitcake` = panettone/plum
  cake). The catalogue gives each the typical Spanish version.
- `_not_food` is learnt from photos verified to contain no food (people, places, objects; a fifth of them with
  tableware, a table, a kitchen or packaging in view). The app also says "no food recognised" when the best food
  probability is below `min_confidence`.

## Data

**Sources (licence-clean only).** Open Images V7 human-verified image-level labels (photos listed as CC BY 2.0
by their Flickr authors) and Wikimedia Commons food categories (only files whose licence is CC0, public domain,
CC BY or CC BY-SA). No research-only datasets (Food-101, UEC-Food, ISIA, Food2K…). Every photo used is listed
with author, licence and source page in `docs/third-party/food-model/images.csv.gz`.

**Class selection.** ~350 candidates useful to a Spanish/European user first, then global
(`classes_config.py`), each mapped to the Open Images labels and Commons categories that show it. Open Images
photos often carry several food labels; a rank decides (specific dish > dish > single food > ingredient) and
photos with two labels of the same rank are not used. Commons files filed under two classes go to the more
specific one. Commons categories also contain plants, markets, illustrations and old catalogue scans: only
photos ≥ 240 px are kept, nearly colourless ones (scans, engravings, black-and-white photos) are dropped, and
for single foods the "… dishes"/"… soup"/"… cake" subcategories are skipped. After review, the Commons photos
of honey, dates, nuts, chocolate spread, ham, sardines and cider were removed (mostly dishes or sweets that
merely contain the food).

**Dataset.** Near-duplicates (64-bit difference hash, Hamming ≤ 4) are merged so that no near-identical photo
sits in two splits; groups with conflicting labels are dropped. At most 1 000 photos per class (4 000 for
`_not_food`), split per class into test 8 % (min 12), val 5 % (min 8) and train. Classes with fewer than 150
training photos were dropped (47 candidates, e.g. raisins, leek, kale, celery, chickpeas, edamame, sauerkraut,
pork belly, black pudding, feta, goat cheese, nuts by kind, honey, turrón, patatas bravas, onion rings,
arancini, minestrone, French onion soup, moussaka, spaghetti bolognese, cannelloni, horchata, cider,
mille-feuille; they stay in the food catalogue as non-visual foods).

| Split | Photos |
|---|---|
| train | 110 730 |
| val | 6 363 |
| test | 10 188 (10 157 without `dolma`, see below) |

Photos per class: median 340, minimum 186 (the smallest: calzone, danish pastry, chicken soup, pulled pork,
couscous, ~190 each), maximum 1 000; `_not_food` 3 765.

**Removed after training: `dolma`.** Its Open Images label ("Grape leaves") turned out to be photos of
grapevines, not stuffed vine leaves (found through the grapes → dolma confusions). Its output row was cut from
the classifier (`drop_classes.py`; the other outputs are unchanged) and its test photos are not counted.

## Training

- timm `tf_efficientnet_lite0.in1k`, dropout 0.2, stochastic depth 0.1; static TF-style "SAME" padding for
  export.
- 25 epochs, batch 64, AdamW (lr 3e-4, weight decay 0.02, 1 warm-up epoch, cosine to 1 %), label smoothing
  0.1, gradient clipping 5, mixed precision (fp16), weight EMA (0.9998) — the checkpoint with the best val
  top-1 (EMA or raw) is kept.
- Square-root class-balanced sampling (each photo drawn Poisson(n_class^-0.5 · k) times per epoch).
- Augmentation for phone photos: random resized crop (25–100 % of the area, aspect 3:4–4:3), horizontal flip,
  random 90° rotations (top-down plates), TrivialAugmentWide (brightness, colour, contrast, sharpness,
  posterize, small rotations/shears…), random erasing.
- RTX 2060 SUPER, ~3.7 min per epoch (`run2`, best val top-1 46.7 % at epoch 13, EMA weights).
- `_not_food` follow-up (`run2b`): 3 more epochs from that checkpoint (lr 1e-4, EMA 0.999) sampling `_not_food`
  photos 6 × more often — it had caught only 44 % of the non-food test photos. Then `drop_classes.py` removed
  `dolma` and added −1.0 to the `_not_food` logit, the balance chosen between catching non-food photos and not
  rejecting food photos (see below).
- Calibration: temperature scaling fitted on val (NLL): **T = 0.94**. The model was already well calibrated
  (expected calibration error on test 0.023 before, 0.028 after — T ≈ 1 either way).

## Results (held-out test split, 10 157 photos)

PyTorch model, the app's preprocessing:

| | top-1 | top-5 |
|---|---|---|
| All test photos (300 foods + `_not_food`) | **50.5 %** | **77.3 %** |
| Food photos only (9 856) | 50.1 % | 76.9 % |
| Spanish / southern European dishes and foods (61 classes, 1 952 photos) | 46.9 % | 76.5 % |

Per food group (top-1 / top-5, test photos): vegetables 62.7 / 81.1 (1 023) · fish 59.9 / 82.2 (494) ·
fruit 59.6 / 77.9 (1 333) · fast food 51.1 / 81.7 (597) · eggs 51.0 / 77.5 (204) · snacks 50.6 / 74.7 (316) ·
sweets 50.3 / 76.0 (1 631) · drinks 47.9 / 79.4 (922) · dishes 44.9 / 76.9 (1 594) · legumes 44.6 / 77.0 (74) ·
meat 41.7 / 75.3 (667) · dairy 39.7 / 66.8 (232) · fats & nuts 36.8 / 61.8 (68) · sauces 34.6 / 64.7 (133) ·
grains 32.8 / 67.8 (568).

Per class: median top-5 77.8 %; 29 classes ≥ 90 % top-5, 3 below 50 % (custard, bread, cottage cheese).
Some Spanish ones (top-1 / top-5): flan 76 / 95, coffee with milk 75 / 91, croquettes 59 / 90, paella 64 / 89,
breaded cutlet 54 / 86, baguette sandwich (bocadillo) 41 / 85, suckling pig 75 / 83, lentils 30 / 82, bean
stew (fabada) 59 / 82, churros 63 / 81, chorizo 36 / 79, Spanish omelette 48 / 78, calamari 48 / 78,
octopus 55 / 77, cured ham 43 / 77, fried fish 54 / 75, sangria 29 / 74, gazpacho 24 / 71, custard
(natillas) 7 / 37.

Worst 30 classes by top-1 (top-1 / top-5): custard 7/37, bread 9/45, cupcake 10/62, nuts 10/55, lamb 11/54,
garlic bread 12/59, cottage cheese 12/38, soft drink 13/61, soup 17/70, toast 18/52, sautéed potatoes 18/52,
juice 19/52, mayonnaise 19/61, green salad 19/61, pilaf 20/70, pasta 21/84, mozzarella 22/56, stew 22/52,
cream cheese 22/56, focaccia 22/61, hot chocolate 22/85, guacamole 23/54, dulce de leche 23/71, gazpacho 23/71,
tortilla chips 24/62, tandoori chicken 24/71, flour tortilla 24/52, sweet pie 24/64, crisps 26/58, danish
pastry 27/67. Generic classes (bread, soup, salad, pasta, stew, juice) lose top-1 to their specific
neighbours (wholemeal bread, chicken soup, Caesar salad, carbonara…), which is why their top-5 stays higher.

Main confusions (test photos): orange → mandarin 14, pasta → carbonara 14, orange → not food 12, cake → not
food 12, sausages ↔ chorizo 10 + 8, baguette sandwich → hamburger 10 / → sandwich 10 / → hot dog 9,
sandwich → baguette sandwich 9, pasta → pasta salad 9, cheese → parmesan 9, sorbet → ice cream 9, soup →
chicken soup 9, noodle soup → pho 9, juice → not food 9, bread → wholemeal bread 8, wholemeal bread → loaf
cake 8, pad thai → noodles 8, spirits → beer 8, soft drink → cola 8.

**Not food** (301 non-food test photos): `_not_food` wins on 61.8 % of them (precision 50.4 %), and on 1.9 % of
the food photos (a third of those still have the right food in the top 5). With `min_confidence` = 0.05 the
app adds no further rejections on this test set (the best food probability is under 0.069 for 1 % of the food
photos; 0.10 would reject 3.9 % of the food photos and catch 65.8 % of the non-food ones). Trade-off of the
`_not_food` logit shift, test set: −0 → 76 % / 3.0 %, −1.0 (chosen) → 62 % / 1.9 %, −2.0 → 40 % / 1.0 %
(non-food caught / food photos rejected).

## Export and runtime

ONNX (opset 17, the TF-style padding folded into the convolutions) → onnx2tf 1.28.8 / TensorFlow 2.21 →
TFLite, int8 calibrated on 500 val photos (per-channel). Checked with `parity.py` on all 10 157 test photos,
preprocessing written from `food_model.json` (PIL + numpy), LiteRT interpreter (XNNPACK), on the training
PC's desktop CPU (not a phone):

| Variant | Size | top-1 | top-5 | same top-1 as PyTorch | 1 thread | 4 threads |
|---|---|---|---|---|---|---|
| PyTorch (reference) | — | 50.46 % | 77.26 % | — | — | — |
| float32 | 15.0 MB | 50.46 % | 77.27 % | 99.7 % | 21.7 ms | 8.3 ms |
| float16 | 7.5 MB | 50.43 % | 77.29 % | 99.5 % | 22.9 ms | 8.4 ms |
| dynamic-range int8 | 4.1 MB | 50.13 % | 77.01 % | 94.4 % | 52.6 ms | 69.6 ms |
| **int8 (shipped)** | **4.3 MB** | 49.83 % | 77.15 % | 92.3 % | **14.8 ms** | **6.6 ms** |

The shipped int8 model keeps top-5 within 0.1 point of PyTorch and is the smallest fast variant. On a
mid-range phone (Cortex-A76/A55 class, 4 threads, XNNPACK) EfficientNet-Lite0 int8 typically runs in
~15–40 ms; the app's emulator test of the smoke model measured 16 ms for the model.

Preprocessing (in `food_model.json`, the app follows it): RGB, resize the short side to 256 (long side =
floor(long × 256 / short), bilinear, antialiased), centre crop 224, scale to 0..1, `(x − 0.5) / 0.5`, NHWC
float32. Output: logits; probabilities = softmax(logits / temperature).

## Known limitations and biases

- **Label noise.** Open Images labels are human-verified but multi-label; the single label per photo comes from
  the rank heuristic. Commons categories are curated by volunteers and include photos where the food is not
  the main subject. Test labels carry the same noise, so the test numbers are a rough estimate.
- **Where the photos come from.** Mostly Flickr (2005–2015) and Commons, with a North-American/European
  skew; restaurant and market photos are over-represented compared with home cooking. Spanish dishes had few
  photos (e.g. Spanish omelette, gazpacho, croquettes, churros ~200–300 each) and several had to be dropped
  (patatas bravas, turrón, horchata…).
- **Look-alikes.** Orange/mandarin, sausages/chorizo, the sandwich classes, plain pasta/pasta dishes, the
  soups, the noodle classes, sorbet/ice cream and white cheeses are often confused (see the confusion list);
  the top 5 usually contains the right one. Generic classes (bread, soup, salad, pasta, juice) are the weakest
  at top-1.
- **One food per photo.** Mixed plates, several dishes on a table, or a dish with large sides get one label.
- **Not food.** `_not_food` catches about 6 in 10 non-food photos; the rest get (low-confidence) food
  suggestions, more often when the photo has food-like colours or shapes (fruit trees, drinks bottles, shop
  shelves, food drawings). About 2 in 100 food photos are called "not food" (whole cakes, oranges, juices most
  often) — the search box is the way out.
- **Training data mistakes found late.** `dolma` (grapevines) was removed after training; the training set
  still contained those photos, which only taught the model that grapevines are "dolma" (now hidden, so they
  go to grapes). Other classes may hide similar label problems; the worst-class list above is where to look.
- The model knows only these 301 classes; anything else gets the most similar class — hence the search box.

## Reproduce

See `tools/food-model/README.md`. Data, virtualenvs and checkpoints live outside the repository
(`D:\FoodML`).
