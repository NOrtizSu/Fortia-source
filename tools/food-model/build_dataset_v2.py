# SPDX-License-Identifier: AGPL-3.0-or-later
"""Build the v2 classifier dataset: the v1 photos (same split, same label) + new photos + item crops.

Sources:
  - v1: <work>/dataset/{train,val,test}.csv (127 k photos; `dolma` dropped: its photos are grapevines). Every
    v1 photo keeps its split and class, so the v1 test set stays a fair comparison.
  - Open Images photos (image-level labels, select_classes.py + fetch_open_images.py with the v2 class list).
  - Wikimedia Commons photos (fetch_wikimedia.py --plan/--download with the v2 class list).
  - Item crops, made exactly like the app crops a detector box (food_items.square_crop, margin 0.10), so the
    classifier learns the multi-item flow too:
      * MyFoodRepo / FRB 2022 items whose class maps to ours (frb_map.py); FRB "validation" photos -> test;
      * Open Images boxes of specific foods (OI_BOX_TO_CLASS) on photos with 2+ items, the box under half the
        photo (a box that is the whole photo is just the photo).
Near-duplicates (64-bit dHash, Hamming <= 4) of whole photos are merged across all sources: a new photo that
near-duplicates a v1 photo takes that photo's split (or is dropped when the class differs). Crops follow the
split of their source photo; photos not in v1 get a split from a hash of their id (test 8 %, val 5 %).
At most --cap photos per class (v1 photos first, then new ones at random), --cap-not-food for `_not_food`;
classes with fewer than --min-train training photos are dropped.

Label order: the v1 classes in their v1 order, then the new ones (classes_config order), `_not_food` last.

Output (--out, default <work>/dataset_v2): {train,val,test}.csv (path,label,class,source,source_id,kind),
test_v1.csv (the v1 test photos with v2 labels), attribution.csv, counts.csv; classes_v2.csv (--out-classes) and
food_classes_v2.csv (id,name_en,name_es,status) for the handoff.

usage: python -I build_dataset_v2.py --work D:/FoodML --out-classes classes.csv
"""
import argparse
import csv
import hashlib
import json
import os
import sys
from concurrent.futures import ThreadPoolExecutor
from multiprocessing import Pool

import numpy as np
import pandas as pd
from PIL import Image, ImageOps

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from build_dataset import MIN_WM_SATURATION, dhash, near_dup_groups  # noqa: E402
from classes_config import CLASSES, NOT_FOOD  # noqa: E402
from food_items import fast_path, square_crop  # noqa: E402
from frb_map import FRB_TO_CLASS  # noqa: E402

OI_BOX_TO_CLASS = {
    "Apple": "apple", "Artichoke": "artichoke", "Bagel": "bagel", "Banana": "banana", "Beer": "beer",
    "Bell pepper": "bell_pepper", "Bread": "bread", "Broccoli": "broccoli", "Burrito": "wrap",
    "Cabbage": "cabbage", "Cake": "cake", "Candy": "candy", "Cantaloupe": "melon", "Carrot": "carrot",
    "Cheese": "cheese", "Cocktail": "cocktail", "Coconut": "coconut", "Cookie": "cookie", "Crab": "crab",
    "Croissant": "croissant", "Cucumber": "cucumber", "Doughnut": "donut", "Egg": "egg",
    "French fries": "french_fries", "Garden Asparagus": "asparagus", "Grape": "grapes", "Grapefruit": "grapefruit",
    "Guacamole": "guacamole", "Hamburger": "hamburger", "Hot dog": "hot_dog", "Ice cream": "ice_cream",
    "Juice": "juice", "Lemon (plant)": "lemon", "Lobster": "lobster", "Mango": "mango", "Milk": "milk",
    "Muffin": "muffin", "Mushroom": "mushrooms", "Orange (fruit)": "orange", "Oyster": "oysters",
    "Pancake": "pancakes", "Pasta": "pasta", "Peach": "peach", "Pear": "pear", "Pineapple": "pineapple",
    "Pizza": "pizza", "Pomegranate": "pomegranate", "Popcorn": "popcorn", "Potato": "potato", "Pretzel": "pretzel",
    "Pumpkin": "pumpkin", "Radish": "radish", "Salad": "green_salad", "Sandwich": "sandwich", "Shrimp": "prawns",
    "Squid": "calamari", "Strawberry": "strawberry", "Submarine sandwich": "baguette_sandwich", "Sushi": "sushi",
    "Taco": "tacos", "Tart": "tart", "Tea": "tea", "Tomato": "tomato", "Waffle": "waffles",
    "Watermelon": "watermelon", "Zucchini": "zucchini",
}
FRB_LICENCE = ("CC BY 4.0", "https://creativecommons.org/licenses/by/4.0/")
MIN_CROP = 48     # px: smaller items are too blurry to learn from
EXCLUDE = {"dolma"}  # its Open Images label ("Grape leaves") shows grapevines, not stuffed vine leaves (v1)
CROP_SHORT = 320  # crops are stored with at most this short side


def hsplit(key, test=0.08, val=0.05):
    h = int(hashlib.sha1(key.encode()).hexdigest()[:8], 16) / 0xFFFFFFFF
    return "test" if h < test else "val" if h < test + val else "train"


def make_crop(job):
    src, box, dst = job
    if os.path.exists(dst):
        return True
    try:
        with Image.open(fast_path(src)) as im:
            im = ImageOps.exif_transpose(im).convert("RGB")
            w, h = im.size
            x0, y0, x1, y1 = box[0] * w, box[1] * h, box[2] * w, box[3] * h
            left, top, side = square_crop((x0, y0, x1, y1), w, h, 0.1)
            if side < MIN_CROP:
                return False
            c = im.crop((left, top, min(w, left + side), min(h, top + side)))
            if min(c.size) > CROP_SHORT:
                s = CROP_SHORT / min(c.size)
                c = c.resize((round(c.size[0] * s), round(c.size[1] * s)), Image.BICUBIC)
            c.save(dst + ".tmp", "JPEG", quality=90)
            os.replace(dst + ".tmp", dst)
            return True
    except Exception:
        return False


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--work", default="D:/FoodML")
    ap.add_argument("--out", default="")
    ap.add_argument("--out-classes", default="classes.csv")
    ap.add_argument("--handoff", default="", help="also write food_classes_v2.csv here")
    ap.add_argument("--min-train", type=int, default=150)
    ap.add_argument("--cap", type=int, default=1500)
    ap.add_argument("--cap-not-food", type=int, default=5000)
    ap.add_argument("--workers", type=int, default=12)
    ap.add_argument("--crop-root", default="", help="where the crops are written (default <work>/images; an SSD)")
    a = ap.parse_args()
    rng = np.random.default_rng(2026)
    out = a.out or os.path.join(a.work, "dataset_v2")
    os.makedirs(out, exist_ok=True)
    conf = {c["id"]: c for c in CLASSES if c["id"] not in EXCLUDE}
    v1_classes = [r["id"] for r in csv.DictReader(open(os.path.join(os.path.dirname(os.path.abspath(__file__)),
                                                                    "classes_v1.csv"), encoding="utf-8"))]

    # ---- v1 photos
    attr = pd.read_csv(os.path.join(a.work, "dataset", "attribution.csv"), dtype=str)
    attr_key = attr.set_index(["source", "source_id"])
    v1 = []
    for s in ("train", "val", "test"):
        d = pd.read_csv(os.path.join(a.work, "dataset", f"{s}.csv"), dtype={"source_id": str})
        d["split"] = s
        v1.append(d)
    v1 = pd.concat(v1, ignore_index=True)
    v1 = v1[v1["class"] != "dolma"]
    v1 = v1.merge(attr[["source", "source_id", "url", "author", "licence", "licence_url"]],
                  on=["source", "source_id"], how="left")
    v1["kind"] = "photo"
    v1["origin"] = "v1"
    v1_ids = set(zip(v1.source, v1.source_id))
    v1_split = {(r.source, r.source_id): r.split for r in v1.itertuples(index=False)}
    print(len(v1), "v1 photos", flush=True)

    rows = []
    # ---- new Open Images photos
    meta = pd.read_parquet(os.path.join(a.work, "oi", "meta.parquet"))
    for r in meta.itertuples(index=False):
        if r.cls not in conf and r.cls != NOT_FOOD:
            continue
        if ("openimages", r.ImageID) in v1_ids:
            continue
        p = os.path.join(a.work, "images", "oi", r.ImageID + ".jpg")
        if os.path.exists(p):
            rows.append({"path": p, "class": r.cls, "source": "openimages", "source_id": r.ImageID,
                         "url": r.OriginalLandingURL or r.OriginalURL, "author": r.Author or "",
                         "licence": "CC BY 2.0", "licence_url": r.License or "", "kind": "photo", "group": r.ImageID})
    # ---- new Commons photos
    with open(os.path.join(a.work, "wm", "candidates.jsonl"), encoding="utf-8") as f:
        for line in f:
            r = json.loads(line)
            # (a class whose Commons categories were removed from classes_config after review takes none)
            if (r["class"] not in conf or not conf[r["class"]]["wikimedia"]
                    or ("wikimedia", r["title"]) in v1_ids):
                continue
            p = os.path.join(a.work, "images", "wm", r["sha1"] + ".jpg")
            if os.path.exists(p):
                rows.append({"path": p, "class": r["class"], "source": "wikimedia", "source_id": r["title"],
                             "url": r["page"], "author": r["author"], "licence": r["licence"],
                             "licence_url": r["licence_url"], "kind": "photo", "group": r["sha1"]})
    new_photos = pd.DataFrame(rows)
    print(len(new_photos), "new photos", new_photos.groupby("source").size().to_dict(), flush=True)

    # ---- dedup whole photos (v1 + new)
    cache_path = os.path.join(a.work, "hash_cache.json")
    cache = json.load(open(cache_path)) if os.path.exists(cache_path) else {}
    allp = pd.concat([v1[["path", "class", "source", "split"]].assign(origin="v1"),
                      new_photos[["path", "class", "source"]].assign(origin="new", split="")], ignore_index=True)
    todo = [p for p in allp.path if p not in cache]
    with Pool(a.workers) as pool:
        for p, h in zip(todo, pool.map(dhash, todo, chunksize=256)):
            cache[p] = h
    with open(cache_path + ".tmp", "w") as f:
        json.dump(cache, f)
    os.replace(cache_path + ".tmp", cache_path)
    hs = [cache[p] for p in allp.path]
    ok = np.array([h is not None and not (src == "wikimedia" and o == "new" and h[1] < MIN_WM_SATURATION)
                   for h, src, o in zip(hs, allp.source, allp.origin)])
    allp = allp[ok].reset_index(drop=True)
    groups = near_dup_groups([cache[p][0] for p in allp.path])
    allp["g"] = groups
    keep_new, inherit = set(), {}
    for g, d in allp.groupby("g"):
        old = d[d.origin == "v1"]
        new = d[d.origin == "new"]
        if not len(new):
            continue
        if len(old):
            # a new copy of a v1 photo: same class -> nothing new to learn; different class -> label noise
            continue
        if new["class"].nunique() > 1:
            continue
        keep_new.add(new.path.iloc[0])
    new_photos = new_photos[new_photos.path.isin(keep_new)]
    print(len(new_photos), "new photos after near-duplicate removal", flush=True)

    # ---- crops: FRB
    crop_dir = os.path.join(a.crop_root or os.path.join(a.work, "images"), "crops")
    os.makedirs(os.path.join(crop_dir, "frb"), exist_ok=True)
    os.makedirs(os.path.join(crop_dir, "oi"), exist_ok=True)
    frb = pd.read_parquet(os.path.join(a.work, "frb", "items.parquet"))
    frb = frb[frb.split.isin(["training", "validation"]) & frb.cls.isin(FRB_TO_CLASS)]
    jobs, crows = [], []
    for k, r in enumerate(frb.itertuples(index=False)):
        box = (r.x0 / r.width, r.y0 / r.height, r.x1 / r.width, r.y1 / r.height)
        stem = os.path.splitext(r.image)[0]
        dst = os.path.join(crop_dir, "frb", f"{stem}_{int(r.x0)}_{int(r.y0)}_{int(r.x1)}_{int(r.y1)}.jpg")
        jobs.append((r.path, box, dst))
        split = "test" if r.split == "validation" else hsplit("frb" + stem, test=0.0, val=0.05)
        crows.append({"path": dst, "class": FRB_TO_CLASS[r.cls], "source": "myfoodrepo",
                      "source_id": f"{r.split}/{r.image}#{int(r.x0)},{int(r.y0)},{int(r.x1)},{int(r.y1)}",
                      "url": "https://www.aicrowd.com/challenges/food-recognition-benchmark-2022",
                      "author": "MyFoodRepo users (AIcrowd Food Recognition Benchmark 2022)",
                      "licence": FRB_LICENCE[0], "licence_url": FRB_LICENCE[1], "kind": "crop",
                      "group": "frb" + stem, "split": split})
    # ---- crops: Open Images boxes
    items = pd.read_parquet(os.path.join(a.work, "det", "oi_items.parquet"))
    n_items = items.groupby("ImageID").label.transform("size")
    area = (items.x1 - items.x0) * (items.y1 - items.y0)
    items = items[(n_items >= 2) & (area < 0.5) & items.label.isin(OI_BOX_TO_CLASS)]
    oi_meta_cols = ["ImageID", "OriginalURL", "OriginalLandingURL", "License", "Author"]
    want = set(items.ImageID)
    m = []
    for fn in ("oidv6-train-images-with-labels-with-rotation.csv", "validation-images-with-rotation.csv",
               "test-images-with-rotation.csv"):
        for ch in pd.read_csv(os.path.join(a.work, "data", "oi_meta", fn), chunksize=1_000_000, usecols=oi_meta_cols,
                              dtype=str):
            m.append(ch[ch.ImageID.isin(want)])
    m = pd.concat(m).drop_duplicates("ImageID").set_index("ImageID")
    for r in items.itertuples(index=False):
        src = os.path.join(a.work, "images", "oi_box", r.ImageID + ".jpg")
        if not os.path.exists(src):
            src = os.path.join(a.work, "images", "oi", r.ImageID + ".jpg")
            if not os.path.exists(src):
                continue
        if r.ImageID not in m.index:
            continue
        mm = m.loc[r.ImageID]
        dst = os.path.join(crop_dir, "oi", f"{r.ImageID}_{r.x0:.3f}_{r.y0:.3f}_{r.x1:.3f}_{r.y1:.3f}.jpg")
        jobs.append((src, (r.x0, r.y0, r.x1, r.y1), dst))
        split = v1_split.get(("openimages", r.ImageID)) or hsplit(r.ImageID)
        crows.append({"path": dst, "class": OI_BOX_TO_CLASS[r.label], "source": "openimages_box",
                      "source_id": f"{r.ImageID}#{r.x0:.3f},{r.y0:.3f},{r.x1:.3f},{r.y1:.3f}",
                      "url": mm.OriginalLandingURL or mm.OriginalURL, "author": mm.Author or "",
                      "licence": "CC BY 2.0", "licence_url": mm.License or "", "kind": "crop",
                      "group": r.ImageID, "split": split})
    print(len(jobs), "crops to make", flush=True)
    with ThreadPoolExecutor(a.workers) as pool:
        made = list(pool.map(make_crop, jobs, chunksize=64))
    crops = pd.DataFrame([c for c, k in zip(crows, made) if k])
    print(len(crops), "crops", crops.groupby("source").size().to_dict(), flush=True)

    # ---- splits for new photos: inherit a v1 split through the group, else a hash
    new_photos["split"] = [v1_split.get((s, i)) or hsplit(g) for s, i, g in
                           zip(new_photos.source, new_photos.source_id, new_photos.group)]
    new = pd.concat([new_photos, crops], ignore_index=True)
    new["origin"] = "new"

    # ---- per class: v1 first, then new at random, up to the cap; min-train
    order = [c for c in v1_classes if c != NOT_FOOD] + [c["id"] for c in CLASSES
                                                        if c["id"] not in v1_classes and c["id"] in conf]
    final, counts, dropped = [], [], []
    picked = []
    for cid in order + [NOT_FOOD]:
        old = v1[v1["class"] == cid]
        cand = new[new["class"] == cid]
        cand = cand.iloc[rng.permutation(len(cand))]
        cap = a.cap_not_food if cid == NOT_FOOD else a.cap
        take = cand.head(max(0, cap - len(old)))
        d = pd.concat([old, take], ignore_index=True)
        n_train = int((d.split == "train").sum())
        if cid != NOT_FOOD and n_train < a.min_train:
            dropped.append((cid, n_train, len(d)))
            continue
        final.append(cid)
        picked.append(d)
        counts.append({"class": cid, "train": n_train, "val": int((d.split == "val").sum()),
                       "test": int((d.split == "test").sum()), "v1": len(old),
                       "openimages": int((take.source == "openimages").sum()),
                       "wikimedia": int((take.source == "wikimedia").sum()),
                       "myfoodrepo_crops": int((take.source == "myfoodrepo").sum()),
                       "openimages_crops": int((take.source == "openimages_box").sum())})
    label = {c: i for i, c in enumerate(final)}
    data = pd.concat(picked, ignore_index=True)
    data["label"] = data["class"].map(label)
    print(f"{len(final) - 1} classes + {NOT_FOOD}; dropped {len(dropped)}:", dropped, flush=True)
    for s in ("train", "val", "test"):
        d = data[data.split == s].sample(frac=1.0, random_state=7)
        d[["path", "label", "class", "source", "source_id", "kind"]].to_csv(os.path.join(out, f"{s}.csv"),
                                                                            index=False)
    t1 = data[(data.origin == "v1") & (data.split == "test")]
    t1[["path", "label", "class", "source", "source_id", "kind"]].to_csv(os.path.join(out, "test_v1.csv"),
                                                                         index=False)
    data[["split", "class", "source", "source_id", "url", "author", "licence", "licence_url"]].sort_values(
        ["class", "source", "source_id"]).to_csv(os.path.join(out, "attribution.csv"), index=False)
    pd.DataFrame(counts).to_csv(os.path.join(out, "counts.csv"), index=False)
    print(data.groupby(["split", "source"]).size().to_string(), flush=True)

    # ---- class lists
    first_new = next(i for i, c in enumerate(CLASSES) if c["id"] == "water")
    added_v2 = {c["id"] for c in CLASSES[first_new:]}
    n_img = data.groupby("class").size().to_dict()
    with open(a.out_classes, "w", encoding="utf-8", newline="\n") as f:
        w = csv.writer(f, lineterminator="\n")
        w.writerow(["id", "en_name", "es_name_hint", "group", "status", "n_images"])
        for cid in final:
            if cid == NOT_FOOD:
                w.writerow([NOT_FOOD, "Not food", "no es comida", "", "kept", n_img[cid]])
                continue
            c = conf[cid]
            st = "kept" if cid in v1_classes else "new" if cid in added_v2 else "recovered"
            w.writerow([cid, c["en"], c["es"], c["group"], st, n_img[cid]])
    if a.handoff:
        with open(a.handoff, "w", encoding="utf-8", newline="\n") as f:
            w = csv.writer(f, lineterminator="\n")
            w.writerow(["id", "name_en", "name_es", "status"])
            for cid in final:
                if cid == NOT_FOOD:
                    continue
                c = conf[cid]
                w.writerow([cid, c["en"], c["es"], "kept" if cid in v1_classes else
                            "new" if cid in added_v2 else "recovered"])
            for cid in [d[0] for d in dropped if d[0] in v1_classes]:
                c = conf[cid]
                w.writerow([cid, c["en"], c["es"], "dropped"])
    print("written", out)


if __name__ == "__main__":
    main()
