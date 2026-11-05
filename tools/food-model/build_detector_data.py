# SPDX-License-Identifier: AGPL-3.0-or-later
"""Training data for the class-agnostic food-item detector (YOLO format), from MyFoodRepo (FRB 2022) and
Open Images boxes. One class: "food item" (see food_items.py for what counts as an item).

Steps:
  select    -> <work>/det/oi_items.parquet: Open Images photos with 1..12 food items after the leaf/container
               rules (no drawings), their item boxes and whether the photo is already on disk.
  download  -> fetches the missing Open Images photos (bucket, short side 512) to <work>/images/oi_box/.
  build     -> <work>/det/{images,labels}/{train,val,test}/: every photo **stretched to --size x --size** (the app
               stretches the whole photo for the detector, detector_contract.md) + YOLO labels (class 0, cx cy w h
               normalised); <work>/det/data.yaml. Splits: FRB validation = test; 1 000 FRB training photos = val;
               Open Images validation/test subsets = val/test; the rest train. --not-food N adds N `_not_food`
               training photos of the classifier (no boxes) as background.

usage: python -I build_detector_data.py select|download|build --work D:/FoodML
"""
import argparse
import concurrent.futures as cf
import io
import os
import sys
import threading

import numpy as np
import pandas as pd
from PIL import Image, ImageOps

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from food_items import TOPPINGS, fast_path, oi_items  # noqa: E402

BUCKET = "https://open-images-dataset.s3.amazonaws.com/{subset}/{id}.jpg"
_local = threading.local()


def _session():
    # one session per thread: a new connection costs ~10 s here (TLS setup), a reused one ~0.2 s
    if not hasattr(_local, "s"):
        import requests
        _local.s = requests.Session()
        _local.s.headers["User-Agent"] = "Fortia-food-model/2.0 (enjoyyourtime.online@gmail.com)"
    return _local.s


def step_select(a):
    df = pd.read_parquet(os.path.join(a.work, "oi", "boxes_food.parquet"))
    bad = set(df.loc[df.IsDepiction == 1, "ImageID"])
    df = df[~df.ImageID.isin(bad)]
    rows = []
    for img, g in df.groupby("ImageID", sort=False):
        boxes = g[["XMin", "YMin", "XMax", "YMax"]].to_numpy()
        keep = oi_items(boxes, g.label.tolist())
        if not 1 <= len(keep) <= 12:
            continue
        sub = g.iloc[keep]
        # tiny items in a big scene (markets, buffets): not a meal photo
        area = ((sub.XMax - sub.XMin) * (sub.YMax - sub.YMin)).to_numpy()
        if area.max() < 0.02:
            continue
        for r in sub.itertuples(index=False):
            rows.append((img, r.Subset, r.label, r.XMin, r.YMin, r.XMax, r.YMax, r.IsGroupOf))
    items = pd.DataFrame(rows, columns=["ImageID", "Subset", "label", "x0", "y0", "x1", "y1", "group"])
    have = {os.path.splitext(f)[0] for f in os.listdir(os.path.join(a.work, "images", "oi"))}
    box_dir = os.path.join(a.work, "images", "oi_box")
    os.makedirs(box_dir, exist_ok=True)
    have_box = {os.path.splitext(f)[0] for f in os.listdir(box_dir)}
    items["on_disk"] = items.ImageID.isin(have | have_box)
    os.makedirs(os.path.join(a.work, "det"), exist_ok=True)
    items.to_parquet(os.path.join(a.work, "det", "oi_items.parquet"), index=False)
    per = items.groupby("ImageID").agg(n=("label", "size"), sub=("Subset", "first"), disk=("on_disk", "first"))
    print(len(items), "items on", len(per), "photos;", int(per.disk.sum()), "already on disk")
    print(per.groupby("sub").size().to_dict(), per.n.value_counts().sort_index().to_dict())
    print(items.label.value_counts().head(30).to_dict())


def _fetch(row, out_dir, short):
    path = os.path.join(out_dir, row.ImageID + ".jpg")
    if os.path.exists(path):
        return True
    for _ in range(3):
        try:
            r = _session().get(BUCKET.format(subset=row.Subset, id=row.ImageID), timeout=30)
            if r.status_code != 200 or len(r.content) < 4000:
                return False
            im = ImageOps.exif_transpose(Image.open(io.BytesIO(r.content))).convert("RGB")
            w, h = im.size
            s = short / min(w, h)
            if s < 1:
                im = im.resize((round(w * s), round(h * s)), Image.BICUBIC)
            im.save(path + ".tmp", "JPEG", quality=90)
            os.replace(path + ".tmp", path)
            return True
        except Exception:
            continue
    return False


def step_download(a):
    items = pd.read_parquet(os.path.join(a.work, "det", "oi_items.parquet"))
    todo = items[~items.on_disk].drop_duplicates("ImageID")
    if a.max_download:
        todo = todo.sample(min(len(todo), a.max_download), random_state=5)
    out = os.path.join(a.work, "images", "oi_box")
    os.makedirs(out, exist_ok=True)
    print(len(todo), "to download", flush=True)
    ok = 0
    with cf.ThreadPoolExecutor(a.threads) as pool:
        for i, r in enumerate(pool.map(lambda row: _fetch(row, out, 512), todo.itertuples(index=False))):
            ok += bool(r)
            if i % 2000 == 0:
                print(i, ok, flush=True)
    print("downloaded", ok, "of", len(todo))


def _write(job):
    src, dst, labels, size = job
    if not os.path.exists(dst):
        try:
            with Image.open(fast_path(src)) as im:
                im = ImageOps.exif_transpose(im).convert("RGB").resize((size, size), Image.BILINEAR)
                im.save(dst, "JPEG", quality=90)
        except Exception:
            return False
    with open(os.path.splitext(dst.replace(os.sep + "images" + os.sep, os.sep + "labels" + os.sep))[0] + ".txt",
              "w", newline="\n") as f:
        for x0, y0, x1, y1 in labels:
            f.write(f"0 {(x0 + x1) / 2:.6f} {(y0 + y1) / 2:.6f} {x1 - x0:.6f} {y1 - y0:.6f}\n")
    return True


def step_build(a):
    root = a.out_root or os.path.join(a.work, "det")
    for s in ("train", "val", "test"):
        os.makedirs(os.path.join(root, "images", s), exist_ok=True)
        os.makedirs(os.path.join(root, "labels", s), exist_ok=True)
    jobs = []
    # MyFoodRepo
    frb = pd.read_parquet(os.path.join(a.work, "frb", "items.parquet"))
    frb = frb[frb.split.isin(["training", "validation"])]
    imgs = frb.drop_duplicates("image")[["split", "image"]]
    rng = np.random.default_rng(3)
    train_imgs = imgs[imgs.split == "training"].image.to_numpy()
    val_set = set(rng.choice(train_imgs, 1000, replace=False))
    for img, g in frb.groupby("image", sort=False):
        split = "test" if g.split.iloc[0] == "validation" else "val" if img in val_set else "train"
        g = g[(g.cls != "") & ~g.cls.isin(TOPPINGS)]
        w, h = g.width.iloc[0] if len(g) else 1, g.height.iloc[0] if len(g) else 1
        labels = [(r.x0 / w, r.y0 / h, r.x1 / w, r.y1 / h) for r in g.itertuples(index=False)
                  if (r.x1 - r.x0) > 2 and (r.y1 - r.y0) > 2]
        src = frb.loc[frb.image == img, "path"].iloc[0] if not len(g) else g.path.iloc[0]
        jobs.append((src, os.path.join(root, "images", split, "frb_" + os.path.splitext(img)[0] + ".jpg"), labels,
                     a.size))
    # Open Images (one directory listing per folder: stat-ing 100 k files on a busy hard disk is slow)
    items = pd.read_parquet(os.path.join(a.work, "det", "oi_items.parquet"))
    on_disk = {}
    for sub in ("oi", "oi_box"):  # oi_box (512 px) wins over oi (320 px)
        d = os.path.join(a.work, "images", sub)
        for f in os.listdir(fast_path(d) if a.only_fast else d):
            on_disk[os.path.splitext(f)[0]] = os.path.join(d, f)
    for img, g in items.groupby("ImageID", sort=False):
        src = on_disk.get(img)
        if src is None:
            continue
        sub = g.Subset.iloc[0]
        split = "train" if sub == "train" else "val" if sub == "validation" else "test"
        labels = list(zip(g.x0, g.y0, g.x1, g.y1))
        jobs.append((src, os.path.join(root, "images", split, "oi_" + img + ".jpg"), labels, a.size))
    # background photos (no food) from the classifier's `_not_food` training photos
    if a.not_food:
        tr = pd.read_csv(os.path.join(a.work, "dataset", "train.csv"))
        nf = tr[tr["class"] == "_not_food"].sample(frac=1.0, random_state=4).head(a.not_food)
        for p in nf.path:
            jobs.append((p, os.path.join(root, "images", "train", "nf_" + os.path.basename(p)), [], a.size))
    if a.splits:  # e.g. only val,test (to evaluate an exported detector again)
        keep = {os.sep + "images" + os.sep + s + os.sep for s in a.splits.split(",")}
        jobs = [j for j in jobs if any(k in os.path.normpath(j[1]) for k in keep)]
    print(len(jobs), "photos", flush=True)
    ok = 0
    with cf.ThreadPoolExecutor(12) as pool:
        for k, r in enumerate(pool.map(_write, jobs, chunksize=64)):
            ok += bool(r)
            if k % 10000 == 0:
                print(k, len(jobs), flush=True)
    print("written", ok)
    with open(os.path.join(root, "data.yaml"), "w", newline="\n") as f:
        f.write(f"path: {root}\ntrain: images/train\nval: images/val\ntest: images/test\nnames:\n  0: food\n")
    for s in ("train", "val", "test"):
        n = len(os.listdir(os.path.join(root, "images", s)))
        print(s, n)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("step", choices=("select", "download", "build"))
    ap.add_argument("--work", default="D:/FoodML")
    ap.add_argument("--size", type=int, default=384)
    ap.add_argument("--threads", type=int, default=32)
    ap.add_argument("--max-download", type=int, default=0)
    ap.add_argument("--not-food", type=int, default=3000)
    ap.add_argument("--out-root", default="", help="where to write the YOLO dataset (an SSD: read at random)")
    ap.add_argument("--splits", default="", help="build only these splits (comma-separated), e.g. val,test")
    ap.add_argument("--only-fast", action="store_true", help="only Open Images photos already on the mirror")
    a = ap.parse_args()
    {"select": step_select, "download": step_download, "build": step_build}[a.step](a)


if __name__ == "__main__":
    main()
