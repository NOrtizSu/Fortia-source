# Model card: Fortia food models v3 (`food_model.tflite`, `food_detector.tflite`)

Models for the Comida tab since 1.8.0. Everything runs on the phone, offline; the photo never leaves the device.
v3 replaces the 1.6.0 models (v2: fp16, 17.7 + 5.3 MB, test top-1 56.4 % / top-5 81.7 %); their numbers are quoted
below where they help the comparison.

## Summary

| | Classifier `food_model.tflite` | Detector `food_detector.tflite` |
|---|---|---|
| What | photo or item crop → **351 foods/dishes + `_not_food`** (`classes.csv`, output order); 2nd output `log_grams` (grams suggestion); 3rd output `embedding` (960 values: "products you already scanned") | photo → up to 10 "food item" boxes (one class), NMS in the graph |
| Use in the app | top 5 shown, **the user confirms**; the grams estimate only pre-fills the amount ("≈ 180 g (estimated)"); the embedding matches a photo of a package with the Open Food Facts photos of the products the user scanned (offered, never logged) | ≥ 2 items → each is cropped and classified ("several foods per photo"); 0-1 → the whole photo |
| Architecture | MobileNetV4-Conv-Medium with its head narrowed to 640 units (8.0 M parameters), 320 × 320 input | YOLO11n (2.6 M parameters), 320 × 320 input |
| Starting weights | the 1.6.0 classifier (from timm `mobilenetv4_conv_medium.e250_r384_in12k_ft_in1k`, ImageNet-12k + 1k, Apache-2.0); trained to match a teacher built on SigLIP 2 (Apache-2.0, training only) | the 1.6.0 detector (Ultralytics YOLO11n COCO, AGPL-3.0, fine-tuned), exported again |
| Training data | 210 k training pictures: Open Images V7 photos and box crops (CC BY 2.0 / CC BY 4.0), Wikimedia Commons (CC0/PD/CC BY/CC BY-SA), MyFoodRepo item crops (CC BY 4.0); grams head: Nutrition5k (CC BY 4.0) | 108 k photos: Open Images V7 boxes, MyFoodRepo polygons → boxes (1.6.0) |
| File | **full int8** TFLite (float32 input/outputs), **8.95 MB** | **dynamic-range int8** (int8 weights, float activations), **2.81 MB** |
| Total | **11.76 MB** (1.6.0: 23.0 MB) | |
| Test | 1.6.0 test pictures: top-1 **57.9 %**, top-5 **83.0 %** (1.6.0 model: 56.4 / 81.7); 1.5.0 test photos 56.7 / 82.5 % (1.6.0: 54.3 / 80.5; 1.5.0: 50.5 / 77.3) — the shipped int8 file | as 1.6.0 (the int8-weight file gives the fp16 numbers): MyFoodRepo phone photos, 1 item P 0.86 R 0.96; 2-3 items P 0.82 R 0.59; 4+ items P 0.81 R 0.31 |
| Speed | emulator (x86-64 AVD, debug build): single food 0.26-0.29 s warm / 0.74 s cold; plate of 3 foods 0.68-0.71 s warm / 0.96 s cold (detector + classifier runs, photo decode excluded) | |
| Trained | 2026-11-04/05, `tools/food-model/` (`train.py` run `cls3`, `qat.py` → `cls3q`, `grams.py` → `cls3q4`, the shipped export) | 2026-11-03 (`det1`), exported 2026-11-04 |

The accuracy targets were top-1 ≥ 60 % / top-5 ≥ 85 % on the 1.6.0 test pictures; **they were not reached**
(57.9 / 83.0: +1.5 / +1.3 points over 1.6.0 with 22 more classes; the float model before int8: 58.1 / 82.6). The
size target (all food model files ≤ 12 MB) and the speed target (≤ 1.5 s on the emulator, cold and warm) were
reached.

## Intended use and limits

- Suggestions only. The user always confirms the food and the amount; nothing is logged automatically.
- Meant for meal photos from above or at an angle; several foods on a plate are found by the detector (2-6 items).
- **Grams**: a rough suggestion from the picture's content, calibrated on one cafeteria dataset (see below). It
  does not know the real size of anything in the photo; a close-up of a small bowl and a wide shot of a big plate
  can look alike. The user's amount always wins.
- **Products you already scanned**: the embedding only compares a photo with the stored front photos of the
  user's own barcode products; a match is offered ("Is this …?"), never logged. Not a barcode reader, not a
  general product recogniser.
- Not meant for: drinks in opaque cups, medical or dietary decisions.
- Classes are visual groups (e.g. `potato` = boiled/cooked potato, `couscous` also bulgur, `water` = a glass of
  still or sparkling water, `mixed_vegetables` = cooked mixed vegetables / menestra); the catalog's variants
  (paella de marisco, salmorejo for gazpacho…) are offered by the app after recognition.

## Classes (v3)

`classes.csv` = `classes_v3.csv` = `.omc/handoffs/2026-11-04/food_classes_v3.csv`: the **329 classes of 1.6.0 kept**
(same ids, same order) and **22 new**, + `_not_food` last: edamame, baked beans, black pudding (morcilla), pork
belly (panceta), sardines, chestnuts, turrón, patatas bravas, onion rings, clam chowder, mille-feuille (milhojas),
gnocchi — these 12 already had catalog rows — and baklava, kimchi, fajitas, chicken teriyaki, marshmallows,
enchiladas, stuffed peppers, quail eggs, tempeh, marzipan (ids shared with the 1.8.0 catalog). Rule as before: a
class needs ≥ 150 training pictures (the new ones have 151-392). Still under it, so not visual in the catalog:
celery 147, French onion soup 148, deviled eggs 144, horchata 142, moussaka 125, pistachios 123, arancini 114,
minestrone 112, polenta 110, cocido 93, shakshuka 88, migas 70, dal 70, sobrasada 69, fideuà 56, pimientos de
Padrón 46, roscón 39, salmorejo 24, huevos rotos 23, ensaimada 23 (Commons and Open Images have no more free
photos of them).

## Licence

The classifier files (`food_model.tflite`, `food_model.json`) are AGPL-3.0 like the app and, at your choice, also
CC BY-SA 4.0 (some training photos are CC BY-SA); the detector files are AGPL-3.0 only (they derive from
Ultralytics YOLO11n). Details and credits: `docs/third-party/food-model/README.md`.

## Data

**Sources (licence-clean only; details and every picture: `docs/third-party/food-model/`).** As in 1.6.0 (Open
Images V7 labels and boxes, Wikimedia Commons, MyFoodRepo / Food Recognition Benchmark 2022, Nutrition5k for grams)
plus, for the new classes: Open Images photos of their labels, Commons categories and a **Commons file-name
search** (files named after the dish, e.g. "patatas bravas"; searches that returned paintings or places named like
a dish — Carpaccio, Canelones — were removed, and every new Commons photo had to pass the 1.6.0 classifier's
`_not_food` check, p < 0.4), and MyFoodRepo items of the new classes. No research-only datasets (Food-101,
UEC-Food, ISIA, Food2K…). Open Food Facts product photos (CC BY-SA 3.0) were used **only to evaluate** the
embedding, never for training.

**Dataset.** The 1.6.0 pictures keep their split and label, so the 1.6.0 test pictures (`test_v2.csv`, 15 763)
and the 1.5.0 ones (`test_v1.csv`, 10 157) remain fair comparisons; new photos follow the same near-duplicate
merging and split rules.

| Split | Pictures | of which crops | 1.6.0 |
|---|---|---|---|
| train | 210 167 | 68 422 | 205 473 |
| val | 11 978 | 3 750 | 11 700 |
| test | 16 207 | 3 157 | 15 763 |

## Classifier training

- **Warm start** from the 1.6.0 classifier. Its head (960 → 1 280 → classes) is **narrowed to 640 units** to fit
  the size budget: the 640 most important hidden units are kept and the classifier layer is refitted by least
  squares on the old logits of 3 904 training pictures (logits R² 0.94, so no accuracy is lost at the start). The
  22 new classes start as the mean of the old classifier rows.
- **Distillation** (`teacher.py`): the teacher is the geometric mean of a linear layer trained on **SigLIP 2
  ViT-B/16** image features (Google, Apache-2.0, `vit_base_patch16_siglip_224.v2_webli` via timm) and the 1.6.0
  classifier (weight 0.3; it abstains on the classes it does not know), computed on **4 stored augmented views of
  every training picture**. Teacher on the v3 test: SigLIP 2 probe 60.6 / 85.5 %, ensemble **62.7 / 87.1 %**. Loss
  = 0.7 × KL to the teacher's view + 0.3 × label loss (smoothing 0.1).
- 12 epochs, batch 64, AdamW, backbone lr 4e-5, head lr 5e-4, weight decay 0.05, half an epoch of warm-up, cosine;
  EMA; progressive resizing 256 → 288 px at epoch 6; square-root class-balanced sampling, `_not_food` sampled 3 ×
  more; augmentation as 1.6.0. RTX 2060 SUPER, ~7.5 min (256 px) / ~9.5 min (288 px) per epoch. Best = the EMA of
  the last epoch (val 57.9 / 83.0 % at 288 px; 1.6.0's best was 53.4 % on its own val).
- Evaluated and exported at **320 px** (short side 366, centre crop 320), as 1.6.0.
- Float results (PyTorch, before int8, no `_not_food` shift): 1.6.0 test pictures 58.1 / 82.6 %, 1.5.0 test photos
  56.2 / 81.5 %, v3 test 57.7 / 82.4 %. Distillation moved about a third of the teacher's gap.

## Full int8

1.6.0 shipped fp16 because full int8 lost 16 points of top-1 (39.7 % vs 56.4 %). `quant_sim.py` reproduces
TFLite's int8 arithmetic in PyTorch (BatchNorm folded, per-channel int8 weights, per-tensor int8 activations
calibrated by min/max) and finds the cause: the weights are fine (int8 weights alone: −0.3 points); the
**activations** are not, mainly the residual stream of the last big stage (one per-tensor int8 scale for values up
to ±1 400 in 1.6.0, ±4 500 in v3, because of a few huge channels, while the other channels are a few units: rounded
to zero) and the pooled features before the head.

1. **Exact re-parametrisation** before export (same float function, same architecture): every channel of a
   residual stream is divided by d_c in all the convolutions that write the stream and multiplied back in the first
   linear op of every reader; the same inside every block (expansion → depthwise → projection, through the ReLUs)
   and between the last convolution and the head; d_c = max|x_c|^0.75 / max|w_c|^0.25, normalised
   (SmoothQuant-style). v3 simulated int8: 50.9 % (float 56.6 %, 2 000 val pictures).
2. **Quantisation-aware fine-tuning** (`qat.py`, 1 500 steps, batch 32, lr 1e-5): the same fake int8 with
   straight-through gradients, values outside a tensor's int8 range clipped, distillation from the float model (+
   10 % label loss), and a **penalty on each channel's batch max/min leaving its int8 range**. Without the penalty
   the fine-tuned model was good with its training ranges and bad after export, because the converter measures
   the float model's min/max again and the activations had drifted outside the ranges they were trained with.
   Simulated int8 after QAT 56.5 / 82.9 % (float 56.6 / 82.5 %).
3. The `_not_food` logit is shifted by −1.0 (see "Not food"), the grams head fitted on the int8 network's own
   features (see "Grams estimate"), and the model exported with onnx2tf 1.28.8 / TensorFlow 2.21 as full int8
   (per-channel weights, 500 val pictures for calibration, float32 input and outputs, tensor names stripped).

Real TFLite on the first 2 000 of the 1.6.0 test pictures (4 threads, Ryzen 7 3700X, LiteRT XNNPACK):

| Variant | Size | top-1 | top-5 | per picture |
|---|---|---|---|---|
| PyTorch float (reference) | — | 59.8 % | 83.3 % | — |
| float16 | 16.1 MB | 59.5 % | 83.3 % | 22-27 ms |
| dynamic-range int8 | 8.6 MB | 59.0 % | 83.1 % | 113 ms |
| full int8, plain (no re-parametrisation, no QAT) | 9.0 MB | 2.7 % | 5.1 % | 12.5 ms |
| **full int8 (shipped: re-parametrised + QAT)** | **8.95 MB** | **59.2 %** | **83.8 %** | **12.7 ms** |

(The first four rows are the float model before QAT. The re-parametrised model converted to fp16 gives 57.1 %:
some re-scaled weights become too small for fp16; it is not shipped.)

## Classifier results (the shipped int8 file, the app's preprocessing at 320 px)

| Test set | Pictures | v3 int8 top-1 | top-5 | 1.6.0 model top-1 | top-5 |
|---|---|---|---|---|---|
| **1.6.0 test pictures** (same photos and labels) | 15 763 | **57.9 %** | **83.0 %** | 56.4 % | 81.7 % |
| — Open Images photos | 8 220 | 58.3 % | 85.1 % | 55.7 % | 82.9 % |
| — Commons photos | 4 391 | 51.8 % | 74.8 % | 50.9 % | 74.1 % |
| — MyFoodRepo item crops (phone photos) | 1 236 | 56.1 % | 84.4 % | 57.1 % | 84.2 % |
| — Open Images box crops | 1 916 | 71.6 % | 92.1 % | 71.2 % | 92.2 % |
| — Spanish / southern European classes | 2 993 | 54.5 % | 81.6 % | 54.1 % | 81.2 % |
| 1.5.0 test photos (1.5.0 model: 50.5 / 77.3) | 10 157 | 56.7 % | 82.5 % | 54.3 % | 80.5 % |
| v3 test (incl. the 22 new classes) | 16 207 | 57.5 % | 82.7 % | — | — |

The v3 float model before int8 gets 58.1 / 82.6 % on the 1.6.0 test pictures, so full int8 costs 0.2 points of
top-1 (and none of top-5).

Per food group (1.6.0 test pictures, top-1 / top-5; 1.6.0 top-1 in brackets): vegetables 68.1 / 85.8 (67.7) ·
fast food 67.1 / 90.8 (63.2) · fruit 66.7 / 85.3 (65.3) · fish 65.4 / 86.7 (65.9) · legumes 59.1 / 84.0 (56.4) ·
eggs 59.0 / 83.4 (58.7) · snacks 58.4 / 80.9 (55.3) · sweets 56.3 / 81.3 (53.5) · drinks 53.6 / 86.5 (52.0) ·
dishes 51.9 / 81.4 (50.3) · meat 49.5 / 79.6 (46.4) · sauces 47.0 / 70.8 (45.2) · fats & nuts 42.9 / 73.1 (44.0)
· dairy 41.5 / 68.8 (41.7) · grains 39.5 / 75.7 (40.0).

Per class (1.6.0 test pictures): median top-5 82.5 % (1.6.0: 80.9 %); 75 classes ≥ 90 % top-5 (61), 4 below 50 %
(5). Some Spanish ones (top-1 / top-5): paella 81 / 94, suckling pig 83 / 88, coffee with milk 76 / 96, flan
76 / 86, cured ham 64 / 92, churros 62 / 75, lentils 61 / 87, croquettes 58 / 76, bocadillo 58 / 94, Spanish
omelette 57 / 87, breaded cutlet 53 / 87, chorizo 51 / 81, octopus 50 / 64, bean stew 47 / 80, calamari 38 / 72,
gazpacho 35 / 82, fried fish 32 / 75, sangria 32 / 80, custard (natillas) 6 / 45.

New classes (v3 test; 9-40 pictures each, so rough): edamame 73 / 80, onion rings 69 / 88, baklava 69 / 81, quail
eggs 62 / 77, marshmallows 52 / 78, mille-feuille 50 / 88, stuffed peppers 48 / 70, patatas bravas 47 / 76,
gnocchi 44 / 56, turrón 43 / 86, tempeh 41 / 65, clam chowder 41 / 68, enchiladas 39 / 56, sardines 38 / 77,
chestnuts 37 / 74, kimchi 33 / 58, pork belly 31 / 66, chicken teriyaki 30 / 72, marzipan 22 / 61, fajitas
18 / 55, baked beans 17 / 83, black pudding 14 / 62.

Worst by top-1 (top-1 / top-5): goat cheese 0 / 0 (2 test pictures), brown rice 0 / 100, custard 6 / 45,
mozzarella 6 / 56, chicken soup 7 / 80, bread 8 / 54, hazelnuts 12 / 53, cupcake 14 / 67, dulce de leche 18 / 47,
tripe stew 19 / 62, leek 20 / 60, grilled chicken 20 / 64, green salad 22 / 59, dates 22 / 61. Main confusions:
doughnut → bagel 29, sandwich → baguette sandwich 24, courgette → cucumber 22, bread → wholemeal bread 18, orange
→ grapefruit 17, pasta → carbonara 14, grapefruit → lemon 13, baguette sandwich → hamburger 12, juice → orange
juice 12, soft drink → beer 12.

**Not food.** The `_not_food` logit is shifted by −1.0 inside the model (chosen on the float model's val/test).
On the 1.6.0 test pictures `_not_food` wins on 69.1 % of the non-food pictures (1.6.0: 71.6 %) and on **1.1 %
of the food pictures** (1.6.0: 1.8 %), precision 62.6 % (1.6.0: 50.8 %).

**Calibration** (`calibrate_tflite.py`, on the exported file itself): temperature **T = 0.82** (fitted on val;
expected calibration error on the v3 test 0.088 → 0.037); `min_confidence` 0.05 (1 % of the food test pictures
have a best food probability under 0.067).

## Detector

Same weights as 1.6.0: YOLO11n from COCO, one class ("food item"), 30 epochs at 320 px on MyFoodRepo (every
food segmented; sauces and toppings on another food give no box) + Open Images food boxes (the most specific box,
no "container" boxes, 1-12 items) + 3 000 no-food photos, every photo stretched to a square as the app does (108 k
training, 3.9 k val, 9.5 k test photos). App rules in the json: NMS 0.5, score ≥ 0.25, boxes shorter than 6 % of
the photo dropped, a box holding ≥ 2 other boxes (each ≥ 80 % inside) dropped, at most 6 items (details: the 1.6.0
version of this card in git history). What changed is the export:

- The 1.6.0 int8 detector lost its scores because Ultralytics concatenates the decoded boxes (pixels, 0-320) with
  the scores (0-1) in one tensor before our export split them: one int8 scale for both rounded every score to ~0
  or 1.25. v3's export (`export_detector.py`) normalises the boxes and never puts them in the same tensor as the
  scores.
- Shipped as **dynamic-range int8** (int8 weights, float activations): the same results as fp16 at half the size.
  Full int8 works now but loses 3 points of box F1.

MyFoodRepo test (phone meal photos), threshold 0.25, no container rule — box precision / recall (IoU ≥ 0.5) and
how often the app picks the right flow:

| Photos | fp16 (1.6.0) | **dynamic-range int8 (shipped)** | full int8 |
|---|---|---|---|
| 1 item (569) | P 0.86, R 0.96, single flow 89 % | P 0.86, R 0.96, 89 % | P 0.82, R 0.96, 85 % |
| 2-3 items (260) | P 0.82, R 0.59, multi flow 53 % | P 0.82, R 0.59, 53 % | P 0.76, R 0.56, 54 % |
| 4+ items (167) | P 0.81, R 0.31, multi flow 63 % | P 0.81, R 0.31, 63 % | P 0.75, R 0.30, 65 % |
| box F1 / right flow (all) | 0.666 / 75.4 % | **0.666 / 75.3 %** | 0.637 / 73.3 % |
| size | 5.33 MB | **2.81 MB** | 2.89 MB |

Open Images test: box F1 0.636 (fp16), 0.637 (dynamic-range), 0.609 (full int8). On a plate of 3 foods used for
the emulator timing, the full-int8 detector found 2, the other two found 3.

## Grams estimate

Same method and contract as 1.6.0 (`grams.py`, `grams_contract.md`): a linear head on the classifier's pooled
features gives `y` = log grams; trained on Nutrition5k so that the photo's estimate matches the weighed dish mass
(single flow: the whole photo; multi flow: the sum of the items' estimates, with the detector's rules and the
container rule as in the app); every dish is also shown "closer" (the central 65 % of the photo) with the same
mass. The app computes `ln est = b0 + b_model·y + b_portion·ln(portion) + b_share·ln(share)`, clamps it around
the catalog portion and to 5-1 500 g, rounds to 5 g. Only the json values changed. Portions: the 1.8.0 catalog
(`foods.tsv`).

**What int8 changed.** A head fitted on the float model's features does not survive the int8 file: its 640
weights share one int8 scale (the first v3 head, with one weight of 9.1 and a median of 0.045, lost 42 % of its
weights to rounding) and the int8 network's features are not the float ones. Measured on the exported file
itself, that head's plate error was **39 %** although PyTorch said 28 %. So the shipped head is fitted on **the
int8 file's own features** (read from a debug export that also outputs them; `grams.py tflite --with-features`,
`fit --tflite-features`) with every weight within ±0.5, so that the rounding costs nothing, and the app formula
and its clamp are then fitted on the shipped file's own `log_grams` (`grams.py tflite` + `fit --tflite`):
**b0 0.341, b_model 0.882, b_portion 0.074, b_share 0.343, clamp 0.1-6 × the catalog portion** (chosen on held-out
training dishes). The logits and the embedding of the file are bit-identical with and without this head.

Nutrition5k test dishes (506; official split), median absolute % error of the suggested amount (share within
±25 %), **measured on the shipped int8 file** (detector boxes, top-1 food and `log_grams` as the app gets them):

| | v3 (int8 file) | 1.6.0 | Catalog default portion of the recognised food | A constant (median mass) |
|---|---|---|---|---|
| Plate, whole photo (single flow), 338 | **30.4 %** (42 %) | 33.1 % | 56.0 % (22 %) | 58.9 % |
| Plate, item crops (multi flow), 168 | **25.6 %** (48 %) | 26.3 % | 66.4 % (17 %) | 42.3 % |
| Plate, all, 506 | **28.2 %** (44 %) | 29.2 % | 59.7 % (21 %) | 50.6 % |
| Item: one food found alone, its crop, 132 | **28.3 %** (44 %) | 27.0 % | 71.2 % (22 %) | 138 % |
| Item: one food in pieces, summed, 35 | 36.2 % (37 %) | 30.9 % | 241 % (6 %) | 146 % |
| Plate, all, camera nearer (central 65 %) | 33.3 % (38 %) | 31.7 % | 67.8 % (18 %) | 50.6 % |

So the estimate still halves the error of the default portion and is about as good as 1.6.0's: better on whole
plates, worse on a food cut in pieces (only 35 dishes) and on close-ups. Nutrition5k is still the only data with weighed food we can use (MyFoodRepo has no weights), which
limits what a better head can learn. **Limits**: one cafeteria, one rig, overhead photos, Californian dishes; on
phone photos the error will be higher (no ground truth to measure it). It is a suggestion the user confirms.

## Image embedding ("products you already scanned")

The classifier's third output: the spatial maximum of its last convolution's ReLU output (960 values), from its
own run on the **whole photo stretched** to 320 × 320; the app L2-normalises it and compares by cosine with the
stored embeddings of the user's barcode products' Open Food Facts front photos (contract: the handoff's
`embedding_contract.md`). It costs no extra file; separate small ImageNet models were not better on this task
(and cost 2.5-9 MB).

Test (`off_retrieval.py`, `embed_tflite.py`, `embed_eval.py`): 2 095 popular Open Food Facts products; for 892 of
them the query is one contributor's raw upload and the stored photo is the front selected from another upload
(evaluation only; the photos are not redistributed). The shipped int8 file:

| Products the user scanned | 10 | 30 | 100 | 300 | 1 000 |
|---|---|---|---|---|---|
| recall@1 | 85.4 % | 78.2 % | 68.4 % | 57.9 % | 46.2 % |
| recall@5 | 96.9 % | 91.2 % | 84.7 % | 77.6 % | 66.6 % |

(1.6.0 classifier: 78.6 / 71.1 / 61.9 / 53.9 / 44.6 % recall@1.) Accept rule in the json: **threshold 0.67, margin
0.075** — at 30 products, 45 % of the photos of a scanned product are matched, 98.5 % of them to the right product;
3.2 % of photos of an unscanned product and 0.04 % of meal photos match something (at 100 products: 39 %, 97 %,
5 %, 0.04 %). Most wrong matches are a sibling package (another size or flavour of the same brand line).
`suggest_threshold` 0.57: up to 3 suggestions when there is no match. `space`: `fortia-food-3-int8-2026-11-05`
(embeddings of another model are recomputed).

## Export and runtime

See the README for the commands. Classifier: ONNX (opset 17; the grams head and the embedding as extra outputs)
→ onnx2tf → TFLite full int8, float32 input and outputs: logits (index 0), `log_grams` (1), `embedding` (2).
Detector: ONNX → onnx2tf → a tf.function adding the NMS → TFLite with int8 weights.

The int8 file is not a copy of the float model: on 200 test pictures with the same input tensors its top-1
agrees with the float checkpoint on 78 % (same accuracy: borderline pictures move both ways), its embedding has a
median cosine of 0.90 to the float one, and its `log_grams` needed a head fitted on its own int8 features (see
"Grams estimate"). That is why the results, calibration, embedding and grams numbers above are measured on the
exported file itself, with the app's preprocessing.

**Timing.** This PC (Ryzen 7 3700X, LiteRT XNNPACK, 4 threads): classifier 12.7 ms per picture (1.6.0 fp16:
23 ms), detector 14 ms. Emulator (x86-64 AVD `Play_Screens` on this PC, debug build, LiteRT 1.4.2, 4 threads;
12 MP photos decoded by the app to 1 280 px; times from the app's log: detection + every classifier run, the photo
decode excluded; cold = first photo after the app was started; the emulator's own noise is 2-4 ×):

| Files | Total size | One food, cold / warm | Plate of 3 foods, cold / warm |
|---|---|---|---|
| **int8 classifier + dynamic-range int8 detector (shipped)** | **11.76 MB** | 0.74 s / 0.26-0.29 s | 0.96 s / 0.68-0.71 s |
| int8 classifier + full-int8 detector | 11.84 MB | 0.83 s / 0.29-0.34 s | 0.85 s / 0.54-0.58 s (found 2 of 3) |
| int8 classifier + fp16 detector (1.6.0's) | 14.28 MB | 0.69 s / 0.26-0.30 s | 0.81 s / 0.64-0.72 s |
| 1.6.0 (fp16 + fp16), earlier measurement | 23.0 MB | — | 1.49 s / 1.05 s |

Model time per run on the emulator: classifier 46-101 ms, detector 22-120 ms (all three detector files alike
within the noise). Most of a cold run is the app's first image preparation (0.4-0.6 s), not the models.

## Known limitations and biases

- **Label noise** as before (Open Images rank heuristic, Commons categories and file names, MyFoodRepo's Swiss
  food ontology mapped to our classes by hand). Test labels carry the same noise.
- **Where the photos come from.** Flickr (2005-2015), Commons, and MyFoodRepo (Swiss volunteers' phone photos,
  mostly home meals). Spanish dishes still have few photos (several could not become classes).
- **Few test pictures** for the new classes (their per-class numbers are rough).
- **Look-alikes** (doughnut/bagel, citrus fruits, sandwich types, plain/sauced pasta, cheeses, nuts by kind).
- **Detector** misses about 4 in 10 foods on plates with 2-3 foods and most small extras on busy breakfast
  tables; 6 items at most.
- **Grams**: see above; the estimate is for the food's typical density (ml shown for drinks).
- **Embedding**: packages that look alike (sizes and flavours of one brand line), plain packages, photos of the
  back, strong glare, a small package in a big scene.
