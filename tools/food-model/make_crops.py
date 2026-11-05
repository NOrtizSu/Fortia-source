# SPDX-License-Identifier: AGPL-3.0-or-later
"""(Re)make the item crops of a dataset from its csv files (build_dataset_v2.py's crops, same rule and names).

Every `kind == crop` row carries its source in `source_id`: MyFoodRepo `split/image#x0,y0,x1,y1` (pixels) or
Open Images `ImageID#x0,y0,x1,y1` (normalised, the exact box from <work>/det/oi_items.parquet). The crop is the
app's square crop of that box (margin 0.10), short side at most 320 px, written to the row's `path`. Existing
files are kept. Sources are read in path order (the work disk is a hard disk).

usage: python make_crops.py --work D:/FoodML --dataset D:/FoodML/dataset_v2 [--threads 6]
"""
import argparse
import os
import sys
from concurrent.futures import ThreadPoolExecutor

import pandas as pd

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from build_dataset_v2 import make_crop  # noqa: E402


def jobs_of(work, dataset):
    rows = pd.concat([pd.read_csv(os.path.join(dataset, f"{s}.csv")) for s in ("train", "val", "test")])
    rows = rows[rows.kind == "crop"]
    frb = pd.read_parquet(os.path.join(work, "frb", "items.parquet"))
    frb_size = {f"{r.split}/{r.image}": (r.path, r.width, r.height) for r in frb.itertuples(index=False)}
    items = pd.read_parquet(os.path.join(work, "det", "oi_items.parquet"))
    exact = {f"{r.ImageID}#{r.x0:.3f},{r.y0:.3f},{r.x1:.3f},{r.y1:.3f}": (r.x0, r.y0, r.x1, r.y1)
             for r in items.itertuples(index=False)}
    jobs = []
    for r in rows.itertuples(index=False):
        if os.path.exists(r.path):
            continue
        key, box = r.source_id.split("#")
        if r.source == "myfoodrepo":
            src, w, h = frb_size[key]
            x0, y0, x1, y1 = (float(v) for v in box.split(","))
            jobs.append((src, (x0 / w, y0 / h, x1 / w, y1 / h), r.path))
        else:
            src = os.path.join(work, "images", "oi_box", key + ".jpg")
            if not os.path.exists(src):
                src = os.path.join(work, "images", "oi", key + ".jpg")
            jobs.append((src, exact.get(r.source_id) or tuple(float(v) for v in box.split(",")), r.path))
    jobs.sort(key=lambda j: j[0])
    return jobs


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--work", default="D:/FoodML")
    ap.add_argument("--dataset", default="D:/FoodML/dataset_v2")
    ap.add_argument("--threads", type=int, default=6)
    a = ap.parse_args()
    jobs = jobs_of(a.work, a.dataset)
    for d in {os.path.dirname(j[2]) for j in jobs}:
        os.makedirs(d, exist_ok=True)
    print(len(jobs), "crops to make", flush=True)
    ok = 0
    with ThreadPoolExecutor(a.threads) as pool:
        for k, made in enumerate(pool.map(make_crop, jobs, chunksize=32)):
            ok += made
            if k % 5000 == 0:
                print(k, len(jobs), "made", ok, flush=True)
    print("made", ok, "of", len(jobs), flush=True)


if __name__ == "__main__":
    main()
