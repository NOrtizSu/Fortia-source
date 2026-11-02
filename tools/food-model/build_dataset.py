# SPDX-License-Identifier: AGPL-3.0-or-later
"""Build the train/val/test splits and the attribution manifest from the downloaded photos.

- Inputs: the candidate classes of classes_config.py, <work>/oi/meta.parquet (Open Images photos + licence),
  <work>/wm/candidates.jsonl + the downloaded thumbnails (Wikimedia Commons photos + licence).
- Near-duplicates (64-bit difference hash, Hamming distance <= 4) are grouped; a group whose photos carry
  different classes is dropped, otherwise one photo of the group is kept, so no near-identical photo sits in
  two splits.
- Per class at most --cap photos (Open Images and Commons mixed at random), then test = 10 % (min 12),
  val = 8 % (min 10), the rest train. `_not_food` gets --cap-not-food.
- Classes left with fewer than --min-train training photos are dropped; the final list (label order =
  model output order, `_not_food` last) is written to --out-classes (classes.csv).

Output: <work>/dataset/{train,val,test}.csv (path,label,class,source,source_id) and
        <work>/dataset/attribution.csv (every photo used: split, class, source, url, author, licence).

usage: python build_dataset.py --work D:/FoodML --out-classes classes.csv
"""
import argparse
import csv
import json
import os
import random
from multiprocessing import Pool

import sys

import numpy as np
import pandas as pd
from PIL import Image

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from classes_config import CLASSES, NOT_FOOD  # noqa: E402

MIN_WM_SATURATION = 0.12


def dhash(path):
    """(64-bit difference hash, mean HSV saturation 0..1) of a photo, or None if it can't be read."""
    try:
        with Image.open(path) as im:
            im = im.convert("RGB")
            g = np.asarray(im.convert("L").resize((9, 8), Image.BILINEAR), dtype=np.int16)
            sat = float(np.asarray(im.resize((64, 64)).convert("HSV"))[:, :, 1].mean()) / 255.0
    except Exception:
        return None
    bits = (g[:, 1:] > g[:, :-1]).flatten()
    return int(np.packbits(bits).view(">u8")[0]), sat


class UF:
    def __init__(self, n):
        self.p = list(range(n))

    def find(self, x):
        while self.p[x] != x:
            self.p[x] = self.p[self.p[x]]
            x = self.p[x]
        return x

    def union(self, a, b):
        a, b = self.find(a), self.find(b)
        if a != b:
            self.p[b] = a


def near_dup_groups(hashes, max_dist=4):
    """Union photos whose hashes differ in <= max_dist bits (pigeonhole on 5 blocks of ~13 bits)."""
    n = len(hashes)
    uf = UF(n)
    h = np.array(hashes, dtype=np.uint64)
    blocks = [(0, 13), (13, 26), (26, 39), (39, 52), (52, 64)]
    for lo, hi in blocks:
        key = (h >> np.uint64(lo)) & np.uint64((1 << (hi - lo)) - 1)
        order = np.argsort(key, kind="stable")
        ks = key[order]
        start = 0
        for i in range(1, n + 1):
            if i == n or ks[i] != ks[start]:
                idx = order[start:i]
                if 1 < len(idx) <= 400:
                    for a in range(len(idx)):
                        for b in range(a + 1, len(idx)):
                            x, y = int(idx[a]), int(idx[b])
                            if bin(hashes[x] ^ hashes[y]).count("1") <= max_dist:
                                uf.union(x, y)
                start = i
    return [uf.find(i) for i in range(n)]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--work", default="D:/FoodML")
    ap.add_argument("--out-classes", default="classes.csv")
    ap.add_argument("--min-train", type=int, default=150)
    ap.add_argument("--cap", type=int, default=1000)
    ap.add_argument("--cap-not-food", type=int, default=4000)
    ap.add_argument("--workers", type=int, default=12)
    ap.add_argument("--out-dir", default="dataset", help="under --work")
    a = ap.parse_args()
    random.seed(1234)
    cidx = {c["id"]: i for i, c in enumerate(CLASSES)}
    cidx[NOT_FOOD] = len(cidx)

    items = []
    meta = pd.read_parquet(os.path.join(a.work, "oi", "meta.parquet"))
    for r in meta.itertuples(index=False):
        if r.cls in cidx:
            items.append({"path": os.path.join(a.work, "images", "oi", r.ImageID + ".jpg"), "class": r.cls,
                          "source": "openimages", "source_id": r.ImageID,
                          "url": r.OriginalLandingURL or r.OriginalURL, "author": r.Author or "",
                          "licence": "CC BY 2.0", "licence_url": r.License or ""})
    # Commons: every planned photo (fetch_wikimedia.py --plan) whose thumbnail was downloaded
    man = os.path.join(a.work, "wm", "candidates.jsonl")
    with open(man if os.path.exists(man) else os.devnull, encoding="utf-8") as f:
        for line in f:
            r = json.loads(line)
            if r["class"] in cidx:
                items.append({"path": os.path.join(a.work, "images", "wm", r["sha1"] + ".jpg"),
                              "class": r["class"], "source": "wikimedia", "source_id": r["title"],
                              "url": r["page"], "author": r["author"], "licence": r["licence"],
                              "licence_url": r["licence_url"]})
    items = [it for it in items if os.path.exists(it["path"])]
    print(len(items), "photos", flush=True)
    # hashes are cached per file (the photos never change once downloaded)
    cache_path = os.path.join(a.work, "hash_cache.json")
    cache = json.load(open(cache_path)) if os.path.exists(cache_path) else {}
    todo = [it["path"] for it in items if it["path"] not in cache]
    with Pool(a.workers) as pool:
        for p, h in zip(todo, pool.map(dhash, todo, chunksize=256)):
            cache[p] = h
    with open(cache_path + ".tmp", "w") as f:
        json.dump(cache, f)
    os.replace(cache_path + ".tmp", cache_path)
    hashes = [cache[it["path"]] for it in items]
    n0 = len(items)
    items = [dict(it, h=h[0]) for it, h in zip(items, hashes) if h is not None
             # Commons categories also hold scans of old seed catalogues, engravings and black-and-white
             # photos: drop (nearly) colourless Commons images
             and not (it["source"] == "wikimedia" and h[1] < MIN_WM_SATURATION)]
    print(f"{n0 - len(items)} unreadable or colourless photos dropped")
    groups = near_dup_groups([it["h"] for it in items])
    by_group = {}
    for it, g in zip(items, groups):
        by_group.setdefault(g, []).append(it)
    kept, conflict = [], 0
    for g in by_group.values():
        if len({it["class"] for it in g}) > 1:
            conflict += 1
            continue
        kept.append(g[0])
    print(f"{len(items)} -> {len(kept)} after near-duplicate removal ({conflict} groups with mixed labels)")

    per_class = {}
    for it in kept:
        per_class.setdefault(it["class"], []).append(it)
    splits = {"train": [], "val": [], "test": []}
    counts, dropped, final = [], [], []
    for c in list(cidx):
        lst = per_class.get(c, [])
        random.shuffle(lst)
        lst = lst[:a.cap_not_food if c == NOT_FOOD else a.cap]
        n = len(lst)
        n_test = max(12, round(0.08 * n))
        n_val = max(8, round(0.05 * n))
        if c != NOT_FOOD and n - n_test - n_val < a.min_train:
            dropped.append((c, n))
            continue
        label = len(final)
        final.append(c)
        for i, it in enumerate(lst):
            s = "test" if i < n_test else "val" if i < n_test + n_val else "train"
            splits[s].append(dict(it, label=label, split=s))
        counts.append((c, n - n_test - n_val, n_val, n_test,
                       sum(1 for it in lst if it["source"] == "openimages"),
                       sum(1 for it in lst if it["source"] == "wikimedia")))
    assert final[-1] == NOT_FOOD
    print(f"{len(final) - 1} classes + {NOT_FOOD}; dropped {len(dropped)}: {dropped}")
    write_classes(a.out_classes, final, a.work, {c[0]: c[1] + c[2] + c[3] for c in counts})
    out = os.path.join(a.work, a.out_dir)
    os.makedirs(out, exist_ok=True)
    for s, rows in splits.items():
        random.shuffle(rows)
        pd.DataFrame(rows)[["path", "label", "class", "source", "source_id"]].to_csv(
            os.path.join(out, f"{s}.csv"), index=False)
    allrows = [r for rows in splits.values() for r in rows]
    pd.DataFrame(allrows)[["split", "class", "source", "source_id", "url", "author", "licence",
                           "licence_url"]].sort_values(["class", "source", "source_id"]).to_csv(
        os.path.join(out, "attribution.csv"), index=False)
    pd.DataFrame(counts, columns=["class", "train", "val", "test", "openimages", "wikimedia"]).to_csv(
        os.path.join(out, "counts.csv"), index=False)
    print({s: len(r) for s, r in splits.items()})


def write_classes(path, final, work, n_images):
    """classes.csv in model output order: id,en_name,es_name_hint,group,open_images_mids,wikimedia_categories,n_images"""
    with open(os.path.join(work, "oi", "mids.json"), encoding="utf-8") as f:
        mids = json.load(f)
    conf = {c["id"]: c for c in CLASSES}
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        w = csv.writer(f, lineterminator="\n")
        w.writerow(["id", "en_name", "es_name_hint", "group", "open_images_mids", "wikimedia_categories",
                    "n_images"])
        for cid in final:
            if cid == NOT_FOOD:
                w.writerow([NOT_FOOD, "Not food", "no es comida", "", "/m/02wbm (verified absent)", "",
                            n_images[cid]])
                continue
            c = conf[cid]
            w.writerow([cid, c["en"], c["es"], c["group"], " ".join(mids.get(cid, [])),
                        " ".join(x.split("|")[0].replace(" ", "_") for x in c["wikimedia"]), n_images[cid]])


if __name__ == "__main__":
    main()
