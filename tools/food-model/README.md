# Food recognition models (Comida tab)

Our own on-device models for the Comida tab, run on the phone with LiteRT (TensorFlow Lite); the photo never
leaves the device. Since 1.6.0 (models v2):

- **Classifier** `food_model.tflite` + `food_model.json`: a photo (or one item's crop) → the visual food classes
  in `classes.csv` (329 foods + `_not_food`); the app shows the top 5 and the user always confirms. A second
  output, `log_grams`, is the **grams suggestion** (contract: `grams_contract.md` in the handoff, summarised
  below).
- **Detector** `food_detector.tflite` + `food_detector.json`: class-agnostic "food item" boxes, with decoding and
  NMS inside the graph, so a plate with several foods becomes several items, each cropped and classified.
- Results and limits: [MODEL_CARD.md](MODEL_CARD.md). Sources and licences: `docs/third-party/food-model/`.

Nothing here is needed to build the app: the asset files are committed. These scripts rebuild them.

## Files

| File | What it does |
|---|---|
| `classes_config.py` | Candidate classes: id, names, group, Open Images labels, Commons categories, rank (370 candidates in v2). |
| `classes.csv` / `classes_v1.csv` | The v2 class list in output order (with `status` kept/recovered/new) / the 1.5.0 list. |
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
| `mirror_photos.py` | Copies photo folders to an SSD in on-disk order (the work disk is a hard disk). |
| `pack.py` | Packs the train/val photos into one file each (`--reuse` copies an earlier pack's photos). |
| `static_pad.py` | Makes TF-style "SAME" padding static (v1 EfficientNet-Lite) so the model exports cleanly. |
| `train.py` | Fine-tuning on the GPU (mixed precision, EMA, progressive resizing, per-group learning rates). |
| `drop_classes.py` | Final touches on a checkpoint: cut a class's output row, shift the `_not_food` logit. |
| `evaluate.py` | Test metrics (also on the v1 test photos), per group / class, confusions, `_not_food`, temperature, `min_confidence`. |
| `grams.py` | `features` + `fit`: the grams head on the classifier's features, calibrated on Nutrition5k; MdAPE vs the default portion. |
| `export.py` | `onnx` (PyTorch venv; `--grams-head` adds the `log_grams` output) and `tflite` (export venv, onnx2tf); writes the assets. |
| `parity.py` | Runs every TFLite variant on the test set with the app's preprocessing and compares with PyTorch. |
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

## Contracts (what the app reads)

`food_model.json` (version 2):

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
- fp16 weights, float32 input and outputs (full int8 loses 16 points of top-1 with this network).

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
- The detector's int8 TFLite lost most of its recall in the first export (the score tensor quantised badly), so
  it ships as fp16; the classifier's int8 loses 16 points of top-1, so it ships as fp16 too (MODEL_CARD).
- On Windows every DataLoader worker maps the CUDA libraries (~1.5 GB of commit each): few worker processes with
  threads inside.
