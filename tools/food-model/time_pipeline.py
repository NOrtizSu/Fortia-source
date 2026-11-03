# SPDX-License-Identifier: AGPL-3.0-or-later
"""CPU timing of the whole on-device pipeline with the bundled assets (LiteRT, XNNPACK): detector on the photo,
then the classifier (with its grams output) on each item crop, as the app does (detector_contract.md).

Reports the model time of one detector run and one classifier run (median of --runs, 1 and 4 threads) and the
end-to-end time for a photo with --items items (Python preprocessing with PIL included, so it is an upper
bound of the app's own image work on the same CPU).

usage (export venv): python time_pipeline.py --assets ../../app/src/main/assets/food --photo <jpg> [--items 6]
"""
import argparse
import json
import os
import sys
import time

import numpy as np
from ai_edge_litert.interpreter import Interpreter
from PIL import Image

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from food_items import square_crop  # noqa: E402
from parity import preprocess  # noqa: E402


def median_ms(fn, n):
    ts = []
    for _ in range(n):
        t = time.perf_counter()
        fn()
        ts.append((time.perf_counter() - t) * 1000)
    return round(float(np.median(ts)), 1)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--assets", required=True)
    ap.add_argument("--photo", required=True)
    ap.add_argument("--items", type=int, default=6)
    ap.add_argument("--runs", type=int, default=20)
    a = ap.parse_args()
    spec = json.load(open(os.path.join(a.assets, "food_model.json"), encoding="utf-8"))
    dspec = json.load(open(os.path.join(a.assets, "food_detector.json"), encoding="utf-8"))
    res = {}
    for threads in (1, 4):
        det = Interpreter(model_path=os.path.join(a.assets, "food_detector.tflite"), num_threads=threads)
        det.allocate_tensors()
        cls = Interpreter(model_path=os.path.join(a.assets, "food_model.tflite"), num_threads=threads)
        cls.allocate_tensors()
        S = dspec["input"]["width"]
        dx = np.random.rand(1, S, S, 3).astype(np.float32)
        det.set_tensor(det.get_input_details()[0]["index"], dx)
        det.invoke()
        cx = preprocess(a.photo, spec)
        cls.set_tensor(cls.get_input_details()[0]["index"], cx)
        cls.invoke()
        res[f"detector_ms_{threads}t"] = median_ms(det.invoke, a.runs)
        res[f"classifier_ms_{threads}t"] = median_ms(cls.invoke, a.runs)

        def pipeline():
            im = Image.open(a.photo).convert("RGB")
            W, H = im.size
            x = np.asarray(im.resize((S, S), Image.BILINEAR), np.float32)[None] / 255.0
            det.set_tensor(det.get_input_details()[0]["index"], x)
            det.invoke()
            outs = {len(o["shape"]): o["index"] for o in det.get_output_details()}
            n = int(det.get_tensor(outs[1])[0])
            boxes = list(det.get_tensor(outs[3])[0][:n])
            # time a fixed number of items: the found boxes, padded with the whole photo
            boxes = (boxes + [np.array([0.0, 0.0, 1.0, 1.0])] * a.items)[:a.items]
            for b in boxes:
                left, top, side = square_crop((b[1] * W, b[0] * H, b[3] * W, b[2] * H), W, H, 0.1)
                crop = im.crop((left, top, left + side, top + side))
                cls.set_tensor(cls.get_input_details()[0]["index"], preprocess(crop, spec))
                cls.invoke()
        pipeline()
        res[f"pipeline_{a.items}_items_ms_{threads}t"] = median_ms(pipeline, max(3, a.runs // 4))
    with Image.open(a.photo) as im:
        res["photo"] = f"{os.path.basename(a.photo)} {im.size[0]}x{im.size[1]}"
    print(json.dumps(res, indent=1))


if __name__ == "__main__":
    main()
