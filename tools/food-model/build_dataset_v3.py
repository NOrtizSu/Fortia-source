# SPDX-License-Identifier: AGPL-3.0-or-later
"""Build the v3 classifier dataset: the v2 pictures (same split, same label) + photos and crops for v3 classes.

v3 classes are the candidates of classes_config.py that are not v2 classes: the v1/v2 classes that stayed under
the 150 training pictures (patatas bravas, chestnuts, celery, morcilla, moussaka...) and new ones (baklava, kimchi,
fajitas, teriyaki, dal, stuffed peppers...). Their pictures:
  - Open Images photos assigned to them by select_classes.py (highest rank wins) and fetched by
    fetch_open_images.py; a v2 Open Images photo that is now assigned to a v3 class (more specific) moves to it;
  - Wikimedia Commons photos of fetch_wikimedia.py --plan/--download (v3: categories + file-name search); a v2
    Commons photo whose most specific class is now a v3 class moves to it;
  - MyFoodRepo item crops whose class maps to them (frb_map.py; the app's crop, made like v2's).
New photos that near-duplicate a v2 photo (64-bit dHash, Hamming <= 4) are dropped; new photos get a split from a
hash of their id (test 8 %, val 5 %); crops follow their photo. At most --cap pictures per class (v2 first); a v3
class needs --min-train training pictures, else its pictures are dropped and moved photos go back to their v2 class.

Label order: the 329 v2 foods in v2 order, then the v3 classes that made it (classes_config order), `_not_food` last.

Output (--out): {train,val,test}.csv (path,label,class,source,source_id,kind), test_v2.csv (the 1.6.0 test pictures
with their v2 class and v3 label numbers: the comparable test), test_v1.csv (the 1.5.0 test photos),
attribution.csv, counts.csv; --out-classes (classes.csv) and --handoff (food_classes_v3.csv).

usage: python -I build_dataset_v3.py --work D:/FoodML --v2 D:/FoodML/dataset_v2 --out D:/FoodML/dataset_v3 \
         --crop-root E:/FoodML_ssd/images --out-classes classes.csv --handoff food_classes_v3.csv
"""
import argparse
import csv
import json
import os
import sys
from concurrent.futures import ThreadPoolExecutor
from multiprocessing import Pool

import numpy as np
import pandas as pd

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from build_dataset import MIN_WM_SATURATION, dhash, near_dup_groups  # noqa: E402
from build_dataset_v2 import EXCLUDE, FRB_LICENCE, hsplit, make_crop  # noqa: E402
from classes_config import CLASSES, NOT_FOOD  # noqa: E402
from frb_map import FRB_TO_CLASS  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
COLS = ["path", "label", "class", "source", "source_id", "kind"]


def not_food_prob(paths, ckpt, bs=64):
    """p(`_not_food`) of each photo by a trained classifier (the last label), its own preprocessing."""
    import timm
    import torch
    from PIL import Image, ImageOps
    import train as TR
    ck = torch.load(ckpt, map_location="cpu", weights_only=False)
    TR.configure_from_checkpoint(ck)
    m = TR.build_model(ck["model"], ck["n_classes"], ck.get("head_hidden", 0), pretrained=False)
    m.load_state_dict(ck["state_dict"])
    m.eval().cuda()
    tf = TR.eval_tf()
    out = []
    for i in range(0, len(paths), bs):
        xs = []
        for p in paths[i:i + bs]:
            with Image.open(p) as im:
                xs.append(tf(ImageOps.exif_transpose(im).convert("RGB")))
        x = TR.gpu_normalize(torch.stack(xs).cuda())
        with torch.no_grad(), torch.autocast("cuda", dtype=torch.float16):
            out.append(torch.softmax(m(x).float(), 1)[:, -1].cpu())
    return torch.cat(out).numpy() if out else np.zeros(0)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--work", default="D:/FoodML")
    ap.add_argument("--v2", default="D:/FoodML/dataset_v2")
    ap.add_argument("--out", default="D:/FoodML/dataset_v3")
    ap.add_argument("--v2-classes", default=os.path.join(HERE, "classes_v2.csv"))
    ap.add_argument("--v1-classes", default=os.path.join(HERE, "classes_v1.csv"))
    ap.add_argument("--out-classes", default=os.path.join(HERE, "classes.csv"))
    ap.add_argument("--handoff", default="")
    ap.add_argument("--min-train", type=int, default=150)
    ap.add_argument("--cap", type=int, default=1500)
    ap.add_argument("--workers", type=int, default=12)
    ap.add_argument("--crop-root", default="E:/FoodML_ssd/images")
    ap.add_argument("--filter-ckpt", default="", help="drop new Commons photos this classifier calls `_not_food`")
    ap.add_argument("--max-not-food", type=float, default=0.5)
    a = ap.parse_args()
    rng = np.random.default_rng(2027)
    os.makedirs(a.out, exist_ok=True)
    conf = {c["id"]: c for c in CLASSES if c["id"] not in EXCLUDE}
    v2_classes = [r["id"] for r in csv.DictReader(open(a.v2_classes, encoding="utf-8"))]
    v1_classes = {r["id"] for r in csv.DictReader(open(a.v1_classes, encoding="utf-8"))}
    v2_set = set(v2_classes)
    cand = [c["id"] for c in CLASSES if c["id"] in conf and c["id"] not in v2_set]
    cand_set = set(cand)
    print(len(v2_classes), "v2 classes,", len(cand), "v3 candidates", flush=True)

    # ---- v2 pictures
    v2 = []
    for s in ("train", "val", "test"):
        d = pd.read_csv(os.path.join(a.v2, f"{s}.csv"), dtype={"source_id": str})
        d["split"] = s
        v2.append(d)
    v2 = pd.concat(v2, ignore_index=True)
    attr = pd.read_csv(os.path.join(a.v2, "attribution.csv"), dtype=str).drop_duplicates(["source", "source_id"])
    v2 = v2.merge(attr[["source", "source_id", "url", "author", "licence", "licence_url"]],
                  on=["source", "source_id"], how="left")
    v2["v2_class"] = v2["class"]
    v2["origin"] = "v2"
    v2_test_v1 = pd.read_csv(os.path.join(a.v2, "test_v1.csv"), dtype={"source_id": str})
    v2_key = {(s, i): k for k, (s, i) in enumerate(zip(v2.source, v2.source_id))}
    print(len(v2), "v2 pictures", flush=True)

    # ---- moves + new photos: Open Images
    assign = pd.read_parquet(os.path.join(a.work, "oi", "assign.parquet"))
    oi_cls = dict(zip(assign.ImageID, assign.cls))
    meta = pd.read_parquet(os.path.join(a.work, "oi", "meta.parquet")).drop_duplicates("ImageID")
    moved = 0
    for k in np.flatnonzero((v2.source == "openimages").to_numpy()):
        c = oi_cls.get(v2.source_id.iat[k])
        if c in cand_set:
            v2.loc[k, "class"] = c
            moved += 1
    rows = []
    for r in meta.itertuples(index=False):
        if r.cls not in cand_set or ("openimages", r.ImageID) in v2_key:
            continue
        p = os.path.join(a.work, "images", "oi", r.ImageID + ".jpg")
        if os.path.exists(p):
            rows.append({"path": p, "class": r.cls, "source": "openimages", "source_id": r.ImageID,
                         "url": r.OriginalLandingURL or r.OriginalURL, "author": r.Author or "",
                         "licence": "CC BY 2.0", "licence_url": r.License or "", "kind": "photo", "group": r.ImageID})
    # ---- Commons
    with open(os.path.join(a.work, "wm", "candidates.jsonl"), encoding="utf-8") as f:
        for line in f:
            r = json.loads(line)
            if r["class"] not in cand_set:
                continue
            k = v2_key.get(("wikimedia", r["title"]))
            if k is not None:
                v2.loc[k, "class"] = r["class"]
                moved += 1
                continue
            p = os.path.join(a.work, "images", "wm", r["sha1"] + ".jpg")
            if os.path.exists(p):
                rows.append({"path": p, "class": r["class"], "source": "wikimedia", "source_id": r["title"],
                             "url": r["page"], "author": r["author"], "licence": r["licence"],
                             "licence_url": r["licence_url"], "kind": "photo", "group": r["sha1"]})
    new_photos = pd.DataFrame(rows)
    print(len(new_photos), "new photos", new_photos.groupby("source").size().to_dict(), "; v2 photos moved", moved,
          flush=True)

    # ---- near-duplicates: new photos vs the v2 photos and each other
    cache_path = os.path.join(a.work, "hash_cache.json")
    cache = json.load(open(cache_path)) if os.path.exists(cache_path) else {}
    v2_photos = v2[v2.kind == "photo"]
    allp = pd.concat([v2_photos[["path", "class"]].assign(origin="v2"),
                      new_photos[["path", "class", "source"]].assign(origin="new")], ignore_index=True)
    todo = [p for p in allp.path if p not in cache]
    print(len(todo), "photos to hash", flush=True)
    with Pool(a.workers) as pool:
        for p, h in zip(todo, pool.map(dhash, todo, chunksize=128)):
            cache[p] = h
    with open(cache_path + ".tmp", "w") as f:
        json.dump(cache, f)
    os.replace(cache_path + ".tmp", cache_path)
    ok = np.array([cache.get(p) is not None and not (o == "new" and s == "wikimedia" and cache[p][1] < MIN_WM_SATURATION)
                   for p, o, s in zip(allp.path, allp.origin, allp.get("source", pd.Series([""] * len(allp))).fillna(""))])
    allp = allp[ok].reset_index(drop=True)
    allp["g"] = near_dup_groups([cache[p][0] for p in allp.path])
    keep = set()
    for g, d in allp.groupby("g"):
        new = d[d.origin == "new"]
        if not len(new) or (d.origin == "v2").any() or new["class"].nunique() > 1:
            continue
        keep.add(new.path.iloc[0])
    new_photos = new_photos[new_photos.path.isin(keep)].copy()
    new_photos["split"] = [hsplit(g) for g in new_photos.group]
    print(len(new_photos), "new photos after near-duplicate removal", flush=True)
    if a.filter_ckpt:
        # Commons file-name search also finds paintings, places and people named like a dish: a new Commons photo
        # is kept only if the 1.6.0 classifier does not call it `_not_food` (p < --max-not-food)
        wm = new_photos.source == "wikimedia"
        p_nf = not_food_prob(new_photos.path[wm].tolist(), a.filter_ckpt)
        bad = set(new_photos.path[wm][p_nf >= a.max_not_food])
        print(f"not-food filter: {len(bad)} of {int(wm.sum())} new Commons photos dropped", flush=True)
        new_photos = new_photos[~new_photos.path.isin(bad)]

    # ---- MyFoodRepo crops of the v3 classes
    crop_dir = os.path.join(a.crop_root, "crops", "frb")
    os.makedirs(crop_dir, exist_ok=True)
    frb = pd.read_parquet(os.path.join(a.work, "frb", "items.parquet"))
    frb = frb[frb.split.isin(["training", "validation"]) & frb.cls.map(FRB_TO_CLASS).isin(cand_set)]
    jobs, crows = [], []
    for r in frb.itertuples(index=False):
        box = (r.x0 / r.width, r.y0 / r.height, r.x1 / r.width, r.y1 / r.height)
        stem = os.path.splitext(r.image)[0]
        dst = os.path.join(crop_dir, f"{stem}_{int(r.x0)}_{int(r.y0)}_{int(r.x1)}_{int(r.y1)}.jpg")
        jobs.append((r.path, box, dst))
        split = "test" if r.split == "validation" else hsplit("frb" + stem, test=0.0, val=0.05)
        crows.append({"path": dst, "class": FRB_TO_CLASS[r.cls], "source": "myfoodrepo",
                      "source_id": f"{r.split}/{r.image}#{int(r.x0)},{int(r.y0)},{int(r.x1)},{int(r.y1)}",
                      "url": "https://www.aicrowd.com/challenges/food-recognition-benchmark-2022",
                      "author": "MyFoodRepo users (AIcrowd Food Recognition Benchmark 2022)",
                      "licence": FRB_LICENCE[0], "licence_url": FRB_LICENCE[1], "kind": "crop",
                      "group": "frb" + stem, "split": split})
    with ThreadPoolExecutor(a.workers) as pool:
        made = list(pool.map(make_crop, jobs, chunksize=32))
    crops = pd.DataFrame([c for c, k in zip(crows, made) if k])
    print(len(crops), "MyFoodRepo crops for v3 classes", flush=True)
    new = pd.concat([new_photos, crops], ignore_index=True)
    new["origin"] = "new"
    new["v2_class"] = ""

    # ---- per class: v2 first, then new at random, up to the cap; v3 classes need min-train
    final, counts, dropped, picked = [], [], [], []
    for cid in [c for c in v2_classes if c != NOT_FOOD] + cand:
        old = v2[v2["class"] == cid]
        c_new = new[new["class"] == cid]
        c_new = c_new.iloc[rng.permutation(len(c_new))]
        take = c_new.head(max(0, a.cap - len(old)))
        d = pd.concat([old, take], ignore_index=True)
        n_train = int((d.split == "train").sum())
        if cid in cand_set and n_train < a.min_train:
            dropped.append((cid, n_train, len(d)))
            back = old.copy()
            back["class"] = back["v2_class"]  # moved photos return to their v2 class
            picked.append(back)
            continue
        final.append(cid)
        picked.append(d)
        counts.append({"class": cid, "train": n_train, "val": int((d.split == "val").sum()),
                       "test": int((d.split == "test").sum()), "v2": int((d.origin == "v2").sum()),
                       "moved_from_v2": int(((d.origin == "v2") & (d["class"] != d["v2_class"])).sum()),
                       "openimages": int((take.source == "openimages").sum()),
                       "wikimedia": int((take.source == "wikimedia").sum()),
                       "myfoodrepo_crops": int((take.source == "myfoodrepo").sum())})
    picked.append(v2[v2["class"] == NOT_FOOD])
    final.append(NOT_FOOD)
    counts.append({"class": NOT_FOOD, "train": int(((v2["class"] == NOT_FOOD) & (v2.split == "train")).sum())})
    label = {c: i for i, c in enumerate(final)}
    data = pd.concat(picked, ignore_index=True)
    lost = data[~data["class"].isin(label)]
    if len(lost):
        print("pictures without a class (dropped):", len(lost), lost["class"].value_counts().head().to_dict())
        data = data[data["class"].isin(label)]
    data["label"] = data["class"].map(label).astype(int)
    print(f"{len(final) - 1} classes + {NOT_FOOD}; v3 classes kept "
          f"{[c for c in final if c in cand_set]}; dropped {len(dropped)}: {dropped}", flush=True)
    for s in ("train", "val", "test"):
        d = data[data.split == s].sample(frac=1.0, random_state=7)
        d[COLS].to_csv(os.path.join(a.out, f"{s}.csv"), index=False)
    # comparable tests: the 1.6.0 / 1.5.0 test pictures with their old class (v3 label numbers)
    t2 = pd.read_csv(os.path.join(a.v2, "test.csv"), dtype={"source_id": str})
    t2["label"] = t2["class"].map(label)
    assert t2.label.notna().all()
    t2[COLS].to_csv(os.path.join(a.out, "test_v2.csv"), index=False)
    v2_test_v1["label"] = v2_test_v1["class"].map(label)
    v2_test_v1[COLS].to_csv(os.path.join(a.out, "test_v1.csv"), index=False)
    data[["split", "class", "source", "source_id", "url", "author", "licence", "licence_url"]].sort_values(
        ["class", "source", "source_id"]).to_csv(os.path.join(a.out, "attribution.csv"), index=False)
    pd.DataFrame(counts).to_csv(os.path.join(a.out, "counts.csv"), index=False)
    print(data.groupby(["split", "source"]).size().to_string(), flush=True)

    n_img = data.groupby("class").size().to_dict()
    old_rows = {r["id"]: r for r in csv.DictReader(open(a.v2_classes, encoding="utf-8"))}

    def status(cid):
        if cid in v2_set:
            return "kept"
        return "recovered" if cid in v1_classes else "new"
    with open(a.out_classes, "w", encoding="utf-8", newline="\n") as f:
        w = csv.writer(f, lineterminator="\n")
        w.writerow(["id", "en_name", "es_name_hint", "group", "status", "n_images"])
        for cid in final:
            if cid == NOT_FOOD:
                w.writerow([NOT_FOOD, "Not food", "no es comida", "", "kept", n_img[cid]])
                continue
            c = conf.get(cid) or {"en": old_rows[cid]["en_name"], "es": old_rows[cid]["es_name_hint"],
                                  "group": old_rows[cid]["group"]}
            w.writerow([cid, c["en"], c["es"], c["group"], status(cid), n_img[cid]])
    if a.handoff:
        with open(a.handoff, "w", encoding="utf-8", newline="\n") as f:
            w = csv.writer(f, lineterminator="\n")
            w.writerow(["id", "name_en", "name_es", "status"])
            for cid in final:
                if cid == NOT_FOOD:
                    continue
                c = conf.get(cid) or {"en": old_rows[cid]["en_name"], "es": old_rows[cid]["es_name_hint"]}
                w.writerow([cid, c["en"], c["es"], status(cid)])
    print("written", a.out)


if __name__ == "__main__":
    main()
