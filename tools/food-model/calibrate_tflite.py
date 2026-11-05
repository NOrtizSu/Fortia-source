# SPDX-License-Identifier: AGPL-3.0-or-later
"""Calibrate the exported classifier itself (v3: the int8 file differs a little from any float model): its logits
on val and test with the app's preprocessing (parity.py), then
  - the `_not_food` logit shift: non-food pictures caught vs food pictures rejected for a few shifts (val, test);
  - with the chosen `--shift`: the temperature T minimising the negative log-likelihood on val, the expected
    calibration error (15 bins) on test before/after, and min_confidence (1 % of the food test pictures have a best
    food probability under it);
  - top-1/top-5 on every test file given (test.csv, test_v2.csv = the 1.6.0 test pictures, test_v1.csv = 1.5.0's).
Logits are cached as <out>/<file>_logits.npy. The shift goes into the model as a change of the `_not_food` bias
(qat.py --not-food-shift / drop_classes.py), not into the json.

usage (export venv): python calibrate_tflite.py --model <export>/tflite/model_integer_quant.tflite \
          --spec <assets>/food_model.json --data E:/FoodML_ssd/dataset_v3 --out D:/FoodML/runs/v3/cls3/calib [--shift 0]
"""
import argparse
import json
import os
import sys

import numpy as np
import pandas as pd

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from parity import Lazy, run_variant  # noqa: E402


def softmax(z):
    z = z - z.max(1, keepdims=True)
    e = np.exp(z)
    return e / e.sum(1, keepdims=True)


def nll(z, y, t):
    p = softmax(z / t)
    return float(-np.log(np.clip(p[np.arange(len(y)), y], 1e-12, None)).mean())


def ece(p, y, bins=15):
    conf, pred = p.max(1), p.argmax(1)
    e = 0.0
    for lo in np.linspace(0, 1, bins, endpoint=False):
        m = (conf > lo) & (conf <= lo + 1 / bins)
        if m.any():
            e += m.mean() * abs((pred[m] == y[m]).mean() - conf[m].mean())
    return float(e)


def acc(z, y):
    t5 = np.argsort(-z, 1)[:, :5]
    return round(float((t5[:, 0] == y).mean()), 4), round(float((t5 == y[:, None]).any(1).mean()), 4)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", required=True)
    ap.add_argument("--spec", required=True)
    ap.add_argument("--data", required=True)
    ap.add_argument("--tests", default="test.csv,test_v2.csv,test_v1.csv")
    ap.add_argument("--out", required=True)
    ap.add_argument("--shifts", default="0,-0.25,-0.5,-0.75,-1.0")
    ap.add_argument("--shift", type=float, default=0.0, help="the chosen shift (already in the model: 0)")
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)
    spec = json.load(open(a.spec, encoding="utf-8"))
    n = len(spec["labels"])
    nf = spec["labels"].index(spec.get("not_food_label", "_not_food"))

    def logits(split):
        p = os.path.join(a.out, f"{os.path.splitext(split)[0]}_logits.npy")
        df = pd.read_csv(os.path.join(a.data, split))
        if not os.path.exists(p):
            z, _ = run_variant(a.model, Lazy(df.path, spec), n)
            np.save(p, z)
        return np.load(p), df.label.to_numpy()

    zv, yv = logits("val.csv")
    tests = {t: logits(t) for t in a.tests.split(",")}
    zt, yt = tests["test.csv"]
    shifts = {}
    for s in (float(x) for x in a.shifts.split(",")):
        row = {}
        for name, (z, y) in (("val", (zv, yv)), ("test", (zt, yt))):
            zz = z.copy()
            zz[:, nf] += s
            pred = zz.argmax(1)
            isnf = y == nf
            row[name] = {"not_food_caught": round(float((pred[isnf] == nf).mean()), 4),
                         "food_rejected": round(float((pred[~isnf] == nf).mean()), 4), "top1": acc(zz, y)[0]}
        shifts[str(s)] = row
    zv2, zt2 = zv.copy(), zt.copy()
    zv2[:, nf] += a.shift
    zt2[:, nf] += a.shift
    ts = np.arange(0.5, 2.01, 0.02)
    t_best = float(ts[int(np.argmin([nll(zv2, yv, t) for t in ts]))])
    food = yt != nf
    pf = softmax(zt2 / t_best)[food]
    best_food = np.delete(pf, nf, axis=1).max(1)
    res = {"shifts": shifts, "shift": a.shift, "temperature": round(t_best, 2),
           "ece_test_T1": round(ece(softmax(zt2), yt), 4), "ece_test_T": round(ece(softmax(zt2 / t_best), yt), 4),
           "min_confidence_1pct": round(float(np.quantile(best_food, 0.01)), 4),
           "accuracy": {k: acc(z, y) for k, (z, y) in tests.items()} | {"val": acc(zv, yv)}}
    print(json.dumps(res, indent=1), flush=True)
    with open(os.path.join(a.out, "calibration.json"), "w") as f:
        json.dump(res, f, indent=1)


if __name__ == "__main__":
    main()
