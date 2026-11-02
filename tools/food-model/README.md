# Food recognition model (Comida tab)

Our own on-device image classifier for the Comida tab: given a photo of a meal it ranks the visual food
classes in `classes.csv` (plus `_not_food`); the app shows the top 5 and the user always confirms. It runs on
the phone with LiteRT (TensorFlow Lite); the photo never leaves the device.

- Output: `app/src/main/assets/food/food_model.tflite` + `food_model.json` (labels in output order and the
  exact preprocessing; see "Contract" below). Results and limits: [MODEL_CARD.md](MODEL_CARD.md).
- Training images: Open Images V7 (CC BY 2.0) and Wikimedia Commons (CC0 / public domain / CC BY / CC BY-SA)
  only. Attribution: `docs/third-party/food-model/`.
- Backbone: `tf_efficientnet_lite0.in1k` from timm, a port of Google's EfficientNet-Lite0 ImageNet checkpoint
  (Apache-2.0).

Nothing here is needed to build the app: the two asset files are committed. These scripts rebuild them.

## Files

| File | What it does |
|---|---|
| `classes_config.py` | Candidate classes: id, names, group, Open Images labels merged into it, Commons categories, rank. |
| `oi_label_stats.py` | Counts human-verified positive Open Images labels and how often each co-occurs with a food label. |
| `select_classes.py` | `--oi`: gives each Open Images photo one class (highest rank wins, ties dropped) and picks `_not_food` photos (verified "no Food"). |
| `fetch_open_images.py` | Downloads those photos (official bucket, else the dataset's Flickr thumbnail URL) resized to 320 px, with licence metadata. |
| `fetch_wikimedia.py` | `--plan`: walks the Commons categories (API, cached), keeps free licences; `--download`: thumbnails. |
| `build_dataset.py` | Near-duplicate removal, per-class cap, train/val/test split, drops classes under 150 training photos, writes `classes.csv` and the attribution list. |
| `pack.py` | Packs the train/val photos into one file each (the work disk is a hard disk). |
| `static_pad.py` | Makes the TF-style "SAME" padding static so the model exports cleanly. |
| `train.py` | Fine-tuning on the GPU (mixed precision); `--init`/`--boost-not-food` for the follow-up run. |
| `drop_classes.py` | Final touches on a checkpoint: cut a class's output row, shift the `_not_food` logit. |
| `evaluate.py` | Test metrics, per group / class, confusions, `_not_food`, temperature scaling, `min_confidence`. |
| `export.py` | `onnx` (PyTorch venv) and `tflite` (export venv, onnx2tf) steps; writes the two assets. |
| `parity.py` | Runs every TFLite variant on the test set with the app's preprocessing and compares with PyTorch. |
| `write_attribution.py` | Writes `docs/third-party/food-model/images.csv.gz` and the per-source summary. |

## Reproduce end to end (Windows, NVIDIA GPU)

Work directory outside the repo (datasets, venvs and checkpoints never go into git): `D:\FoodML`.

```sh
# 1. two virtualenvs (TensorFlow and PyTorch pin different protobuf versions)
python -m venv D:/FoodML/venv
D:/FoodML/venv/Scripts/python -m pip install torch==2.11.0 torchvision==0.26.0 --index-url https://download.pytorch.org/whl/cu128
D:/FoodML/venv/Scripts/python -m pip install -r tools/food-model/requirements.txt
python -m venv D:/FoodML/venv_tf
D:/FoodML/venv_tf/Scripts/python -m pip install -r tools/food-model/requirements-export.txt

# 2. Open Images V7 metadata into D:/FoodML/data/oi_meta (from https://storage.googleapis.com/openimages/):
#    v7/oidv7-class-descriptions.csv, v7/oidv7-{train,val,test}-annotations-human-imagelabels.csv,
#    v6/oidv6-train-images-with-labels-with-rotation.csv,
#    2018_04/validation/validation-images-with-rotation.csv, 2018_04/test/test-images-with-rotation.csv
cd tools/food-model
PY=D:/FoodML/venv/Scripts/python
$PY oi_label_stats.py --work D:/FoodML
$PY select_classes.py --work D:/FoodML --oi
$PY fetch_open_images.py --work D:/FoodML            # ~100k photos, resized on the fly
$PY fetch_wikimedia.py --work D:/FoodML --plan       # API only (cached in D:/FoodML/wm/cache)
$PY fetch_wikimedia.py --work D:/FoodML --download   # thumbnails, polite rate
$PY build_dataset.py --work D:/FoodML --out-classes D:/FoodML/classes_302.csv
$PY write_attribution.py --work D:/FoodML
$PY pack.py --work D:/FoodML
$PY train.py --work D:/FoodML --out run2 --resume   # 25 epochs, batch 64, AdamW 3e-4; --resume continues after a crash
$PY train.py --work D:/FoodML --out run2b --init D:/FoodML/runs/run2/best.pt --boost-not-food 6 \
    --epochs 3 --lr 1e-4 --warmup 0.2 --ema 0.999
$PY drop_classes.py --work D:/FoodML --run run2b --run-out final --dataset dataset --dataset-out dataset_final \
    --classes D:/FoodML/classes_302.csv --classes-out classes.csv --drop dolma --not-food-bias -1.0
$PY evaluate.py --work D:/FoodML --dataset dataset_final --run final --classes classes.csv
$PY export.py onnx --work D:/FoodML --dataset dataset_final --run final
D:/FoodML/venv_tf/Scripts/python export.py tflite --work D:/FoodML --run final --classes classes.csv \
    --temperature 0.94 --min-confidence 0.05 --variant integer_quant --name fortia-food-1
D:/FoodML/venv_tf/Scripts/python parity.py --work D:/FoodML --dataset dataset_final --run final \
    --spec ../../app/src/main/assets/food/food_model.json
```

Downloads (Open Images bucket, Flickr thumbnails, Commons API/thumbnails) take a few hours; training ~1.6 h
(+12 min follow-up) on an RTX 2060 SUPER; the TFLite conversion ~20 min on the CPU. Results vary slightly
between runs (random sampling of photos and augmentation). The Commons data changes over time, so a rerun
picks slightly different photos.

## Contract (what the app reads)

`food_model.json`:

- `labels`: class ids in output order; the last one is `_not_food` (also in `not_food_label`). Every other
  label is a food id with `visual` = 1 in `app/src/main/assets/food/foods.tsv`.
- `input`: `[1, 224, 224, 3]` float32, NHWC, RGB. Preprocessing: decode the photo upright (EXIF), resize so
  the **short side is 256** (long side = floor(long × 256 / short)), bilinear and antialiased (for a big camera
  photo decode with `inSampleSize` or scale in steps), **centre-crop 224 × 224**, scale to 0..1, then
  `(x − mean) / std` with **mean = std = 0.5** (so the input is in −1..1; not the ImageNet values).
- `output`: `[1, N]` float32 **logits**. Probabilities = `softmax(logits / temperature)`.
- `min_confidence`: if `_not_food` has the highest probability, or the best food probability is below this,
  the app says it doesn't recognise food (and offers search).
- The TFLite model is int8 inside with float32 input and output (quantize/dequantize ops in the graph).

## Notes

- Open Images labels are human-verified, but a photo often carries several food labels; `rank` in
  `classes_config.py` decides (a specific dish beats a generic one, a dish beats its ingredients). Photos with
  two labels of the same rank are left out.
- `onnx2tf` misplaces the padding of a standalone NCHW `Pad` op (it pads N and H instead of H and W), so
  `export.py` folds those pads into the following convolution before converting; `parity.py` checks the
  result against PyTorch.
- On Windows every DataLoader worker maps the CUDA libraries (~1.5 GB of commit each): `train.py` uses few
  worker processes with threads inside.
