# Food recognition model: sources and licences

`app/src/main/assets/food/food_model.tflite` is Fortia's own food classifier (scripts and details:
`tools/food-model/`, results and limits: `tools/food-model/MODEL_CARD.md`). This folder records what it was
made from. The training photos are **not** distributed with the app or the source; only the trained weights
are.

## Pretrained backbone

- **EfficientNet-Lite0**, ImageNet-1k weights by Google (TensorFlow TPU repository,
  https://github.com/tensorflow/tpu/tree/master/models/official/efficientnet/lite), **Apache-2.0**.
- Used through **timm** (PyTorch Image Models, https://github.com/huggingface/pytorch-image-models,
  Apache-2.0) as `tf_efficientnet_lite0.in1k`, a direct port of that checkpoint; the model card
  (https://huggingface.co/timm/tf_efficientnet_lite0.in1k) lists the licence as apache-2.0.
- Fine-tuned by us on the photos below; the classifier layer is new.

## Training photos

| Source | Licence of the photos | Used |
|---|---|---|
| **Open Images Dataset V7** (Google LLC), https://storage.googleapis.com/openimages/web/index.html — human-verified image-level labels | Images: listed in the dataset as **CC BY 2.0** by their authors on Flickr (every photo's licence URL in the dataset metadata is https://creativecommons.org/licenses/by/2.0/). Annotations: CC BY 4.0 (Google LLC). | photos fetched from the dataset's open bucket (`open-images-dataset` on S3) or, when the bucket lacks them, from the dataset's own Flickr thumbnail URL |
| **Wikimedia Commons**, https://commons.wikimedia.org — food categories | Only files whose machine-readable licence is **CC0**, **public domain**, **CC BY** or **CC BY-SA** (any version); no NC/ND/fair-use files | 500 px thumbnails through the Commons API |

Nothing else was used (in particular no Food-101, UEC-Food, ISIA or other research-only datasets).
Exact counts per class and source: `sources.csv`; per licence: `licences.csv`.

`images.csv.gz` lists **every** photo used (train, validation and test), one row each:

| Column | Meaning |
|---|---|
| `split` | train / val / test |
| `class` | the label the photo was trained with (`dolma` photos were used in training; that output was removed from the shipped model afterwards) |
| `source` | `openimages` or `wikimedia` |
| `id` | Open Images ImageID (empty for Commons) |
| `page` | source page: `flickr:<user>/<photo>` = https://www.flickr.com/photos/<user>/<photo>, `commons:<file>` = https://commons.wikimedia.org/wiki/File:<file> |
| `author` | author as given by the source |
| `licence` | licence of that photo |

Photos were resized to at most 320 px on the short side for training and are not redistributed. If you are
the author of a photo listed here and want it removed from future trainings, open an issue at
https://github.com/NOrtizSu/Fortia-source.
