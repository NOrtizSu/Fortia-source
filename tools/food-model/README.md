# Food recognition models (Comida tab)

Our own on-device models for the Comida tab, run on the phone with LiteRT (TensorFlow Lite); the photo never
leaves the device. Since 1.8.0 the models are v3 (1.6.0 shipped v2; the scripts marked v3 below):

- **Classifier** `food_model.tflite` + `food_model.json`: a photo (or one item's crop) → the visual food classes
  in `classes.csv` (351 foods + `_not_food`, the same list as `classes_v3.csv`; 1.6.0's 329 are `classes_v2.csv`);
  the app shows the top 5 and the user always confirms. A second output, `log_grams`, is the **grams suggestion**
  (contract: `grams_contract.md` in the handoff, summarised below); the third, `embedding`, is the **image
  embedding** that matches a photo of a package with the Open Food Facts photos of the products the user already
  scanned (`embedding_contract.md`). Full int8 since v3.
- **Detector** `food_detector.tflite` + `food_detector.json`: class-agnostic "food item" boxes, with decoding and
  NMS inside the graph, so a plate with several foods becomes several items, each cropped and classified.
- Results and limits: [MODEL_CARD.md](MODEL_CARD.md). Sources and licences: `docs/third-party/food-model/`.

Nothing here is needed to build the app: the asset files are committed. These scripts rebuild them.

## Files

| File | What it does |
|---|---|
| `classes_config.py` | Candidate classes: id, names, group, Open Images labels, Commons categories, rank (370 candidates in v2). |
| `classes.csv` / `classes_v3.csv` / `classes_v2.csv` / `classes_v1.csv` | The shipped class list in output order (= v3, with `status` kept/new) / the 1.6.0 list / the 1.5.0 list. |
| `oi_label_stats.py` | Counts human-verified positive Open Images labels and how often each co-occurs with a food label. |
| `select_classes.py` | `--oi`: gives each Open Images photo one class (highest rank wins, ties dropped) and picks `_not_food` photos. |
| `fetch_open_images.py` | Downloads those photos (bucket, else Flickr thumbnail), short side 320 px, with licence metadata; photos already on disk first. |
| `fetch_wikimedia.py` | `--plan`: walks the Commons categories (API, cached), keeps free licences; `--download`: thumbnails. |
| `oi_boxes.py` | Keeps the human-drawn Open Images boxes of the boxable food/drink classes. |
| `frb_index.py` | Indexes MyFoodRepo / Food Recognition Benchmark 2022 (polygons → one row per food item with its box). |
| `frb_map.py` | MyFoodRepo classes → our class ids. |
| `food_items.py` | What one "food item" is (toppings give no box; Open Images leaf/container rules), the app's square crop, the SSD mirror helper. |
| `fetch_nutrition5k.py` | Nutrition5k overhead photos + metadata (grams head). |
| `build_detector_data.py` | `select` / `download` / `build`: the detector's YOLO dataset (photos stretched to a square, as the app does). |
| `train_detector.py` | Fine-tunes Ultralytics YOLO11n (one class) and evaluates it the app's way (precision/recall by item count); also on the exported TFLite. |
| `export_detector.py` | `onnx` + `tflite`: boxes/scores as separate tensors, NMS (TFLite builtin) in the graph, `food_detector.json`. |
| `compare_group_rules.py` | Compares the container rules (a box around a pile plus its pieces) on the detector's val/test photos. |
| `quant_debug.py` | TensorFlow's quantization debugger on the classifier; selective int8 (layers kept in float). |
| `time_pipeline.py` | CPU timing of detector + one classifier run per item, as the app does. |
| `build_dataset.py` | v1 dataset (kept for the hashing and the v1 rules). |
| `build_dataset_v2.py` | v2 dataset: the v1 photos (same split and label) + new photos + item crops (MyFoodRepo items, Open Images boxes), near-duplicates merged, splits, classes ≥ 150 training photos, `food_classes_v2.csv`. |
| `build_dataset_v3.py` | v3 dataset: the v2 pictures (same split and label) + photos and crops of the v3 classes (Open Images, Commons categories and file-name search behind a `_not_food` check, MyFoodRepo crops), `test_v2.csv` (the 1.6.0 test pictures, comparable), `food_classes_v3.csv`. |
| `make_crops.py` | (Re)makes a dataset's item crops from its csv files (same rule and names as `build_dataset_v2.py`). |
| `teacher.py` | v3 distillation teacher: SigLIP 2 features of 4 stored views per training picture (+ the 1.6.0 classifier's predictions on the same views), a linear probe, their average → `train_teacher_logits.npy` for `train.py --teacher`. |
| `quant_sim.py` | Simulates TFLite full int8 in PyTorch (finds what breaks) and the exact re-parametrisation that fixes it (`smooth_streams`: residual streams, head, inside every block). |
| `qat.py` | Quantisation-aware fine-tuning (fake int8 as TFLite does it, distillation from the float model) after the re-parametrisation; the result exports to full int8 with `export.py`. |
| `off_retrieval.py` | The "products you already scanned" test set: Open Food Facts products with two front photos from different contributors (evaluation only). |
| `embed_eval.py` | Which embedding retrieves packages best (taps, pooling, preprocessing, separate ImageNet models); recall@k and the accept rule (`--rule`). |
| `embed_tflite.py` | The exported model's embedding on that set, exactly as the app computes it (for `embed_eval.py --rule`). |
| `mirror_photos.py` | Copies photo folders to an SSD in on-disk order (the work disk is a hard disk). |
| `pack.py` | Packs the train/val photos into one file each (`--reuse` copies an earlier pack's photos). |
| `static_pad.py` | Makes TF-style "SAME" padding static (v1 EfficientNet-Lite) so the model exports cleanly. |
| `train.py` | Fine-tuning on the GPU (mixed precision, EMA, progressive resizing, per-group learning rates). |
| `drop_classes.py` | Final touches on a checkpoint: cut a class's output row, shift the `_not_food` logit. |
| `evaluate.py` | Test metrics (also on the v1 test photos), per group / class, confusions, `_not_food`, temperature, `min_confidence`. |
| `grams.py` | `features` + `fit`: the grams head on the classifier's features, calibrated on Nutrition5k; MdAPE vs the default portion. v3: `--int8-head` (weights an int8 file keeps), `tflite` + `fit --tflite` (the app formula on the exported file's own outputs). |
| `export.py` | `onnx` (PyTorch venv; `--grams-head` adds the `log_grams` output) and `tflite` (export venv, onnx2tf); writes the assets. |
| `parity.py` | Runs every TFLite variant on the test set with the app's preprocessing and compares with PyTorch. |
| `calibrate_tflite.py` | v3: calibrates the exported file itself (`_not_food` balance, temperature, `min_confidence`) and gives its accuracy on every test set. |
| `write_attribution.py` | Writes `docs/third-party/food-model/` (pictures, detector photos, Nutrition5k dishes, per-source counts). |

## Reproduce end to end (Windows, NVIDIA GPU)

Work directory outside the repo (datasets, venvs and checkpoints never go into git): `D:\FoodML`; the photos
used for training are mirrored to an SSD (`E:\FoodML_ssd`, set `FOODML_MIRROR`, see `food_items.py`) because
random reads of small files on the hard disk were far too slow.

```sh
# 1. two virtualenvs (TensorFlow and PyTorch pin different protobuf versions)
python -m venv D:/FoodML/venv
D:/FoodML/venv/Scripts/python -m pip install torch==2.11.0 torchvision==0.26.0 --index-url https://download.pytorch.org/whl/cu128
D:/FoodML/venv/Scripts/python -m pip install -r tools/food-model/requirements.txt
python -m venv D:/FoodML/venv_tf
D:/FoodML/venv_tf/Scripts/python -m pip install -r tools/food-model/requirements-export.txt
export YOLO_OFFLINE=1 HF_HOME=D:/FoodML/hf
export FOODML_MIRROR="D:/FoodML/images=E:/FoodML_ssd/images;D:/FoodML/dl/frb/x=E:/FoodML_ssd/frb_x"

# 2. data (v1 steps as in 1.5.0, with the v2 class list): Open Images metadata into D:/FoodML/data/oi_meta,
#    box CSVs (v6/oidv6-train-annotations-bbox.csv, v5/validation-, v5/test-annotations-bbox.csv,
#    v7/oidv7-class-descriptions-boxable.csv) into D:/FoodML/data/oi_boxes; the MyFoodRepo tar
#    (Dataset Ninja copy) extracted to D:/FoodML/dl/frb/x
cd tools/food-model
PY=D:/FoodML/venv/Scripts/python
$PY oi_label_stats.py --work D:/FoodML
$PY select_classes.py --work D:/FoodML --oi
$PY fetch_open_images.py --work D:/FoodML --cap 1500
$PY fetch_wikimedia.py --work D:/FoodML                 # plan (API, cached) + thumbnails
$PY oi_boxes.py --work D:/FoodML
$PY frb_index.py --work D:/FoodML
$PY fetch_nutrition5k.py --work D:/FoodML
$PY build_detector_data.py select --work D:/FoodML
$PY build_detector_data.py download --work D:/FoodML
$PY mirror_photos.py D:/FoodML/images/oi E:/FoodML_ssd/images/oi D:/FoodML/images/oi_box E:/FoodML_ssd/images/oi_box \
    D:/FoodML/images/wm E:/FoodML_ssd/images/wm             # + the MyFoodRepo images to E:/FoodML_ssd/frb_x

# 3. detector
$PY build_detector_data.py build --work D:/FoodML --out-root E:/FoodML_ssd/det --size 384 --not-food 3000 --only-fast
$PY train_detector.py --data E:/FoodML_ssd/det/data.yaml --name det1 --model yolo11n.pt --epochs 30 --imgsz 320 \
    --batch 64 --workers 4
$PY export_detector.py onnx --weights D:/FoodML/runs/det/det1/weights/best.pt --out D:/FoodML/runs/det/det1/export \
    --size 320 --calib-dir E:/FoodML_ssd/det/images/val
D:/FoodML/venv_tf/Scripts/python export_detector.py tflite --out D:/FoodML/runs/det/det1/export --variant fp16 \
    --score-threshold <chosen> --assets ../../app/src/main/assets/food
D:/FoodML/venv_tf/Scripts/python train_detector.py --eval-only --data E:/FoodML_ssd/det/data.yaml \
    --tflite ../../app/src/main/assets/food/food_detector.tflite --out D:/FoodML/runs/det/det1/app_eval_tflite.json

# 4. classifier
$PY build_dataset_v2.py --work D:/FoodML --crop-root E:/FoodML_ssd/images --out D:/FoodML/dataset_v2 \
    --out-classes classes.csv --handoff food_classes_v2.csv
cp D:/FoodML/dataset_v2/*.csv E:/FoodML_ssd/dataset_v2/
$PY pack.py --work E:/FoodML_ssd --dataset dataset_v2 --short 292 --reuse E:/FoodML_ssd/dataset_v1   # v1 pack copy
$PY train.py --work E:/FoodML_ssd --dataset dataset_v2 --runs D:/FoodML/runs --out cls2 \
    --model mobilenetv4_conv_medium.e250_r384_in12k_ft_in1k --size 256 --resize 292 --sizes 224,256 --switch 0.5 \
    --epochs 24 --batch 64 --lr 6e-5 --head-lr 1e-3 --zero-head --wd 0.05 --workers 3 --boost-not-food 3 --resume
# evaluated at 320 px (resize 366): final.pt = best.pt with size 320 / resize 366 and the `_not_food` logit
# shifted by -0.75 (MODEL_CARD)
$PY evaluate.py --work D:/FoodML --data E:/FoodML_ssd/dataset_v2 --runs D:/FoodML/runs --run cls2 --ckpt final.pt \
    --classes classes.csv --tag final
$PY evaluate.py ... --test test_v1.csv --tag final_v1  # the 1.5.0 test photos

# 5. container rule, grams head, export, parity, timing
$PY compare_group_rules.py --data E:/FoodML_ssd/det --weights D:/FoodML/runs/det/det1/weights/best.pt
$PY grams.py features --work D:/FoodML --runs D:/FoodML/runs --run cls2 --ckpt final.pt \
    --detector D:/FoodML/runs/det/det1/weights/best.pt --detector-spec ../../app/src/main/assets/food/food_detector.json
$PY grams.py compare --work D:/FoodML --runs D:/FoodML/runs --run cls2 --classes classes.csv --foods <foods.tsv v2>
$PY grams.py fit --rule drop_container --work D:/FoodML --runs D:/FoodML/runs --run cls2 --classes classes.csv \
    --foods <foods.tsv v2 (portions)>
$PY export.py onnx --work E:/FoodML_ssd --dataset dataset_v2 --runs D:/FoodML/runs --run cls2 --ckpt final.pt \
    --grams-head D:/FoodML/runs/cls2/grams/head.pt
D:/FoodML/venv_tf/Scripts/python export.py tflite --work D:/FoodML --runs D:/FoodML/runs --run cls2 \
    --classes classes.csv --temperature 0.78 --min-confidence 0.05 --variant float16 \
    --grams D:/FoodML/runs/cls2/grams/grams.json --name fortia-food-2      # int8: see MODEL_CARD "Export"
D:/FoodML/venv_tf/Scripts/python parity.py --work D:/FoodML --data E:/FoodML_ssd/dataset_v2 --runs D:/FoodML/runs \
    --run cls2 --eval-dir eval_final --spec ../../app/src/main/assets/food/food_model.json
D:/FoodML/venv_tf/Scripts/python time_pipeline.py --assets ../../app/src/main/assets/food --photo <a 12 MP photo>
$PY write_attribution.py --work D:/FoodML --dataset dataset_v2 --detector E:/FoodML_ssd/det --nutrition5k
```

Downloads take a few hours; the detector trains in ~3.5 h and the classifier in ~3.5 h on an RTX 2060 SUPER
(both at once, sharing the GPU); the TFLite conversions ~20 min each on the CPU. Results vary slightly between
runs; the Commons data changes over time.

### v3 (1.8.0): new classes, distillation, full int8, image embedding

```sh
# 6. data for the v3 candidates (classes_config.py: categories + `search:` file-name searches)
$PY select_classes.py --work D:/FoodML --oi
$PY fetch_open_images.py --work D:/FoodML --cap 1500 --classes <the v3 candidate ids, comma-separated>
$PY fetch_wikimedia.py --work D:/FoodML --plan && $PY fetch_wikimedia.py --work D:/FoodML --download
$PY make_crops.py --work D:/FoodML --dataset D:/FoodML/dataset_v2          # v2's crops, if the SSD copy is gone
$PY build_dataset_v3.py --work D:/FoodML --v2 D:/FoodML/dataset_v2 --out D:/FoodML/dataset_v3 \
    --crop-root E:/FoodML_ssd/images --out-classes classes_v3.csv --handoff food_classes_v3.csv \
    --filter-ckpt D:/FoodML/runs/cls2/final.pt --max-not-food 0.4
cp D:/FoodML/dataset_v3/*.csv E:/FoodML_ssd/dataset_v3/
$PY pack.py --work E:/FoodML_ssd --dataset dataset_v3 --short 320

# 7. teacher (SigLIP 2 linear probe + the 1.6.0 classifier) on 4 stored views per training picture
OLD="--old-ckpt D:/FoodML/runs/cls2/best.pt --old-classes classes_v2.csv --classes classes_v3.csv"
$PY teacher.py features --data E:/FoodML_ssd/dataset_v3 --out E:/FoodML_ssd/teacher_v3 --split train --views 4 $OLD
$PY teacher.py features --data E:/FoodML_ssd/dataset_v3 --out E:/FoodML_ssd/teacher_v3 --split val $OLD   # and test
$PY teacher.py probe --data E:/FoodML_ssd/dataset_v3 --out E:/FoodML_ssd/teacher_v3 --epochs 8 --old-weight 0.3

# 8. classifier v3: warm start from v2, head narrowed to 640 units, distillation
$PY train.py --work E:/FoodML_ssd --dataset dataset_v3 --runs D:/FoodML/runs/v3 --out cls3 \
    --model mobilenetv4_conv_medium.e250_r384_in12k_ft_in1k --size 288 --resize 329 --sizes 256,288 --switch 0.5 \
    --epochs 12 --batch 64 --lr 4e-5 --head-lr 5e-4 --wd 0.05 --warmup 0.5 --workers 3 --boost-not-food 3 \
    --init D:/FoodML/runs/cls2/best.pt --init-classes classes_v2.csv --classes classes_v3.csv --head-hidden 640 \
    --teacher E:/FoodML_ssd/teacher_v3 --kd-alpha 0.7 --kd-t 1.0
# eval320.pt = best.pt with size 320 / resize 366; evaluate.py on test.csv, test_v2.csv (1.6.0's), test_v1.csv

# 9. int8: re-parametrisation + quantisation-aware fine-tuning (quant_sim.py shows why); `_not_food` logit −1.0
#    (chosen on the float model's val/test: evaluate.py), the shipped run is `cls3q`
$PY qat.py --ckpt D:/FoodML/runs/v3/cls3/eval320.pt --data E:/FoodML_ssd/dataset_v3 --out D:/FoodML/runs/v3/cls3q/final.pt \
    --steps 1500 --recalib 500 --batch 32 --lr 1e-5 --smooth 0.75 --range-weight 1.0 --workers 3 \
    --cache D:/FoodML/runs/v3/qsim_cache_v3.pt --not-food-shift -1.0
FOODS=<food-db3's app/src/main/assets/food/foods.tsv (portions of the v3 classes)>
$PY grams.py features --work D:/FoodML --runs D:/FoodML/runs/v3 --run cls3q --ckpt final.pt \
    --detector D:/FoodML/runs/det/det1/weights/best.pt --detector-spec ../../app/src/main/assets/food/food_detector.json
$PY grams.py fit --rule drop_container --work D:/FoodML --runs D:/FoodML/runs/v3 --run cls3q \
    --classes classes_v3.csv --foods "$FOODS"          # a first head (float features)
# the int8 network's own features: a debug export with them as a 4th output, converted the same way (never shipped)
$PY export.py onnx --work E:/FoodML_ssd --dataset dataset_v3 --runs D:/FoodML/runs/v3 --run cls3qf --ckpt final.pt \
    --grams-head D:/FoodML/runs/v3/cls3q/grams/head.pt --embedding --features-output --calib 500   # cls3qf = a copy
(cd D:/FoodML/runs/v3/cls3qf/export && onnx2tf -i model.onnx -o tflite -oiqt -qt per-channel -cind input calib.npy \
    "[[[[0.485, 0.456, 0.406]]]]" "[[[[0.229, 0.224, 0.225]]]]" -n)          # the conversion export.py tflite runs
D:/FoodML/venv_tf/Scripts/python grams.py tflite --with-features --work D:/FoodML --runs D:/FoodML/runs/v3 \
    --run cls3qf --tflite D:/FoodML/runs/v3/cls3qf/export/tflite/model_integer_quant.tflite \
    --spec <a v3 food_model.json> --detector-spec ../../app/src/main/assets/food/food_detector.json
cp D:/FoodML/runs/v3/cls3qf/grams/tflite.npz D:/FoodML/runs/v3/cls3q/grams/
# the head fitted on those int8 features, its weights within ±0.5 so that the file's one int8 scale suits them
# all (the float-fitted head lost 42 % of its weights to rounding and the int8 features differ from the float ones:
# its estimate measured on the int8 file was 39 % off per plate instead of 28 %)
$PY grams.py fit --tflite-features --int8-head --head-max 0.5 --rule drop_container --work D:/FoodML \
    --runs D:/FoodML/runs/v3 --run cls3q --classes classes_v3.csv --foods "$FOODS"
$PY export.py onnx --work E:/FoodML_ssd --dataset dataset_v3 --runs D:/FoodML/runs/v3 --run cls3q --ckpt final.pt \
    --grams-head D:/FoodML/runs/v3/cls3q/grams/head.pt --embedding --calib 500
D:/FoodML/venv_tf/Scripts/python export.py tflite --work D:/FoodML --runs D:/FoodML/runs/v3 --run cls3q \
    --classes classes_v3.csv --variant integer_quant --temperature 1.0 --min-confidence 0.05 \
    --grams D:/FoodML/runs/v3/cls3q/grams/grams.json --name fortia-food-3 --strip --assets <staging dir>
# the app formula and its clamp fitted on what the int8 file itself outputs (its log_grams and top-5)
D:/FoodML/venv_tf/Scripts/python grams.py tflite --work D:/FoodML --runs D:/FoodML/runs/v3 --run cls3q \
    --tflite <staging dir>/food_model.tflite --spec <staging dir>/food_model.json \
    --detector-spec ../../app/src/main/assets/food/food_detector.json
$PY grams.py fit --tflite yes --rule drop_container --work D:/FoodML --runs D:/FoodML/runs/v3 --run cls3q \
    --classes classes_v3.csv --foods "$FOODS"            # -> grams/grams.json (the head in the file is kept)
D:/FoodML/venv_tf/Scripts/python calibrate_tflite.py --model <staging dir>/food_model.tflite \
    --spec <staging dir>/food_model.json --data E:/FoodML_ssd/dataset_v3 --out D:/FoodML/runs/v3/cls3q/calib --shift 0
D:/FoodML/venv_tf/Scripts/python embed_tflite.py --model <staging dir>/food_model.tflite \
    --out D:/FoodML/runs/v3/embed_eval/emb_v3_int8.npz
$PY embed_eval.py --rule D:/FoodML/runs/v3/embed_eval/emb_v3_int8.npz   # recall@k, accept rule -> embedding_contract.md
# the same file again (--skip-convert) with the calibrated temperature / min_confidence and the embedding block
D:/FoodML/venv_tf/Scripts/python export.py tflite --skip-convert --work D:/FoodML --runs D:/FoodML/runs/v3 --run cls3q \
    --classes classes_v3.csv --variant integer_quant --temperature <T> --min-confidence <m> \
    --grams D:/FoodML/runs/v3/cls3q/grams/grams.json --embedding-spec <json: dim, space, match> --name fortia-food-3 \
    --strip --assets ../../app/src/main/assets/food
cp classes_v3.csv classes.csv

# 10. detector: the 1.6.0 weights exported again (boxes no longer share a tensor with the scores); shipped: dynint8
$PY export_detector.py onnx --weights D:/FoodML/runs/det/det1/weights/best.pt --out D:/FoodML/runs/v3/det_int8 \
    --size 320 --calib-dir E:/FoodML_ssd/det/images/val
D:/FoodML/venv_tf/Scripts/python export_detector.py tflite --out D:/FoodML/runs/v3/det_int8 --variant dynint8 \
    --score-threshold 0.25 --strip --name fortia-food-detector-1 --notes "<what changed>" \
    --assets ../../app/src/main/assets/food
```

## Contracts (what the app reads)

`food_model.json` (version 3; version 2 = the same without `embedding`):

- `labels`: class ids in output order; the last one is `_not_food` (also in `not_food_label`). Every other
  label is a food id with `visual` = 1 in `app/src/main/assets/food/foods.tsv`.
- `input`: `[1, 320, 320, 3]` float32, NHWC, RGB. Decode the photo upright (EXIF), resize so the **short side is
  366** (bilinear, antialiased), **centre-crop 320 × 320**, scale to 0..1, then `(x − mean) / std` with the
  ImageNet **mean (0.485, 0.456, 0.406)** and **std (0.229, 0.224, 0.225)** — all of it read from the json.
- `output`: the logits `[1, N]` (index in `output.index`; also the output with N values). Probabilities =
  `softmax(logits / temperature)`; `min_confidence` as in v1.
- `grams`: the second output `log_grams` `[1, 1]` and the app formula
  `ln est = b0 + b_model·y + b_portion·ln(portion) + b_share·ln(share)`, clamped and rounded; `enabled` false =
  use the catalog portion (see the handoff's `grams_contract.md`).
- `embedding`: the third output `[1, 960]` (index in `embedding.output.index`), computed in its own run on the
  **whole photo stretched** to 320 × 320 (then the same 0..1 and mean/std), L2-normalised by the app and matched by
  cosine with `match.threshold` / `margin` / `suggest_threshold`; embeddings of another `space` are recomputed
  (the handoff's `embedding_contract.md`).
- Full int8 inside, float32 input and outputs (the app's code is unchanged).

`food_detector.json` (version 1): input `[1, 320, 320, 3]` float32 RGB 0..1, the **whole photo stretched**;
outputs boxes `[1, 10, 4]` (ymin, xmin, ymax, xmax normalised to the photo), scores `[1, 10]`, count `[1]` (their
indices in `outputs.*.index`); the app keeps scores ≥ `score_threshold`, drops boxes shorter than `min_box_side`,
applies `group_suppression` (a box holding ≥ 2 other boxes is dropped), keeps at most `max_items`, and crops
each item as a square with `crop.margin` (see `detector_contract.md`).

## Notes

- Open Images labels are human-verified but multi-label; `rank` in `classes_config.py` decides (a specific dish
  beats a generic one). Photos with two labels of the same rank are left out.
- MobileNetV4 loses its pretrained features with the v1 recipe (AdamW 3e-4 on everything, random head: the first
  gradients are ~1 000× the usual); the backbone needs ~6e-5 with a zero-initialised head at 1e-3.
- `onnx2tf` misplaces the padding of a standalone NCHW `Pad` op, so `export.py` folds those pads into the
  following convolution; `parity.py` checks the result against PyTorch.
- int8 (MODEL_CARD "Export"): 1.6.0 shipped fp16 because full int8 lost 16 points of top-1 (a few huge channels
  in the residual streams under per-tensor int8) and the int8 detector lost its scores (pixel boxes and scores in
  one tensor). v3 re-parametrises the classifier exactly (`quant_sim.py`), fine-tunes it with fake int8 and a
  penalty that keeps the float activations inside their int8 ranges (`qat.py`: the converter re-measures the
  float min/max), and exports the detector with normalised boxes in their own tensor; the detector ships with
  dynamic-range int8 (int8 weights, float activations: the fp16 accuracy at half the size).
- On Windows every DataLoader worker maps the CUDA libraries (~1.5 GB of commit each): few worker processes with
  threads inside.
