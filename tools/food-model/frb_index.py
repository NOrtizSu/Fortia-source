# SPDX-License-Identifier: AGPL-3.0-or-later
"""Index the Food Recognition Benchmark 2022 (MyFoodRepo v2.0) annotations: one row per food item.

Source: AIcrowd Food Recognition Benchmark 2022, data from the MyFoodRepo app (Mohanty et al., "The Food Recognition
Benchmark: Using Deep Learning to Recognize Food in Images", Front. Nutr. 2022) — CC BY 4.0. AIcrowd needs an
account, so the public Dataset Ninja copy (Supervisely format, same images and polygons) is used:
https://datasetninja.com/food-recognition (tar extracted to <work>/dl/frb/x).

Output: <work>/frb/items.parquet (split, image, path, width, height, cls, x0, y0, x1, y1 in pixels, poly_area)
        <work>/frb/class_counts.csv

usage: python -I frb_index.py --work D:/FoodML
"""
import argparse
import glob
import json
import os
from concurrent.futures import ThreadPoolExecutor

import numpy as np
import pandas as pd


def poly_area(pts):
    if len(pts) < 3:
        return 0.0
    x, y = pts[:, 0], pts[:, 1]
    return float(0.5 * abs(np.dot(x, np.roll(y, 1)) - np.dot(y, np.roll(x, 1))))


def read(args):
    split, ann_path, img_dir = args
    with open(ann_path, encoding="utf-8") as f:
        a = json.load(f)
    name = os.path.basename(ann_path)[:-5]
    w, h = a["size"]["width"], a["size"]["height"]
    rows = []
    for o in a.get("objects", []):
        ext = o.get("points", {}).get("exterior") or []
        if len(ext) < 3:
            continue
        pts = np.asarray(ext, dtype=np.float64)
        x0, y0 = pts.min(0)
        x1, y1 = pts.max(0)
        rows.append({"split": split, "image": name, "path": os.path.join(img_dir, name), "width": w, "height": h,
                     "cls": o["classTitle"], "x0": max(0.0, x0), "y0": max(0.0, y0), "x1": min(float(w), x1),
                     "y1": min(float(h), y1), "poly_area": poly_area(pts)})
    if not rows:
        rows.append({"split": split, "image": name, "path": os.path.join(img_dir, name), "width": w, "height": h,
                     "cls": "", "x0": 0.0, "y0": 0.0, "x1": 0.0, "y1": 0.0, "poly_area": 0.0})
    return rows


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--work", default="D:/FoodML")
    a = ap.parse_args()
    root = os.path.join(a.work, "dl", "frb", "x")
    jobs = []
    for split in ("training", "validation", "test"):
        for p in glob.glob(os.path.join(root, split, "ann", "*.json")):
            jobs.append((split, p, os.path.join(root, split, "img")))
    with ThreadPoolExecutor(12) as pool:
        rows = [r for rs in pool.map(read, jobs, chunksize=64) for r in rs]
    df = pd.DataFrame(rows)
    out = os.path.join(a.work, "frb")
    os.makedirs(out, exist_ok=True)
    df.to_parquet(os.path.join(out, "items.parquet"), index=False)
    lab = df[df.cls != ""]
    cc = lab.groupby("cls").agg(items=("image", "size"), images=("image", "nunique")).sort_values("items",
                                                                                                    ascending=False)
    cc.to_csv(os.path.join(out, "class_counts.csv"))
    per_img = lab.groupby(["split", "image"]).size()
    print(len(jobs), "images;", len(lab), "items;", lab.cls.nunique(), "classes")
    print(df.groupby("split").image.nunique().to_dict(), "labelled items per split",
          lab.groupby("split").size().to_dict())
    print("items per image:", per_img.value_counts().sort_index().head(12).to_dict())


if __name__ == "__main__":
    main()
