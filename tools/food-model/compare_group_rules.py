# SPDX-License-Identifier: AGPL-3.0-or-later
"""Which container rule should the app apply to the detector's boxes (detector_contract.md, group_suppression)?

A box around a pile plus boxes for its pieces counts the food twice. Compared on the detector's val and test
photos with their true boxes, at the shipped score threshold, the app's rules (min side, max items) applied:
  none            no suppression
  drop_container  (a) a box holding ≥ 2 other kept boxes (each ≥ `inside` within it) is dropped, pieces kept
  keep_container  (b) the reverse: the big box is kept, the boxes inside it dropped
  hybrid_<c>      (c) (a) only when the inner boxes cover ≥ c of the big box, else (b)
  drop_container_cover0.5   (a) only when the inner boxes cover ≥ half of the big box, else nothing
Reports precision / recall / F1 of the boxes (IoU ≥ 0.5) and how often the app picks the right flow, by number
of true items, and how many photos the rule changes.

usage: python compare_group_rules.py --data E:/FoodML_ssd/det --weights best.pt --threshold 0.25
"""
import argparse
import json
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import train_detector as TD  # noqa: E402
from food_items import group_rule  # noqa: E402


def hybrid(boxes, cover, inside=0.8):
    """(a) where the pieces cover most of the big box (a plate fully split into items), else (b)."""
    a = set(group_rule(boxes, "drop_container", inside, 2, cover))
    if len(a) < len(boxes):
        return sorted(a)
    return group_rule(boxes, "keep_container", inside, 2, 0.0)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", required=True)
    ap.add_argument("--weights", required=True)
    ap.add_argument("--threshold", type=float, default=0.25)
    ap.add_argument("--inside", type=float, default=0.8)
    ap.add_argument("--out", default="")
    a = ap.parse_args()
    os.environ.setdefault("YOLO_OFFLINE", "1")
    predict = TD.yolo_predictor(a.weights, 320, 0.1)
    rules = {"none": None,
             "drop_container": {"mode": "drop_container", "inside": a.inside, "min_inside": 2},
             "keep_container": {"mode": "keep_container", "inside": a.inside, "min_inside": 2},
             "drop_container_cover0.5": {"mode": "drop_container", "inside": a.inside, "min_inside": 2,
                                         "min_cover": 0.5}}
    res = {}
    for split in ("val", "test"):
        img_dir = os.path.join(a.data, "images", split)
        lab_dir = os.path.join(a.data, "labels", split)
        files = sorted(os.listdir(img_dir))
        preds = {}
        for k in range(0, len(files), 64):
            batch = files[k:k + 64]
            for f, r in zip(batch, predict([os.path.join(img_dir, f) for f in batch])):
                preds[f] = r
        for prefix in ("frb_", "oi_"):
            fs = [f for f in files if f.startswith(prefix)]
            out = {}
            names = list(rules) + ["hybrid_0.5", "hybrid_0.7"]
            for name in names:
                rows, changed = {}, 0
                for f in fs:
                    b, s = preds[f]
                    keep = [bb for bb, ss in zip(b, s)
                            if ss >= a.threshold and min(bb[2] - bb[0], bb[3] - bb[1]) >= 0.06]
                    if name.startswith("hybrid"):
                        idx = hybrid(keep, float(name.split("_")[1]), a.inside)
                    else:
                        idx = group_rule(keep, **rules[name]) if rules[name] else list(range(len(keep)))
                    changed += int(len(idx) != len(keep))
                    keep = [keep[i] for i in idx][:6]
                    g = TD.read_labels(os.path.join(lab_dir, os.path.splitext(f)[0] + ".txt"))
                    n = len(g)
                    key = "0" if n == 0 else "1" if n == 1 else "2-3" if n <= 3 else "4+"
                    tp = TD.match(keep, g)
                    r = rows.setdefault(key, {"photos": 0, "true": 0, "pred": 0, "tp": 0, "flow_ok": 0})
                    r["photos"] += 1
                    r["true"] += n
                    r["pred"] += len(keep)
                    r["tp"] += tp
                    r["flow_ok"] += int((len(keep) >= 2) == (n >= 2))
                tot = {k2: sum(r[k2] for r in rows.values()) for k2 in ("photos", "true", "pred", "tp", "flow_ok")}
                rows["all"] = tot
                for r in rows.values():
                    r["precision"] = round(r["tp"] / max(1, r["pred"]), 4)
                    r["recall"] = round(r["tp"] / max(1, r["true"]), 4)
                    r["f1"] = round(2 * r["tp"] / max(1, r["pred"] + r["true"]), 4)
                    r["flow_acc"] = round(r["flow_ok"] / max(1, r["photos"]), 4)
                out[name] = {"photos_changed": changed, **rows}
                print(split, prefix[:-1], name, "changed", changed,
                      {k2: (v["precision"], v["recall"], v["f1"], v["flow_acc"]) for k2, v in sorted(rows.items())},
                      flush=True)
            res[f"{split}/{prefix[:-1]}"] = out
    if a.out:
        with open(a.out, "w") as f:
            json.dump(res, f, indent=1)


if __name__ == "__main__":
    main()
