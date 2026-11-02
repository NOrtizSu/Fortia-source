# SPDX-License-Identifier: AGPL-3.0-or-later
"""Final touches on a trained model: remove classes after the fact, shift the `_not_food` logit.

- --drop: when a class turns out to be wrongly labelled data, slice its row out of the classifier layer (the
  other logits are unchanged, so it equals never offering that class).
- --not-food-bias: add a constant to the `_not_food` logit (negative = fewer food photos called "not food").

Writes <work>/runs/<run_out>/best.pt, <work>/<dataset_out>/{val,test}.csv without the dropped classes' photos
(labels re-numbered, plus a copy of attribution.csv) and the class list without the dropped rows.

usage: python drop_classes.py --work D:/FoodML --run run2b --run-out final --dataset dataset \
           --dataset-out dataset_final --classes classes.csv --classes-out classes.csv --drop dolma \
           --not-food-bias -1.0
"""
import argparse
import csv
import os
import shutil

import pandas as pd
import torch


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--work", default="D:/FoodML")
    ap.add_argument("--run", required=True)
    ap.add_argument("--ckpt", default="best.pt")
    ap.add_argument("--run-out", required=True)
    ap.add_argument("--dataset", default="dataset")
    ap.add_argument("--dataset-out", required=True)
    ap.add_argument("--classes", default="classes.csv")
    ap.add_argument("--classes-out", default="classes.csv")
    ap.add_argument("--drop", default="", help="comma-separated class ids")
    ap.add_argument("--not-food-bias", type=float, default=0.0, help="added to the `_not_food` logit")
    a = ap.parse_args()
    drop = set(filter(None, a.drop.split(",")))
    with open(a.classes, encoding="utf-8") as f:
        rows = list(csv.DictReader(f))
    ids = [r["id"] for r in rows]
    assert drop <= set(ids), drop - set(ids)
    keep = [i for i, c in enumerate(ids) if c not in drop]
    remap = {old: new for new, old in enumerate(keep)}

    ck = torch.load(os.path.join(a.work, "runs", a.run, a.ckpt), map_location="cpu")
    sd = ck["state_dict"]
    assert sd["classifier.weight"].shape[0] == len(ids)
    sd["classifier.weight"] = sd["classifier.weight"][keep].clone()
    sd["classifier.bias"] = sd["classifier.bias"][keep].clone()
    if a.not_food_bias:
        # shift the `_not_food` logit: trades not-food recall for fewer food photos called "not food"
        sd["classifier.bias"][remap[ids.index("_not_food")]] += a.not_food_bias
    out_run = os.path.join(a.work, "runs", a.run_out)
    os.makedirs(out_run, exist_ok=True)
    torch.save(dict(ck, state_dict=sd, n_classes=len(keep), dropped=sorted(drop), source=f"{a.run}/{a.ckpt}"),
               os.path.join(out_run, "best.pt"))

    src, dst = os.path.join(a.work, a.dataset), os.path.join(a.work, a.dataset_out)
    os.makedirs(dst, exist_ok=True)
    for split in ("val", "test"):
        df = pd.read_csv(os.path.join(src, f"{split}.csv"))
        df = df[df.label.isin(remap)].copy()
        df["label"] = df.label.map(remap)
        df.to_csv(os.path.join(dst, f"{split}.csv"), index=False)
    shutil.copyfile(os.path.join(src, "attribution.csv"), os.path.join(dst, "attribution.csv"))

    with open(a.classes_out, "w", encoding="utf-8", newline="\n") as f:
        w = csv.DictWriter(f, fieldnames=list(rows[0].keys()), lineterminator="\n")
        w.writeheader()
        w.writerows(r for r in rows if r["id"] not in drop)
    print(f"{len(ids)} -> {len(keep)} classes; dropped {sorted(drop)}")


if __name__ == "__main__":
    main()
