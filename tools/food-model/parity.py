# SPDX-License-Identifier: AGPL-3.0-or-later
"""Check the TFLite variants against the PyTorch model on the test set, with the app's preprocessing.

Preprocessing here is written from food_model.json's description only (PIL + numpy, no torchvision), the way
the app does it: RGB, resize the short side to resize_short_side (bilinear), centre crop width x height, /255,
(x - mean) / std, NHWC. The logits are the output with as many values as labels (v2 models also have a
`log_grams` output, compared with the PyTorch head when <run>/grams/head.pt exists).
Reports top-1/top-5 of every variant, its agreement with the PyTorch top-1 (from evaluate.py's
test_logits.npy) and the CPU latency of one inference (this PC, 1 and 4 threads), and writes parity.json.

usage (export venv): python parity.py --work D:/FoodML --run run1 [--limit 0]
"""
import argparse
import json
import os
import time

import sys

import numpy as np
import pandas as pd
from ai_edge_litert.interpreter import Interpreter
from PIL import Image

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from food_items import fast_path  # noqa: E402

VARIANTS = ("float32", "float16", "dynamic_range_quant", "integer_quant", "integer_quant_with_int16_act",
            "selective_int8")


def preprocess(path, spec):
    inp = spec["input"]
    im = (path if isinstance(path, Image.Image) else Image.open(fast_path(path))).convert("RGB")
    w, h = im.size
    short = inp["resize_short_side"]
    # short side -> 256, long side -> floor(long * 256 / short) (as torchvision's Resize)
    size = (short, int(short * h / w)) if w <= h else (int(short * w / h), short)
    im = im.resize(size, Image.BILINEAR)
    w, h = im.size
    cw, ch = inp["width"], inp["height"]
    left, top = int(round((w - cw) / 2.0)), int(round((h - ch) / 2.0))
    im = im.crop((left, top, left + cw, top + ch))
    x = np.asarray(im, dtype=np.float32) / 255.0
    x = (x - np.array(inp["mean"], np.float32)) / np.array(inp["std"], np.float32)
    return x[None]


class Lazy:
    """The test pictures preprocessed on demand (keeping 16 k float tensors of 320 x 320 needs ~5 GB)."""

    def __init__(self, paths, spec):
        self.paths, self.spec = list(paths), spec

    def __len__(self):
        return len(self.paths)

    def __getitem__(self, i):
        return preprocess(self.paths[i], self.spec)

    def __iter__(self):
        return (self[i] for i in range(len(self)))


def run_variant(path, xs, n_labels, threads=4):
    """-> (logits [n, n_labels], log_grams [n] or None)"""
    it = Interpreter(model_path=path, num_threads=threads)
    it.allocate_tensors()
    i = it.get_input_details()[0]
    outs = it.get_output_details()
    o = next(d for d in outs if int(np.prod(d["shape"])) == n_labels)
    g = next((d for d in outs if int(np.prod(d["shape"])) == 1), None)

    def read(d):
        y = it.get_tensor(d["index"]).reshape(-1).astype(np.float32)
        if d["dtype"] == np.int8:
            sc, zp = d["quantization"]
            y = (y - zp) * sc
        return y
    out, grams = [], []
    for x in xs:
        if i["dtype"] == np.int8:
            sc, zp = i["quantization"]
            x = np.clip(np.round(x / sc + zp), -128, 127).astype(np.int8)
        it.set_tensor(i["index"], x)
        it.invoke()
        out.append(read(o))
        if g is not None:
            grams.append(read(g)[0])
    return np.stack(out), (np.array(grams) if g is not None else None)


def latency(path, x, threads, n=30):
    it = Interpreter(model_path=path, num_threads=threads)
    it.allocate_tensors()
    i = it.get_input_details()[0]
    it.set_tensor(i["index"], x)
    for _ in range(5):
        it.invoke()
    t = time.perf_counter()
    for _ in range(n):
        it.invoke()
    return round((time.perf_counter() - t) / n * 1000, 1)


def acc(logits, labels):
    top5 = np.argsort(-logits, 1)[:, :5]
    return round(float((top5[:, 0] == labels).mean()), 4), round(float((top5 == labels[:, None]).any(1).mean()), 4)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--work", default="D:/FoodML")
    ap.add_argument("--dataset", default="dataset", help="under --work")
    ap.add_argument("--run", default="run1")
    ap.add_argument("--spec", default="", help="food_model.json (default: <run>/export/food_model.json)")
    ap.add_argument("--limit", type=int, default=0)
    ap.add_argument("--runs", default="", help="runs folder (default <work>/runs)")
    ap.add_argument("--data", default="", help="dataset folder (default <work>/<dataset>)")
    ap.add_argument("--test", default="test.csv")
    ap.add_argument("--eval-dir", default="eval", help="where evaluate.py wrote test_logits.npy")
    ap.add_argument("--variants", default="", help="comma-separated subset of " + ",".join(VARIANTS))
    a = ap.parse_args()
    run = os.path.join(a.runs or os.path.join(a.work, "runs"), a.run)
    tfl = os.path.join(run, "export", "tflite")
    spec_path = a.spec or os.path.join(run, "export", "food_model.json")
    with open(spec_path, encoding="utf-8") as f:
        spec = json.load(f)
    test = pd.read_csv(os.path.join(a.data or os.path.join(a.work, a.dataset), a.test))
    ref = np.load(os.path.join(run, a.eval_dir, "test_logits.npy"))
    if a.limit:
        test, ref = test.head(a.limit), ref[:a.limit]
    labels = test.label.to_numpy()
    xs = Lazy(test.path, spec)
    res = {"n": len(xs), "pytorch": dict(zip(("top1", "top5"), acc(ref, labels)))}
    ref_top1 = ref.argmax(1)
    ref_grams = None
    for v in [x for x in VARIANTS if not a.variants or x in a.variants.split(",")]:
        p = os.path.join(tfl, f"model_{v}.tflite")
        if not os.path.exists(p):
            continue
        lg, gr = run_variant(p, xs, len(spec["labels"]))
        t1, t5 = acc(lg, labels)
        res[v] = {"top1": t1, "top5": t5, "agree_top1_with_pytorch": round(float((lg.argmax(1) == ref_top1).mean()), 4),
                  "max_abs_logit_diff": round(float(np.abs(lg - ref).max()), 3),
                  "bytes": os.path.getsize(p), "ms_1_thread": latency(p, xs[0], 1),
                  "ms_4_threads": latency(p, xs[0], 4)}
        if gr is not None:
            # the float32 TFLite matches PyTorch (export.py checks the ONNX graph): the reference for the others
            res[v]["log_grams_mean"] = round(float(gr.mean()), 4)
            if v == "float32":
                ref_grams = gr
            elif ref_grams is not None:
                d = np.abs(np.exp(gr) - np.exp(ref_grams)) / np.exp(ref_grams)
                res[v]["grams_rel_diff_median"] = round(float(np.median(d)), 4)
                res[v]["grams_rel_diff_p95"] = round(float(np.quantile(d, 0.95)), 4)
        print(v, res[v], flush=True)
    with open(os.path.join(run, a.eval_dir, "parity.json"), "w") as f:
        json.dump(res, f, indent=1)
    print(json.dumps(res, indent=1))


if __name__ == "__main__":
    main()
