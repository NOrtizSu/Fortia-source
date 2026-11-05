# SPDX-License-Identifier: AGPL-3.0-or-later
"""Embeddings of the exported food_model.tflite on the off_retrieval.py set, as the app computes them
(embedding_contract.md: the json's `embedding` block — whole picture stretched to the input size, the classifier's
normalisation, the `embedding` output, L2-normalised). Writes an .npz that `embed_eval.py --rule` reads.

usage (export venv): python embed_tflite.py --model <assets>/food_model.tflite --off D:/FoodML/off_eval \
          --meals D:/FoodML/dataset_v2/test_v1.csv --out D:/FoodML/runs/v3/embed_eval/emb_v3_int8.npz
"""
import argparse
import json
import os
import sys

import numpy as np
import pandas as pd
from ai_edge_litert.interpreter import Interpreter
from PIL import Image, ImageOps

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from food_items import fast_path  # noqa: E402


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", required=True)
    ap.add_argument("--off", default="D:/FoodML/off_eval")
    ap.add_argument("--meals", default="D:/FoodML/dataset_v2/test_v1.csv")
    ap.add_argument("--n-meals", type=int, default=1000)
    ap.add_argument("--out", required=True)
    a = ap.parse_args()
    spec = json.load(open(os.path.splitext(a.model)[0] + ".json", encoding="utf-8"))
    inp, emb = spec["input"], spec["embedding"]
    assert emb["input"]["resize"] == "stretch"
    it = Interpreter(model_path=a.model, num_threads=4)
    it.allocate_tensors()
    i = it.get_input_details()[0]
    o = it.get_output_details()[emb["output"]["index"]]
    mean, std = np.array(inp["mean"], np.float32), np.array(inp["std"], np.float32)

    def run(paths):
        vs = []
        for p in paths:
            with Image.open(p) as im:
                im = ImageOps.exif_transpose(im).convert("RGB").resize((inp["width"], inp["height"]), Image.BILINEAR)
            x = ((np.asarray(im, np.float32) / 255.0 - mean) / std)[None].astype(np.float32)
            it.set_tensor(i["index"], x)
            it.invoke()
            v = it.get_tensor(o["index"]).reshape(-1).astype(np.float32)
            vs.append(v / max(float(np.linalg.norm(v)), 1e-6))
        return np.stack(vs)
    pr = pd.read_csv(os.path.join(a.off, "products.csv"), dtype=str).fillna("")
    img = os.path.join(a.off, "img")
    qmask = (pr["query"] != "").to_numpy()
    meals = pd.read_csv(a.meals).sample(a.n_meals, random_state=5)
    np.savez(a.out, g=run([os.path.join(img, f) for f in pr.gallery]),
             q=run([os.path.join(img, f) for f in pr["query"][qmask]]),
             m=run([fast_path(p) for p in meals.path]), q_idx=np.flatnonzero(qmask))
    print("written", a.out)


if __name__ == "__main__":
    main()
