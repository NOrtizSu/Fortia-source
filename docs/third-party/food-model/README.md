# Food recognition models: sources and licences

The app's food models are Fortia's own (scripts and details: `tools/food-model/`, results and limits:
`tools/food-model/MODEL_CARD.md`). This folder records what they were made from. The training photos are
**not** distributed with the app or the source; only the trained weights are.

| File in `app/src/main/assets/food/` | What |
|---|---|
| `food_model.tflite` (+ `.json`) | the food classifier (329 foods + "not food") with its grams-estimate head |
| `food_detector.tflite` (+ `.json`) | the class-agnostic "food item" detector (several foods per photo) |

## Pretrained weights and code

- **Classifier backbone — MobileNetV4-Conv-Medium**, ImageNet-12k pretraining + ImageNet-1k fine-tuning by Ross
  Wightman for **timm** (PyTorch Image Models, https://github.com/huggingface/pytorch-image-models, Apache-2.0),
  `mobilenetv4_conv_medium.e250_r384_in12k_ft_in1k`; the model card
  (https://huggingface.co/timm/mobilenetv4_conv_medium.e250_r384_in12k_ft_in1k) lists the licence as
  **apache-2.0**. Architecture: Qin et al., "MobileNetV4 — Universal Models for the Mobile Ecosystem" (Google,
  2024). Fine-tuned by us; the classifier layer and the grams head are new.
- **Detector — Ultralytics YOLO11n** (https://github.com/ultralytics/ultralytics), code and COCO-pretrained
  weights © Ultralytics, **AGPL-3.0** (the app's own licence). Fine-tuned by us as a one-class "food item"
  detector; box decoding and non-maximum suppression were put inside the TFLite graph by
  `tools/food-model/export_detector.py`.
- The 1.5.0 classifier (EfficientNet-Lite0, Google, Apache-2.0, via timm `tf_efficientnet_lite0.in1k`) is no
  longer shipped; its training photos are all part of the v2 set below.

## Training data

| Source | Licence | Used for |
|---|---|---|
| **Open Images Dataset V7** (Google LLC), https://storage.googleapis.com/openimages/web/index.html — human-verified image-level labels and human-drawn **boxes** | Images: listed in the dataset as **CC BY 2.0** by their authors on Flickr (every photo's licence URL in the dataset metadata is https://creativecommons.org/licenses/by/2.0/). Annotations (labels and boxes): **CC BY 4.0** (Google LLC). | classifier photos; crops of food boxes (classifier); food boxes (detector) |
| **Wikimedia Commons**, https://commons.wikimedia.org — food categories | Only files whose machine-readable licence is **CC0**, **public domain**, **CC BY** or **CC BY-SA** (any version); no NC/ND/fair-use files | classifier photos (500 px thumbnails through the Commons API) |
| **MyFoodRepo / AIcrowd Food Recognition Benchmark 2022** (v2.0: 39 962 training + 1 000 validation photos with every food segmented), data from the MyFoodRepo app (Digital Epidemiology Lab, EPFL), Mohanty et al., "The Food Recognition Benchmark: Using Deep Learning to Recognize Food in Images", Frontiers in Nutrition 9:875143 (2022), https://www.aicrowd.com/challenges/food-recognition-benchmark-2022 | **CC BY 4.0** (stated in the paper: "licensed under the Creative Commons CC-BY-4.0 license"). The AIcrowd download needs an account, so the identical public copy of Dataset Ninja (https://datasetninja.com/food-recognition, Supervisely format) was used; its page labels the copy CC0, we follow the original's CC BY 4.0. | item crops (classifier); item boxes from the polygons (detector) |
| **Nutrition5k** (Google), Thames et al., "Nutrition5k: Towards Automatic Nutritional Understanding of Generic Food", CVPR 2021, https://github.com/google-research-datasets/Nutrition5k | **CC BY 4.0** ("We release all Nutrition5k data under the Creative Commons V4.0 license", linking CC BY 4.0) | the grams head: overhead photos (`realsense_overhead/*/rgb.png`) of 3 262 dishes with their weighed mass; no videos, no depth |

Nothing else was used (in particular no Food-101, UEC-Food, ISIA, Food2K or other research-only datasets). Exact
counts per class and source: `sources.csv`; per licence: `licences.csv`.

`images.csv.gz` lists **every** classifier training/validation/test picture (photo or item crop), one row each:

| Column | Meaning |
|---|---|
| `split` | train / val / test |
| `class` | the label it was trained with |
| `source` | `openimages`, `openimages_box` (a crop of one food box), `wikimedia`, `myfoodrepo` (a crop of one segmented food) |
| `id` | Open Images ImageID (`#xmin,ymin,xmax,ymax`, normalised, for box crops); MyFoodRepo `t`/`v` (training / validation set) + image number + `#x0,y0,x1,y1` in pixels; empty for Commons |
| `page` | source page: `flickr:<user>/<photo>` = https://www.flickr.com/photos/<user>/<photo>, `commons:<file>` = https://commons.wikimedia.org/wiki/File:<file>, `frb2022` = https://www.aicrowd.com/challenges/food-recognition-benchmark-2022 |
| `author` | author as given by the source ("MyFoodRepo users" for MyFoodRepo, whose photos carry no per-photo author) |
| `licence` | licence of that picture |

`detector_photos.csv.gz` lists the photos the detector was trained and tested on (`split`, `source`, `id`) — all
of them Open Images (licence and author as in `images.csv.gz` / the Open Images metadata) or MyFoodRepo (CC BY 4.0).
`nutrition5k_dishes.csv.gz` lists the Nutrition5k dish ids used for the grams head and its test.

Photos were resized to at most 320 px (Open Images boxes: 512 px) on the short side for training and are not
redistributed. If you are the author of a photo listed here and want it removed from future trainings, open an
issue at https://github.com/NOrtizSu/Fortia-source.
