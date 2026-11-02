# SPDX-License-Identifier: AGPL-3.0-or-later
"""Assign Open Images photos to our classes and pick the `_not_food` photos.

Resolves the Open Images label names of classes_config.py to MIDs, then gives every photo with
a positive label of our classes exactly one class (highest rank wins; a tie at the top makes the photo
ambiguous and it is skipped; "food" classes need a generic food label too). Also picks the `_not_food`
photos: verified *negative* for Food (and not positive for any food label), a fifth of them with tableware,
a table, a kitchen or packaging in view (empty plates and the like: the hard cases). Writes
<work>/oi/assign.parquet and <work>/oi/mids.json. The final class list comes from build_dataset.py.

usage: python select_classes.py --work D:/FoodML --oi
"""
import argparse
import json
import os
import sys

import pandas as pd

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from classes_config import CLASSES, NOT_FOOD  # noqa: E402
from oi_label_stats import GENERIC_FOOD  # noqa: E402

NOT_FOOD_TARGET = 4000
NOT_FOOD_NEGATIVE_OF = ["/m/02wbm"]  # verified "no Food" in the photo
# Tableware, Plate, Bowl, Dishware, Serveware, Platter, Tray, Cup, Mug, Coffee cup, Saucer, Drinkware, Wine glass,
# Fork, Spoon, Knife, Kitchen utensil, Napkin, Tablecloth, Table, Kitchen & dining room table, Countertop,
# Kitchen, Kitchen appliance, Refrigerator, Restaurant, Packaging and labeling
TABLE_LIKE = {"/m/04brg2", "/m/050gv4", "/m/04kkgm", "/m/0h8nvk9", "/m/0h8n22v", "/m/099ssp", "/m/01nn4f",
              "/m/07ptj3n", "/m/02jvh9", "/m/02p5f1q", "/m/03q5c7", "/m/050h26", "/m/09tvcd", "/m/0dt3t",
              "/m/0cmx8", "/m/04ctx", "/m/03hlz0c", "/m/026lrs", "/m/05677z", "/m/04bcr3", "/m/0h8n5zk",
              "/m/0b3fp9", "/m/0d4wf", "/m/0h99cwc", "/m/040b_t", "/m/06l8d", "/m/02qz80"}


def resolve(names_csv):
    desc = pd.read_csv(names_csv)
    by_name = {}
    for mid, name in zip(desc.LabelName, desc.DisplayName):
        by_name.setdefault(str(name).lower(), []).append(mid)
    return by_name


def step_oi(work):
    meta = os.path.join(work, "data", "oi_meta")
    out = os.path.join(work, "oi")
    by_name = resolve(os.path.join(meta, "oidv7-class-descriptions.csv"))
    stats = pd.read_csv(os.path.join(out, "label_stats.csv")).set_index("mid")
    mid2class, missing = {}, []
    for c in CLASSES:
        for n in c["open_images"]:
            mids = by_name.get(n.lower())
            if not mids:
                missing.append(f"{c['id']}: {n}")
                continue
            mid = max(mids, key=lambda m: stats.n.get(m, 0))
            if mid in mid2class and mid2class[mid] != c["id"]:
                print(f"label {n} in {mid2class[mid]} and {c['id']}", file=sys.stderr)
            mid2class[mid] = c["id"]
    if missing:
        print("missing Open Images labels:", "; ".join(missing))
    rank = {c["id"]: c["rank"] for c in CLASSES}
    need_food = {c["id"] for c in CLASSES if c["need_food"]}

    pos = pd.read_parquet(os.path.join(out, "positives.parquet"))
    pos["LabelName"] = pos.LabelName.astype(str)
    food_imgs = set(pos.loc[pos.LabelName.isin(GENERIC_FOOD.keys()), "ImageID"])
    ours = pos[pos.LabelName.isin(mid2class.keys())].copy()
    ours["cls"] = ours.LabelName.map(mid2class)
    ours = ours.drop_duplicates(["ImageID", "cls"])
    ours = ours[~(ours.cls.isin(need_food) & ~ours.ImageID.isin(food_imgs))]
    ours["rank"] = ours.cls.map(rank)
    top = ours.groupby("ImageID")["rank"].transform("max")
    ours = ours[ours["rank"] == top]
    n_top = ours.groupby("ImageID")["cls"].transform("size")
    assigned = ours[n_top == 1][["ImageID", "Subset", "cls"]]
    print(f"{ours.ImageID.nunique()} photos with our labels, {len(assigned)} unambiguous")

    # _not_food: photos verified to have no food, and no positive food label at all
    any_food = food_imgs | set(pos.loc[pos.LabelName.isin(mid2class.keys()), "ImageID"])
    neg = []
    for subset, fn in (("train", "oidv7-train-annotations-human-imagelabels.csv"),
                       ("validation", "oidv7-val-annotations-human-imagelabels.csv"),
                       ("test", "oidv7-test-annotations-human-imagelabels.csv")):
        for chunk in pd.read_csv(os.path.join(meta, fn), chunksize=5_000_000,
                                 usecols=["ImageID", "LabelName", "Confidence"]):
            ch = chunk[(chunk.Confidence == 0) & chunk.LabelName.isin(NOT_FOOD_NEGATIVE_OF)]
            neg.append(pd.DataFrame({"ImageID": ch.ImageID, "Subset": subset}))
    neg = pd.concat(neg).drop_duplicates("ImageID")
    neg = neg[~neg.ImageID.isin(any_food)]
    print(f"{len(neg)} verified no-food photos")
    table = set(pos.loc[pos.LabelName.isin(TABLE_LIKE), "ImageID"])
    hard = neg[neg.ImageID.isin(table)]
    n_hard = min(len(hard), NOT_FOOD_TARGET * 2 // 5)
    neg = pd.concat([hard.sample(n_hard, random_state=7),
                     neg[~neg.ImageID.isin(table)].sample(NOT_FOOD_TARGET * 2 - n_hard, random_state=7)])
    print(f"{len(hard)} of them with tableware/table/kitchen/packaging; {n_hard} taken")
    neg["cls"] = NOT_FOOD
    assigned = pd.concat([assigned, neg], ignore_index=True)
    assigned.to_parquet(os.path.join(out, "assign.parquet"), index=False)
    cnt = assigned.groupby("cls").size()
    with open(os.path.join(out, "mids.json"), "w", encoding="utf-8") as f:
        json.dump({c: sorted(m for m, k in mid2class.items() if k == c) for c in rank}, f, indent=0)
    for c in CLASSES:
        print(f"{c['id']}\t{cnt.get(c['id'], 0)}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--work", default="D:/FoodML")
    ap.add_argument("--oi", action="store_true", help="(the only step; kept for the README commands)")
    a = ap.parse_args()
    step_oi(a.work)


if __name__ == "__main__":
    main()
