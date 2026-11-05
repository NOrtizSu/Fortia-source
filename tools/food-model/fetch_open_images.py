# SPDX-License-Identifier: AGPL-3.0-or-later
"""Download the Open Images photos chosen by select_classes.py --oi, plus their licence metadata.

Photos come from the dataset's official open bucket (https://open-images-dataset.s3.amazonaws.com/<subset>/<id>.jpg,
anonymous), are resized on the fly (short side 320 px, JPEG q90) and only the small copy is kept. At most
--cap photos per class (random, fixed seed). The licence/author/URL of every photo (from the
*-images-with-rotation.csv metadata files; Open Images lists all of them as CC BY 2.0) is written to
<work>/oi/meta.parquet for the attribution manifest.

usage: python fetch_open_images.py --work D:/FoodML [--cap 1000] [--threads 48]
"""
import argparse
import concurrent.futures as cf
import io
import os
import sys
import threading

import pandas as pd
import requests
from PIL import Image, ImageOps

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from classes_config import NOT_FOOD  # noqa: E402

BUCKET = "https://open-images-dataset.s3.amazonaws.com/{subset}/{id}.jpg"
SHORT_SIDE = 320
META = {"train": "oidv6-train-images-with-labels-with-rotation.csv",
        "validation": "validation-images-with-rotation.csv",
        "test": "test-images-with-rotation.csv"}
local = threading.local()


def sess():
    if not hasattr(local, "s"):
        local.s = requests.Session()
        local.s.headers["User-Agent"] = "Fortia-food-model/1.0 (enjoyyourtime.online@gmail.com)"
    return local.s


def _get(url, path):
    for _ in range(3):
        try:
            r = sess().get(url, timeout=30)
            if r.status_code != 200 or "photo_unavailable" in r.url or len(r.content) < 4000:
                return False
            im = ImageOps.exif_transpose(Image.open(io.BytesIO(r.content))).convert("RGB")
            w, h = im.size
            if min(w, h) < 120:
                return False
            s = SHORT_SIDE / min(w, h)
            if s < 1:
                im = im.resize((round(w * s), round(h * s)), Image.BICUBIC)
            im.save(path + ".tmp", "JPEG", quality=90)
            os.replace(path + ".tmp", path)
            return True
        except (requests.RequestException, OSError):
            continue
    return False


def fetch(row, img_dir):
    """The bucket copy first; if the bucket doesn't have it, the dataset's Thumbnail300KURL (the same Flickr
    photo, ~640 px)."""
    path = os.path.join(img_dir, row.ImageID + ".jpg")
    if os.path.exists(path):
        return row.ImageID, "kept"
    if _get(BUCKET.format(subset=row.Subset, id=row.ImageID), path):
        return row.ImageID, "bucket"
    thumb = getattr(row, "Thumbnail300KURL", None)
    if isinstance(thumb, str) and thumb.startswith("http") and _get(thumb, path):
        return row.ImageID, "flickr_thumbnail"
    return None, None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--work", default="D:/FoodML")
    ap.add_argument("--cap", type=int, default=1000)
    ap.add_argument("--not-food", type=int, default=4500)
    ap.add_argument("--threads", type=int, default=48)
    ap.add_argument("--classes", default="", help="only these classes (comma-separated)")
    a = ap.parse_args()
    oi = os.path.join(a.work, "oi")
    img_dir = os.path.join(a.work, "images", "oi")
    os.makedirs(img_dir, exist_ok=True)
    asg = pd.read_parquet(os.path.join(oi, "assign.parquet"))
    if a.classes:  # v3: only the photos of these classes (the new ones); meta.parquet then lists only them
        asg = asg[asg.cls.isin(a.classes.split(","))]
    asg = asg.sample(frac=1.0, random_state=11)
    # photos already on disk first (a rerun with a higher cap or a changed class list keeps the earlier photos)
    have = {os.path.splitext(f)[0] for f in os.listdir(img_dir)}
    asg["_have"] = asg.ImageID.isin(have)
    asg = asg.sort_values("_have", ascending=False, kind="stable").drop(columns="_have")
    caps = asg.cls.map(lambda c: a.not_food if c == NOT_FOOD else a.cap)
    asg = asg[asg.groupby("cls").cumcount() < caps]
    print(len(asg), "photos to fetch", flush=True)

    ids = set(asg.ImageID)
    metas = []
    for subset, fn in META.items():
        for ch in pd.read_csv(os.path.join(a.work, "data", "oi_meta", fn), chunksize=1_000_000,
                              usecols=["ImageID", "OriginalURL", "OriginalLandingURL", "License", "Author",
                                       "AuthorProfileURL", "Title", "Thumbnail300KURL"], dtype=str):
            metas.append(ch[ch.ImageID.isin(ids)])
    meta = pd.concat(metas).drop_duplicates("ImageID")
    print(len(meta), "with metadata", flush=True)
    asg = asg.merge(meta[["ImageID", "Thumbnail300KURL"]], on="ImageID", how="left")
    prev = {}
    prev_path = os.path.join(oi, "meta.parquet")
    if os.path.exists(prev_path):
        p = pd.read_parquet(prev_path)
        if "via" in p.columns:
            prev = dict(zip(p.ImageID, p.via))

    done, via = [], {}
    with cf.ThreadPoolExecutor(a.threads) as pool:
        for i, (r, how) in enumerate(pool.map(lambda row: fetch(row, img_dir), asg.itertuples(index=False))):
            if r:
                done.append(r)
                via[r] = prev.get(r, "bucket") if how == "kept" else how
            if i % 5000 == 0:
                print(i, len(done), flush=True)
    with open(os.path.join(oi, "downloaded.txt"), "w", newline="\n") as f:
        f.write("\n".join(done) + "\n")
    got = asg[asg.ImageID.isin(set(done))].drop(columns=["Thumbnail300KURL"]).merge(meta, on="ImageID", how="left")
    got["via"] = got.ImageID.map(via)
    print(got.via.value_counts().to_string())
    got.to_parquet(os.path.join(oi, "meta.parquet"), index=False)
    print("downloaded", len(got), "missing metadata", int(got.License.isna().sum()))
    print(got.License.value_counts().to_string())


if __name__ == "__main__":
    main()
