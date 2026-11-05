# SPDX-License-Identifier: AGPL-3.0-or-later
"""A retrieval test set from Open Food Facts front photos (evaluation only; the photos are never used to train).

"Products you already scanned" (1.8.0): the app keeps the Open Food Facts front photo of every barcode product the
user scanned; a later photo of a package is matched against them by an image embedding. To measure that offline:

- `fetch`: popular products (search.openfoodfacts.org, sorted by scans) whose front was selected from at least two
  different uploads (two languages' fronts with different `imgid`, uploaded by different contributors or on
  different days). For each, the **gallery** photo is one selected front (`front_<lang>.<rev>.400.jpg`, cropped by
  the contributors: what the app stores) and the **query** is the *raw* upload behind another language's front
  (`<imgid>.400.jpg`, uncropped, turned upright with that selection's angle): a different photo of the same
  product, like the user's own. Products with only one front become distractors in the gallery.
- Writes `<out>/products.csv` (code, name, brands, gallery file, query file, query_sel file) and the photos.

Open Food Facts product photos are CC BY-SA 3.0 (their contributors); they are downloaded for this measurement,
kept outside the repository and never redistributed or used to train a model.

usage: python off_retrieval.py fetch --out D:/FoodML/off_eval --pages 40
"""
import argparse
import csv
import io
import json
import os
import time
from concurrent.futures import ThreadPoolExecutor

import requests
from PIL import Image

UA = {"User-Agent": "Fortia-model-eval/3 (enjoyyourtime.online@gmail.com)"}
SEARCH = "https://search.openfoodfacts.org/search"
IMG = "https://images.openfoodfacts.org/images/products/"


def folder(code):
    """Open Food Facts image folder of a barcode (codes are padded to 13 digits; long ones split 3/3/3/rest)."""
    c = code if len(code) >= 13 else code.zfill(13) if len(code) > 8 else code
    if len(c) <= 8:
        return c
    return f"{c[0:3]}/{c[3:6]}/{c[6:9]}/{c[9:]}"


def fronts_of(p):
    """[(lang, imgid, rev, angle, uploader, uploaded_t)] of the product's selected fronts."""
    imgs = p.get("images") or {}
    out = []
    for k, v in imgs.items():
        if not k.startswith("front_") or not isinstance(v, dict) or "imgid" not in v or "rev" not in v:
            continue
        raw = imgs.get(str(v["imgid"])) or {}
        try:
            angle = int(float(v.get("angle") or 0))
        except (TypeError, ValueError):
            angle = 0
        try:
            t = int(float(raw.get("uploaded_t") or 0))
        except (TypeError, ValueError):
            t = 0
        out.append((k[6:], str(v["imgid"]), str(v["rev"]), angle, str(raw.get("uploader") or ""), t))
    return out


def pick_pair(fr):
    """Two fronts from different uploads by different contributors (or days apart); None if there is none."""
    by_img = {}
    for f in fr:
        by_img.setdefault(f[1], f)
    u = list(by_img.values())
    for i in range(len(u)):
        for j in range(len(u)):
            if i == j:
                continue
            a, b = u[i], u[j]
            anon = a[4].startswith("openfoodfacts-contributors") and b[4].startswith("openfoodfacts-contributors")
            if (a[4] != b[4] and not anon) or abs(a[5] - b[5]) > 86400:
                return a, b
    return None


def get(url, tries=3):
    for k in range(tries):
        try:
            r = requests.get(url, headers=UA, timeout=60)
            if r.status_code == 200:
                return r
            if r.status_code == 404:
                return None
        except requests.RequestException:
            pass
        time.sleep(3 * (k + 1))
    return None


def save_jpeg(url, path, angle=0):
    if os.path.exists(path):
        return True
    r = get(url)
    if r is None or not r.headers.get("content-type", "").startswith("image"):
        return False
    try:
        im = Image.open(io.BytesIO(r.content)).convert("RGB")
    except Exception:
        return False
    if angle % 360:
        im = im.rotate(-angle, expand=True)  # OFF angles are clockwise
    im.save(path, "JPEG", quality=92)
    return True


def search_pages(a):
    """The search hits (cached in <out>/search.jsonl: the search API is slow and rate-limited)."""
    path = os.path.join(a.out, "search.jsonl")
    if os.path.exists(path):
        with open(path, encoding="utf-8") as f:
            return [json.loads(line) for line in f]
    hits = []
    for page in range(1, a.pages + 1):
        r = get(f"{SEARCH}?q=&page_size=100&page={page}&sort_by=-unique_scans_n&fields=code,product_name,brands,images")
        if r is None:
            print("page", page, "failed", flush=True)
            continue
        hits += r.json().get("hits", [])
        print("page", page, len(hits), flush=True)
        time.sleep(a.pause)
    with open(path, "w", encoding="utf-8") as f:
        for h in hits:
            f.write(json.dumps(h) + "\n")
    return hits


def fetch(a):
    os.makedirs(os.path.join(a.out, "img"), exist_ok=True)
    rows, seen = [], set()
    for p in search_pages(a):
        code = str(p.get("code") or "")
        if not code or code in seen:
            continue
        seen.add(code)
        fr = fronts_of(p)
        if not fr:
            continue
        pair = pick_pair(fr)
        g = pair[0] if pair else fr[0]
        rows.append({"code": code, "name": (p.get("product_name") or "")[:80],
                     "brands": (p.get("brands") or "")[:60] if isinstance(p.get("brands"), str) else "",
                     "g": g, "q": pair[1] if pair else None})
    # the most scanned products first; at most --max-pairs with a query and --max-distractors without
    pairs = [x for x in rows if x["q"]][:a.max_pairs]
    rest = [x for x in rows if not x["q"]][:a.max_distractors]
    rows = pairs + rest
    print("products", len(rows), "with a second front", len(pairs), flush=True)

    def dl(x):
        f = folder(x["code"])
        g = x["g"]
        gfile = f"{x['code']}_g.jpg"
        ok = save_jpeg(f"{IMG}{f}/front_{g[0]}.{g[2]}.400.jpg", os.path.join(a.out, "img", gfile))
        qfile = qsel = ""
        if ok and x["q"]:
            q = x["q"]
            if save_jpeg(f"{IMG}{f}/{q[1]}.400.jpg", os.path.join(a.out, "img", f"{x['code']}_q.jpg"), q[3]):
                qfile = f"{x['code']}_q.jpg"
            if a.with_sel and save_jpeg(f"{IMG}{f}/front_{q[0]}.{q[2]}.400.jpg",
                                        os.path.join(a.out, "img", f"{x['code']}_s.jpg")):
                qsel = f"{x['code']}_s.jpg"
        return {"code": x["code"], "name": x["name"], "brands": x["brands"], "gallery": gfile if ok else "",
                "query": qfile, "query_sel": qsel}

    with ThreadPoolExecutor(a.threads) as pool:
        out = []
        for k, o in enumerate(pool.map(dl, rows)):
            out.append(o)
            if k % 200 == 0:
                print("downloaded", k, len(rows), flush=True)
    out = [o for o in out if o["gallery"]]
    with open(os.path.join(a.out, "products.csv"), "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=list(out[0].keys()))
        w.writeheader()
        w.writerows(out)
    print("products", len(out), "with a query photo", sum(1 for o in out if o["query"]), flush=True)


def main():
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)
    f = sub.add_parser("fetch")
    f.add_argument("--out", default="D:/FoodML/off_eval")
    f.add_argument("--pages", type=int, default=40)
    f.add_argument("--pause", type=float, default=3.0)
    f.add_argument("--threads", type=int, default=8)
    f.add_argument("--max-pairs", type=int, default=900)
    f.add_argument("--max-distractors", type=int, default=1200)
    f.add_argument("--with-sel", action="store_true", help="also the cropped front behind the query photo")
    a = ap.parse_args()
    if a.cmd == "fetch":
        fetch(a)


if __name__ == "__main__":
    main()
