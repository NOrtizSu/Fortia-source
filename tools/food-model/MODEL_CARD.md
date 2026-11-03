# Model card: Fortia food models v2 (`food_model.tflite`, `food_detector.tflite`)

Models for the Comida tab since 1.6.0. Everything runs on the phone, offline; the photo never leaves the device.
The 1.5.0 model (EfficientNet-Lite0, 300 classes, 4.3 MB, test top-1 50 % / top-5 77 %) is replaced; its numbers
are quoted below where they help the comparison.

## Summary

| | Classifier `food_model.tflite` | Detector `food_detector.tflite` |
|---|---|---|
| What | photo or item crop → **329 foods/dishes + `_not_food`** (`classes.csv`, output order); 2nd output `log_grams` (grams suggestion) | photo → up to 10 "food item" boxes (one class), NMS in the graph |
| Use in the app | top 5 shown, **the user confirms**; the grams estimate only pre-fills the amount ("≈ 180 g (estimated)") | ≥ 2 items → each is cropped and classified ("several foods per photo"); 0-1 → the whole photo |
| Architecture | MobileNetV4-Conv-Medium (8.9 M parameters with this head), 320 × 320 input | YOLO11n (2.6 M parameters), 320 × 320 input |
| Starting weights | timm `mobilenetv4_conv_medium.e250_r384_in12k_ft_in1k` (ImageNet-12k + 1k, Apache-2.0) | Ultralytics YOLO11n COCO (AGPL-3.0) |
| Training data | 205 k training pictures: Open Images V7 photos and box crops (CC BY 2.0 / CC BY 4.0), Wikimedia Commons (CC0/PD/CC BY/CC BY-SA), MyFoodRepo item crops (CC BY 4.0); grams head: Nutrition5k (CC BY 4.0) | 108 k photos: Open Images V7 boxes, MyFoodRepo polygons → boxes |
| File | **fp16** TFLite, 17.7 MB, float32 input/outputs (int8 loses 16 points, see "Export") | fp16 TFLite, 5.3 MB (int8 breaks its scores) |
| Test | top-1 **56.4 %**, top-5 **81.7 %** (15 763 pictures, PyTorch); on the 1.5.0 test photos **54.3 % / 80.5 %** (1.5.0 model: 50.5 % / 77.3 %) | MyFoodRepo phone photos, at the shipped threshold: 1 item P 0.86 R 0.96; 2-3 items P 0.82 R 0.59; 4+ items P 0.82 R 0.31 |
| Trained | 2026-11-02/03, `tools/food-model/` (`train.py` run `cls2`, `train_detector.py` run `det1`, `grams.py`) | |

The targets were top-1 ≥ 60 % / top-5 ≥ 85 %; **they were not reached** (56.4 / 81.7 on the new test set, 54.3 /
80.5 on the comparable 1.5.0 test photos: +3.8 / +3.2 points over 1.5.0, with 29 more classes).

## Intended use and limits

- Suggestions only. The user always confirms the food and the amount; nothing is logged automatically.
- Meant for meal photos from above or at an angle; several foods on a plate are found by the detector (2-6 items).
- **Grams**: a rough suggestion from the picture's content, calibrated on one cafeteria dataset (see below). It
  does not know the real size of anything in the photo; a close-up of a small bowl and a wide shot of a big plate
  can look alike. The user's amount always wins.
- Not meant for: packaged products (barcode), drinks in opaque cups, medical or dietary decisions.
- Classes are visual groups (e.g. `potato` = boiled/cooked potato, `couscous` also bulgur, `water` = a glass of
  still or sparkling water, `mixed_vegetables` = cooked mixed vegetables / menestra); the catalog's variants
  (paella de marisco, salmorejo for gazpacho…) are offered by the app after recognition.

## Classes (v2)

`classes.csv` / `.omc/handoffs/2026-11-02/food_classes_v2.csv`: **300 kept** (no id renamed or removed), **19
recovered** from the 47 dropped in 1.5.0 (dates, raisins, leek, sauerkraut, chickpeas, pain au chocolat, brown
rice, cooked ham, feta, goat cheese, almonds, walnuts, peanuts, cashews, hazelnuts, honey, chocolate spread,
tabbouleh, spaghetti bolognese) and **10 new** (water, quinoa, canned tuna, ketchup, fennel, mixed cooked
vegetables, rosé wine, endive, samosa, onigiri), + `_not_food`. Rule as in 1.5.0: a class needs ≥ 150 training
pictures. Still under it (non-visual in the catalog): 27 of the 1.5.0 drops (e.g. patatas bravas 143, chestnuts
145, celery 142, edamame 146, clam chowder 132, deviled eggs 133, pork belly 130, moussaka 119, turrón 110,
horchata 101) and the Spanish dishes Commons and Open Images cannot fill (cocido 53, migas 60, fideuà 55,
sobrasada 51, pimientos de Padrón 41, huevos rotos 18, salmorejo 16, ensaimada 16), gnocchi, polenta, shakshuka,
açaí bowl. `dolma` stays out (its Open Images label shows grapevines).

## Data

**Sources (licence-clean only; details and every picture: `docs/third-party/food-model/`).**

- Open Images V7: human-verified image-level labels (cap raised from 1 000 to 1 500 photos per class, the v2
  candidate labels added) and, new, the **human-drawn boxes** of food/drink classes (114 k photos with 1-12 items
  after removing "container" boxes such as a plate around several foods).
- Wikimedia Commons: the 1.5.0 categories plus categories for the recovered and new classes; after review, the
  ketchup category was dropped (bottles, hot sauces, street scenes).
- **MyFoodRepo / Food Recognition Benchmark 2022** (AIcrowd; v2.0, 39 962 + 1 000 photos taken with a phone app,
  every food segmented; CC BY 4.0): 84 k items whose class maps to ours (`frb_map.py`).
- **Nutrition5k** (Google, CC BY 4.0): 3 262 overhead photos of cafeteria dishes with their weighed mass (grams
  head only).
- No research-only datasets (Food-101, UEC-Food, ISIA, Food2K…).

**Item crops.** The classifier also learns the multi-item flow: every MyFoodRepo food and every specific Open
Images food box on a photo with 2+ items is cut out exactly as the app cuts a detector box (square, 10 % margin),
47 k + 21 k training crops.

**Dataset.** The 1.5.0 photos keep their split and label (so the 1.5.0 test photos remain a fair comparison).
New photos are merged with them by near-duplicate hashing (a new copy of an old photo is dropped); crops follow
their source photo's split; MyFoodRepo's validation photos are test. At most 1 500 pictures per class (5 000 for
`_not_food`).

| Split | Pictures | of which crops |
|---|---|---|
| train | 205 473 | 68 269 |
| val | 11 700 | 3 742 |
| test | 15 763 | 3 152 |

## Classifier training

- timm `mobilenetv4_conv_medium.e250_r384_in12k_ft_in1k`, new 330-way head, dropout 0.2, stochastic depth 0.1.
- 24 epochs, batch 64, AdamW, **backbone lr 6e-5, head lr 1e-3, head initialised at zero**, weight decay 0.05,
  1 warm-up epoch, cosine; label smoothing 0.1; EMA 0.9998 (the best checkpoint is the EMA of epoch 18, val top-1
  53.4 %). Progressive resizing: 224 px for the first 12 epochs, 256 px after. Square-root class-balanced
  sampling, `_not_food` sampled 3 × more. Augmentation as 1.5.0 (random resized crop 25-100 %, flips, 90°
  rotations, TrivialAugmentWide, random erasing). RTX 2060 SUPER, ~7.5 min per epoch.
- The v1 recipe (AdamW 3e-4 on everything, random head) destroyed MobileNetV4's pretrained features (val top-1
  11 % after 2 epochs): its random head produced huge first gradients. A/B on 600 steps: 0.4 % (v1 recipe) → 24.5 %
  (low backbone lr + zero head).
- **Evaluated at 320 px** (resize the short side to 366, centre crop 320) although trained at 256: +1.7 / +1.8
  points on the 1.5.0 test photos (54.3 / 80.5 vs 52.6 / 78.7 at 256; FixRes effect), for 1.56 × the compute.
- `_not_food` logit shifted by −0.75 (balance below). Temperature scaling on val: **T = 0.78** (expected
  calibration error on test 0.130 → 0.034).

## Classifier results (PyTorch, the app's preprocessing at 320 px)

| Test set | Pictures | top-1 | top-5 |
|---|---|---|---|
| v2 test (photos + crops) | 15 763 | **56.4 %** | **81.7 %** |
| — Open Images photos | 8 220 | 55.7 % | 82.9 % |
| — Commons photos | 4 391 | 50.9 % | 74.1 % |
| — MyFoodRepo item crops (phone photos) | 1 236 | 57.1 % | 84.2 % |
| — Open Images box crops | 1 916 | 71.2 % | 92.2 % |
| 1.5.0 test photos (same photos, same labels) | 10 157 | **54.3 %** | **80.5 %** |
| 1.5.0 model on the same photos | 10 157 | 50.5 % | 77.3 % |
| Spanish / southern European classes (v2 test) | 2 993 | 54.1 % | 81.2 % |

Per food group (v2 test, top-1 / top-5): vegetables 67.7 / 84.8 · fish 65.9 / 86.3 · fruit 65.3 / 84.0 · fast
food 63.2 / 88.3 · eggs 58.7 / 82.9 · legumes 56.4 / 81.2 · snacks 55.3 / 77.6 · sweets 53.5 / 80.0 · drinks
52.0 / 84.2 · dishes 50.3 / 79.7 · meat 46.4 / 78.3 · sauces 45.2 / 70.3 · fats & nuts 44.0 / 67.6 · dairy
41.7 / 71.9 · grains 40.0 / 75.6. On the 1.5.0 test photos (1.5.0 model → v2, top-1) most groups gained: fast
food 51 → 61 %, dishes 45 → 50 %, vegetables 63 → 68 %, eggs 51 → 56 %, snacks 51 → 57 %, fats & nuts 37 → 43 %,
grains 33 → 37 %; legumes stayed at 45 % and dairy lost (40 → 36 %).

Per class (v2 test): median top-5 80.9 %; 61 classes ≥ 90 % top-5, 5 below 50 %. Some Spanish ones (top-1 /
top-5): paella 80 / 90, coffee with milk 80 / 95, suckling pig 83 / 92, flan 71 / 86, cured ham 68 / 93, lentils
57 / 83, bocadillo 56 / 94, croquettes 53 / 80, bean stew 53 / 73, churros 50 / 75, chorizo 49 / 81, Spanish
omelette 48 / 87, breaded cutlet 47 / 80, calamari 46 / 82, octopus 46 / 68, gazpacho 41 / 65, fried fish
36 / 76, sangria 34 / 76, custard (natillas) 10 / 48. New and recovered classes (few test pictures each, so
rough): chickpeas 67 / 83, canned tuna 70 / 90, water 65 / 90, ketchup 67 / 83, samosa 77 / 85, feta 63 / 74,
walnuts 59 / 73, honey 60 / 60, chocolate spread 56 / 81, almonds 36 / 64, peanuts 35 / 50, hazelnuts 12 / 35,
mixed vegetables 23 / 79, goat cheese 0 / 0 (2 test pictures).

Worst 20 by top-1 (top-1 / top-5): goat cheese 0/0, cupcake 10/71, custard 10/48, hazelnuts 12/35, cream cheese
12/60, danish pastry 13/67, chicken soup 13/73, quinoa 14/71, brown rice 17/100, bread 17/59, grilled chicken
18/71, mozzarella 19/58, lemonade 19/74, leek 20/60, noodles 20/66, green salad 21/63, dates 22/56, mixed
vegetables 23/79, pork chop 23/63, garlic bread 24/53. Main confusions: doughnut → bagel 35, sandwich → baguette
sandwich 19, courgette → cucumber 18, orange → not food 17, grapefruit → lemon 15, orange → grapefruit 14, pasta
→ carbonara 14, cake → not food 14, juice → cocktail 13, orange → mandarin 12, hot chocolate → coffee with milk 12.

**Not food** (v2 test): `_not_food` wins on 71.6 % of the non-food pictures (1.5.0: 61.8 %) and on 1.8 % of the
food pictures (1.5.0: 1.9 %), precision 50.8 %. Shift of the `_not_food` logit (non-food caught / food rejected):
0 → 78.8 % / 2.5 %, −0.5 → 73.6 % / 2.1 %, **−0.75 (shipped) → 71.6 % / 1.8 %**, −1.0 → 67.4 % / 1.6 %.
`min_confidence` 0.05 (1 % of the food pictures have a best food probability under 0.061).

## Detector

- Data: MyFoodRepo (every food segmented; sauces, dressings, herbs and seeds on another food give no box: the
  user logs "pasta with tomato sauce", not two things) + Open Images food boxes (the most specific box of an
  object, no "container" boxes around several foods, no drawings, 1-12 items) + 3 000 no-food photos as
  background. Every photo **stretched to a square**, as the app does. 108 k training, 3.9 k val, 9.5 k test.
- YOLO11n from COCO, one class, 30 epochs at 320 px, batch 64, mosaic (off for the last 10), flips up/down and
  left/right; ~6-10 min per epoch. Val mAP50 0.67, mAP50-95 0.51.
- **Container rule** (`group_suppression` in the json, `compare_group_rules.py`): a box holding ≥ 2 other kept
  boxes (each ≥ 80 % inside it) is dropped and the inner boxes kept, so a pile of potato pieces is not counted
  twice (pile + pieces) and no box is left around a mixed plate. Compared on val/test with the true boxes: box F1
  on MyFoodRepo test 0.667 (no rule), **0.656 (this rule)**, 0.649 (keep the big box instead), 0.661 (drop only
  when the pieces cover ≥ 50 % of it); the app's flow choice is unchanged (75.5 %); grams per plate within noise
  between rules (29.2 % vs 28.5 % without). The table below is without the rule (box F1 −1 point with it).
- App rules evaluated (`train_detector.py`): NMS IoU 0.5, score ≥ threshold, boxes shorter than 6 % of the
  photo's short side dropped, at most 6 items; "multi-item flow" = 2+ boxes. Threshold **0.25** chosen on val
  (the share of photos sent to the right flow is flat between 0.2 and 0.3; 0.25 has the higher precision).

Test, exported fp16 TFLite (= PyTorch within 0.5 point), threshold 0.25 — precision / recall of the boxes (IoU
≥ 0.5) and how often the app picks the right flow:

| Photos | MyFoodRepo test (phone meal photos) | Open Images test |
|---|---|---|
| 1 item | P 0.86, R 0.96, single flow kept 89 % (569) | P 0.65, R 0.90, 81 % (5 202) |
| 2-3 items | P 0.82, R 0.59, multi flow 53 % (260) | P 0.74, R 0.61, 59 % (1 954) |
| 4+ items | P 0.82, R 0.31 (at most 6 found), multi flow 64 % (167) | P 0.76, R 0.36, 73 % (1 343) |

Open Images "precision" is lower partly because its boxes are incomplete (foods the annotators did not label
count as false positives). In practice: a single dish is rarely split; on plates with 2-3 foods the detector
finds both/all about 6 times in 10, so about half of such photos still go to the single flow (where the top-5
and search remain). The int8 version lost most of its recall (the score tensor quantises badly), hence fp16.

## Grams estimate

Method (`grams.py`, contract `grams_contract.md`): a linear head on the classifier's pooled features gives `y` =
log grams of the food in the picture; trained on Nutrition5k so that the photo's estimate matches the weighed
dish mass (single flow: the whole photo; multi flow: the sum of the items' estimates, with the detector's rules
and the container rule applied as in the app); every dish is also shown "closer" (the central 65 % of the photo)
with the same mass, so the head can't rely on the rig's fixed camera distance. The app computes
`ln est = b0 + b_model·y + b_portion·ln(portion) + b_share·ln(share)` with **b0 0.548, b_model 0.882, b_portion
0.040, b_share 0.389** (fitted on held-out training dishes), clamps it to **0.1-4 × the catalog portion** (the
floor chosen on held-out dishes including one food in several pieces; 0.25 lifted every potato piece to a
quarter portion) and 5-1 500 g, rounds to 5 g. `enabled` = true.

Nutrition5k test dishes (506; official split), median absolute % error of the suggested amount and share within
±25 % — per plate (the suggested total) and per item (dishes of one food, ≥ 90 % of the mass one ingredient):

| | Estimate (app formula) | Catalog default portion of the recognised food (today) | A constant (median mass) |
|---|---|---|---|
| Plate, whole photo (single flow), 338 | **33.1 %** (37 % within ±25 %) | 55.0 % (23 %) | 58.9 % |
| Plate, item crops (multi flow), 168 | **26.3 %** (49 %) | 68.1 % (18 %) | 42.3 % |
| Plate, all, 506 | **29.2 %** (41 %) | 60.1 % (22 %) | 50.6 % |
| Item: one food found alone, its crop, 132 | **27.0 %** (45 %) | 74.2 % (23 %) | 138 % |
| Item: one food in pieces, summed, 35 | **30.9 %** (40 %) | 241 % (9 %) | 146 % |
| Plate, all, camera nearer (central 65 %) | 31.7 % (41 %) | 72.0 % (18 %) | 50.6 % |

So on Nutrition5k the estimate halves the error of the default portion (which badly overshoots when one food
is split into pieces), and it degrades little when the plate fills more of the photo. **Limits**: one cafeteria, one rig, overhead photos, Californian dishes; on phone photos
the error will be higher than this (no ground truth was available to measure it). It is a suggestion the user
confirms.

## Export and runtime

See the README for the commands. Classifier: ONNX (opset 17) → onnx2tf 1.28.8 / TensorFlow 2.21 → TFLite, float32
input and outputs, logits first (index 0), `log_grams` index 1. Detector: ONNX → onnx2tf → a tf.function adding
the NMS → TFLite fp16.

**Why fp16 and not int8.** Full int8 (calibrated on 500 val pictures, per-channel weights) loses most of the
accuracy of this MobileNetV4: onnx2tf's int8 top-1 39.7 % (PyTorch 56.4 %, 50 % agreement), the TensorFlow
converter's int8 41 %, also with the noisiest layers kept in float (`quant_debug.py`). Each layer's own int8
error looks normal; it accumulates through the network (activation outliers under per-tensor int8). fp16
weights keep it exact; dynamic-range int8 (int8 weights, float activations, 9.4 MB) keeps the accuracy too but
ran 3.7 × slower on this PC's CPU (hybrid kernels, not XNNPACK).

| Variant (v2 test) | Size | top-1 | top-5 | same top-1 as PyTorch | 1 thread | 4 threads |
|---|---|---|---|---|---|---|
| PyTorch (reference) | — | 56.4 % | 81.7 % | — | — | — |
| float32 | 35.3 MB | 56.4 % | 81.7 % | 99.8 % | 78 ms | 23 ms |
| **float16 (shipped)** | **17.7 MB** | 56.4 % | 81.7 % | 99.7 % | 77 ms | 23 ms |
| dynamic-range int8 (3 000 pictures) | 9.4 MB | 57.0 % (float32: 57.3 %) | 81.6 % (81.6 %) | 93.0 % | 110 ms | 130 ms |
| full int8 | 9.8 MB | 39.7 % | 65.2 % | 50.1 % | 38 ms | 12 ms |

The `log_grams` output of the fp16 file is within 0.14 % (median; 0.55 % at the 95th percentile) of float32;
the dynamic-range file's within 8 % (31 % at the 95th percentile). An int8 detector (the 8 worst layers kept in
float) dropped to box F1 0.53 on MyFoodRepo test (fp16 0.66): its scores shrink, so the detector stays fp16.
**Size: 17.7 MB + 5.3 MB = 23.0 MB, over the 20 MB budget** (the dynamic-range classifier would make it 14.7 MB).

**Timing.** This PC (Ryzen 7 3700X, LiteRT XNNPACK, 4 threads): detector 15 ms, classifier 23 ms per picture; a
6-item plate end to end (Python/PIL preprocessing included) 181 ms for a 640 × 480 photo, 434 ms for a 12 MP one
(`time_pipeline.py`). Emulator (food-app2, x86-64 AVD on this PC, debug build, the shipped files, container rule
on; models loaded when the Comida tab opens): classifier model time per crop 49-135 ms (dynamic-range int8:
134-394 ms); whole photo as one item 139 ms; plate of 3 items 1.05 s warm / 1.49 s cold, of 6 items (12 MP
photo) 0.99 s warm / 1.60 s cold (dynamic-range int8: 1.38 / 2.79 s and 1.84 / 2.17 s). So the warm pipeline is
within the 1.5 s budget; a cold first photo with 3-6 items is at or just over it. Labels and grams were nearly the
same with both files.

## Known limitations and biases

- **Label noise** as in 1.5.0 (Open Images rank heuristic, Commons categories, MyFoodRepo's Swiss food ontology
  mapped to our classes by hand). Test labels carry the same noise.
- **Where the photos come from.** Flickr (2005-2015), Commons, and MyFoodRepo (Swiss volunteers' phone photos,
  mostly home meals: bread, cheese, coffee, salads are over-represented there). Spanish dishes still have few
  photos (several could not become classes).
- **Few test pictures** for many new/recovered classes (their photos are mostly MyFoodRepo crops whose test part
  is MyFoodRepo's small validation set): their per-class numbers are rough.
- **Look-alikes** (doughnut/bagel, citrus fruits, sandwich types, plain/sauced pasta, cheeses, nuts by kind).
- **Detector** misses about 4 in 10 foods on plates with 2-3 foods and most small extras on busy breakfast
  tables; 6 items at most.
- **Grams**: see above; the estimate is for the food's typical density (ml shown for drinks).
- **Size and speed**: the fp16 classifier is 4 × the 1.5.0 file and ~3.5 × its compute per picture; see "Export".
