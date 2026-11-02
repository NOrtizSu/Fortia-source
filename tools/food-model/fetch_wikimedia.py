# SPDX-License-Identifier: AGPL-3.0-or-later
"""Collect freely licensed food photos from Wikimedia Commons categories.

For every class in classes_config.py with `wikimedia` categories, walks each category (and its
subcategories down to the configured depth, skipping subcategories whose names look like non-food:
restaurants, shops, signs, art...), asks the API for image info + licence metadata, keeps only files
whose machine-readable licence is CC0, public domain, CC BY or CC BY-SA, downloads a ~500 px thumbnail
and stores it resized (short side 320 px, JPEG q90).

Etiquette (https://www.mediawiki.org/wiki/API:Etiquette): descriptive User-Agent, API requests in
series with maxlag=5, thumbnails from a standard width, few parallel downloads, back off on 429/503.
API responses are cached under <work>/wm/cache so reruns don't hit the API again.

Two phases: --plan (API only: <work>/wm/candidates.jsonl, how many free photos each class has) and
--download (thumbnails); both by default.

Output: <work>/wm/manifest.jsonl (one line per kept file: class, title, page, author, licence...)
        <work>/images/wm/<sha1>.jpg

usage: python fetch_wikimedia.py --work D:/FoodML [--plan | --download] [--threads 4]
"""
import argparse
import concurrent.futures as cf
import hashlib
import html
import io
import json
import os
import re
import sys
import threading
import time

import requests
from PIL import Image, ImageOps

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from classes_config import CLASSES  # noqa: E402

API = "https://commons.wikimedia.org/w/api.php"
UA = "Fortia-food-model/1.0 (enjoyyourtime.online@gmail.com) python-requests"
THUMB_WIDTH = 500  # a standard Commons thumbnail step
SHORT_SIDE = 320
LICENCE_OK = re.compile(r"^(cc0|pd|public domain|cc[- ]by(-sa)?[- ]\d|cc[- ]by(-sa)?$)", re.I)
SUBCAT_SKIP = re.compile(
    r"restaurant|shop|store|market|stall|sign|menu|logo|advert|poster|packag|label|brand|festival|"
    r"museum|statue|sculpture|painting|drawing|illustration|art\b|artwork|stamp|postcard|cookbook|"
    r"recipe book|book|video|audio|sound|map|people|chef|cook\b|cooks\b|eating|contest|competition|"
    r"factory|industry|production|plantation|field|tree|trees|flower|blossom|plant\b|plants\b|seed|"
    r"cultivar|farm|harvest|machine|equipment|utensil|tool|ingredient|by country|in art|history|"
    r"wikipedia|wiki loves|quality images|featured|valued|svg|diagram|text|toy|replica|fake|model",
    re.I)

session = requests.Session()
session.headers["User-Agent"] = UA
api_lock = threading.Lock()
_last_api = [0.0]


def api(params, cache_dir):
    params = dict(params, format="json", formatversion=2, maxlag=5)
    key = hashlib.sha1(json.dumps(params, sort_keys=True).encode()).hexdigest()
    path = os.path.join(cache_dir, key[:2], key + ".json")
    if os.path.exists(path):
        with open(path, "rb") as f:
            return json.loads(f.read())
    for attempt in range(8):
        with api_lock:
            wait = 0.2 - (time.time() - _last_api[0])
            if wait > 0:
                time.sleep(wait)
            try:
                r = session.post(API, data=params, timeout=60)
            except requests.RequestException:
                r = None
            _last_api[0] = time.time()
        if r is not None and r.status_code == 200:
            data = r.json()
            if data.get("error", {}).get("code") == "maxlag":
                time.sleep(5 * (attempt + 1))
                continue
            os.makedirs(os.path.dirname(path), exist_ok=True)
            with open(path + ".tmp", "wb") as f:
                f.write(json.dumps(data).encode())
            os.replace(path + ".tmp", path)
            return data
        print("API retry", attempt, getattr(r, "status_code", None), file=sys.stderr, flush=True)
        time.sleep(min(60, 3 * 2 ** attempt))
    raise RuntimeError(f"API failed: {params}")


# for single foods (rank 1-2), dish subcategories mostly show other dishes that merely contain the food
INGREDIENT_SUBCAT_SKIP = re.compile(r"dish|soup|salad|cuisine|recipe|cake|pie|sauce|jam|juice|drink", re.I)


def category_files(cat, depth, cache_dir, seen_cats, found, level=0, ingredient=False):
    """Adds to `found` (title -> shallowest level) the files of a category and its subcategories to `depth`."""
    subcats = []
    cont = {}
    while True:
        d = api({"action": "query", "list": "categorymembers", "cmtitle": cat, "cmtype": "file|subcat",
                 "cmlimit": 500, **cont}, cache_dir)
        for m in d.get("query", {}).get("categorymembers", []):
            if m["ns"] == 6:
                found[m["title"]] = min(level, found.get(m["title"], level))
            elif m["ns"] == 14:
                subcats.append(m["title"])
        if "continue" not in d:
            break
        cont = d["continue"]
    if depth > 0:
        for sc in subcats:
            name = sc.split(":", 1)[1]
            if sc in seen_cats or SUBCAT_SKIP.search(name) or (ingredient and INGREDIENT_SUBCAT_SKIP.search(name)):
                continue
            seen_cats.add(sc)
            category_files(sc, depth - 1, cache_dir, seen_cats, found, level + 1, ingredient)


def strip_html(s):
    s = re.sub(r"<[^>]+>", "", s or "")
    return re.sub(r"\s+", " ", html.unescape(s)).strip()


_page_cache = {}


def _cached_pages(cache_dir):
    """title -> imageinfo page of every imageinfo answer already in the cache (asked once per title)."""
    if not _page_cache:
        for sub in os.listdir(cache_dir) if os.path.isdir(cache_dir) else []:
            for fn in os.listdir(os.path.join(cache_dir, sub)):
                if not fn.endswith(".json"):
                    continue
                with open(os.path.join(cache_dir, sub, fn), "rb") as f:
                    d = json.loads(f.read())
                for p in d.get("query", {}).get("pages", []):
                    if "imageinfo" in p:
                        _page_cache[p["title"]] = p
        _page_cache.setdefault("", None)
    return _page_cache


def file_infos(titles, cache_dir):
    cache = _cached_pages(cache_dir)
    todo = [t for t in titles if t not in cache]
    for i in range(0, len(todo), 50):
        batch = todo[i:i + 50]
        d = api({"action": "query", "prop": "imageinfo", "titles": "|".join(batch),
                 "iiprop": "url|size|mime|sha1|extmetadata", "iiurlwidth": THUMB_WIDTH,
                 "iiextmetadatafilter": "LicenseShortName|License|LicenseUrl|Artist|Credit"}, cache_dir)
        norm = {n["to"]: n["from"] for n in d.get("query", {}).get("normalized", [])}
        for p in d.get("query", {}).get("pages", []):
            cache[norm.get(p["title"], p["title"])] = cache[p["title"]] = p
    out = []
    for t in titles:
        p = cache.get(t)
        if p:
            ii = (p.get("imageinfo") or [None])[0]
            if not ii:
                continue
            em = ii.get("extmetadata", {})
            out.append({
                "title": p["title"], "page": ii.get("descriptionurl"), "thumb": ii.get("thumburl"),
                "width": ii.get("width"), "height": ii.get("height"), "mime": ii.get("mime"),
                "sha1": ii.get("sha1"),
                "licence_code": em.get("License", {}).get("value", ""),
                "licence": em.get("LicenseShortName", {}).get("value", ""),
                "licence_url": em.get("LicenseUrl", {}).get("value", ""),
                "author": strip_html(em.get("Artist", {}).get("value", ""))[:200],
            })
    return out


def licence_ok(info):
    code = (info["licence_code"] or "").strip().lower()
    short = (info["licence"] or "").strip().lower()
    if not (LICENCE_OK.match(code) or LICENCE_OK.match(short)):
        return False
    bad = ("nc", "nd", "fair use", "non-commercial", "noncommercial", "no derivatives")
    return not any(re.search(rf"\b{b}\b", code + " " + short) for b in bad)


def download(info, img_dir):
    path = os.path.join(img_dir, info["sha1"] + ".jpg")
    if os.path.exists(path):
        return True
    for attempt in range(6):
        try:
            r = session.get(info["thumb"], timeout=60)
        except requests.RequestException:
            r = None
        if r is not None and r.status_code == 200:
            try:
                im = Image.open(io.BytesIO(r.content))
                im = ImageOps.exif_transpose(im).convert("RGB")
            except Exception:
                return False
            w, h = im.size
            s = SHORT_SIDE / min(w, h)
            if s < 1:
                im = im.resize((round(w * s), round(h * s)), Image.BICUBIC)
            tmp = path + ".tmp"
            im.save(tmp, "JPEG", quality=90)
            os.replace(tmp, path)
            return True
        if r is not None and r.status_code in (403, 404):
            return False
        time.sleep(min(120, 5 * 2 ** attempt))
    return False


def plan(args, root, cache_dir):
    """Phase 1: API only. Free-licence photos per class -> candidates.jsonl."""
    import pandas as pd
    oi_n = {}
    for name in ("meta.parquet", "assign.parquet"):  # Open Images photos downloaded (else assigned)
        p = os.path.join(args.work, "oi", name)
        if os.path.exists(p):
            oi_n = pd.read_parquet(p).groupby("cls").size().to_dict()
            break
    per_class, owners = {}, {}
    for c in CLASSES:
        cats = c.get("wikimedia") or []
        if not cats:
            continue
        found, seen = {}, set()
        for spec in cats:
            name, _, depth = spec.partition("|")
            cat = "Category:" + name
            seen.add(cat)
            category_files(cat, int(depth or 1), cache_dir, seen, found, ingredient=c["rank"] <= 2)
        titles = list(found)
        infos = [i for i in file_infos(titles, cache_dir)
                 if i["thumb"] and i["sha1"] and (i["mime"] in ("image/jpeg", "image/png", "image/webp"))
                 and min(i["width"] or 0, i["height"] or 0) >= 240]
        ok = [i for i in infos if licence_ok(i)]
        per_class[c["id"]] = ok
        for i in ok:
            # (rank, -level): the more specific class wins, then the one whose own category holds the file
            owners.setdefault(i["sha1"], {})[c["id"]] = (c["rank"], -found.get(i["title"], 9))
        print(f"{c['id']}: {len(titles)} files, {len(infos)} photos, {len(ok)} free", flush=True)

    def winner(sha1):
        o = sorted(owners[sha1].items(), key=lambda kv: kv[1], reverse=True)
        return o[0][0] if len(o) == 1 or o[0][1] > o[1][1] else None

    n = 0
    with open(os.path.join(root, "candidates.jsonl"), "w", encoding="utf-8", newline="\n") as f:
        for cid, ok in per_class.items():
            # a photo filed under several of our classes goes to the most specific one; a tie: not used
            ok = [i for i in ok if winner(i["sha1"]) == cid]
            cap = min(args.max_per_class, max(MIN_WM, CLASS_TARGET - oi_n.get(cid, 0)))
            for i in ok[:cap]:
                f.write(json.dumps(dict(i, **{"class": cid}), ensure_ascii=False) + "\n")
                n += 1
    print("candidates:", n, flush=True)


def fetch_all(args, root):
    """Phase 2: download the thumbnails of candidates.jsonl -> manifest.jsonl."""
    img_dir = os.path.join(args.work, "images", "wm")
    os.makedirs(img_dir, exist_ok=True)
    with open(os.path.join(root, "candidates.jsonl"), encoding="utf-8") as f:
        cands = [json.loads(line) for line in f]
    # thin classes first: the photos that bring a class to FIRST_TIER (with its Open Images photos) come
    # before the rest, so a partial run still helps every class
    import pandas as pd
    p = os.path.join(args.work, "oi", "meta.parquet")
    oi_n = pd.read_parquet(p).groupby("cls").size().to_dict() if os.path.exists(p) else {}
    seen = {}
    for i in cands:
        k = seen[i["class"]] = seen.get(i["class"], 0) + 1
        i["_tier"] = 0 if k + oi_n.get(i["class"], 0) <= FIRST_TIER else 1
    cands.sort(key=lambda i: i["_tier"])
    for i in cands:
        del i["_tier"]
    kept = []
    with cf.ThreadPoolExecutor(args.threads) as pool:
        for k, (i, ok) in enumerate(zip(cands, pool.map(lambda i: download(i, img_dir), cands))):
            if ok:
                kept.append(i)
            if k % 2000 == 0:
                print(f"{k}/{len(cands)} downloaded {len(kept)}", flush=True)
    with open(os.path.join(root, "manifest.jsonl"), "w", encoding="utf-8", newline="\n") as f:
        for k in kept:
            f.write(json.dumps(k, ensure_ascii=False) + "\n")
    print("kept", len(kept), "of", len(cands), flush=True)


MIN_WM = 150         # always take at least this many Commons photos for a class (when they exist)
CLASS_TARGET = 1100  # otherwise only what Open Images doesn't cover
FIRST_TIER = 350     # download order: every class up to this many photos first


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--work", default="D:/FoodML")
    ap.add_argument("--threads", type=int, default=4)
    ap.add_argument("--max-per-class", type=int, default=800)
    ap.add_argument("--plan", action="store_true", help="only the API phase")
    ap.add_argument("--download", action="store_true", help="only the download phase")
    args = ap.parse_args()
    root = os.path.join(args.work, "wm")
    cache_dir = os.path.join(root, "cache")
    os.makedirs(root, exist_ok=True)
    if args.plan or not args.download:
        plan(args, root, cache_dir)
    if args.download or not args.plan:
        fetch_all(args, root)


if __name__ == "__main__":
    main()
