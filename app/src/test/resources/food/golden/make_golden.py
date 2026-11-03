# SPDX-License-Identifier: AGPL-3.0-or-later
"""Golden inputs of the food model, made with food-model's own preprocessing (tools/food-model/parity.py).

For each case: a small synthetic image (the formula in pixel(), also in RecognitionGoldenTest), the
tensor parity.py's preprocess() makes of it under the case's spec, stored as the 8-bit crop it
normalizes (RGB rows, each byte as its difference from the same channel of the pixel on its left,
modulo 256, then gzip: a few KB) plus a few of its float values. RecognitionGoldenTest checks that the
app's RecognitionMath.preprocess gives the same tensor.

usage (the food-model venv: Pillow, numpy and parity.py's imports):
  python make_golden.py --parity <repo>/tools/food-model/parity.py --spec <repo>/app/src/main/assets/food/food_model.json
"""
import argparse
import copy
import gzip
import importlib.util
import json
import os
import tempfile

import numpy as np
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))


def pixel(x, y, w):
    """R: a ramp, G: a 7x5 checkerboard, B: a diagonal saw (sharp edges for the filter)."""
    return (x * 255) // (w - 1), 230 if (x // 7 + y // 5) % 2 == 0 else 25, ((x + 2 * y) * 3) % 256


def image(w, h):
    a = np.zeros((h, w, 3), np.uint8)
    for y in range(h):
        for x in range(w):
            a[y, x] = pixel(x, y, w)
    return Image.fromarray(a, "RGB")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--parity", required=True)
    ap.add_argument("--spec", required=True)
    a = ap.parse_args()
    loader = importlib.util.spec_from_file_location("parity", a.parity)
    parity = importlib.util.module_from_spec(loader)
    loader.loader.exec_module(parity)
    with open(a.spec, encoding="utf-8") as f:
        bundled = json.load(f)
    small = copy.deepcopy(bundled)
    # Other numbers (and an odd crop offset: round-half-even) on a small image.
    small["input"].update({"width": 16, "height": 16, "resize_short_side": 18, "mean": [0.485, 0.456, 0.406], "std": [0.229, 0.224, 0.225]})
    cases = [
        ("landscape", 400, 300, bundled),  # v2 (320 / 366): enlarged 300 -> 366, 488 wide, crop at x 84, y 23
        ("portrait", 120, 160, bundled),  # v2: enlarged 120 -> 366, 488 high, crop at x 23, y 84
        ("small", 38, 23, small),  # 23 -> 18, 29 wide, crop at x 6.5 -> 6
    ]
    out = []
    with tempfile.TemporaryDirectory() as tmp:
        for name, w, h, spec in cases:
            path = os.path.join(tmp, name + ".png")
            image(w, h).save(path)
            x = parity.preprocess(path, spec)[0]
            mean = np.array(spec["input"]["mean"], np.float32)
            std = np.array(spec["input"]["std"], np.float32)
            crop = np.rint((x * std + mean) * 255.0).astype(np.uint8)
            # The 8-bit crop gives back exactly the tensor (the same float32 steps as parity.py).
            again = (crop.astype(np.float32) / 255.0 - mean) / std
            assert np.array_equal(again, x), name
            delta = crop.astype(np.int16)
            delta[:, 1:] -= crop[:, :-1].astype(np.int16)
            with open(os.path.join(HERE, name + ".rgb.gz"), "wb") as f:
                # mtime 0: the same bytes every time it is made.
                f.write(gzip.compress((delta % 256).astype(np.uint8).tobytes(), compresslevel=9, mtime=0))
            flat = x.reshape(-1)
            idx = np.linspace(0, flat.size - 1, 12).astype(int)
            out.append({
                "name": name, "width": w, "height": h, "input": spec["input"], "rgb": name + ".rgb.gz",
                "samples": [[int(i), float(flat[i])] for i in idx], "sum": float(flat.astype(np.float64).sum()),
            })
    with open(os.path.join(HERE, "golden.json"), "w", encoding="utf-8", newline="\n") as f:
        json.dump({"source": "tools/food-model/parity.py preprocess()", "cases": out}, f, indent=1)
        f.write("\n")
    for c in out:
        print(c["name"], os.path.getsize(os.path.join(HERE, c["rgb"])), "bytes")


if __name__ == "__main__":
    main()
