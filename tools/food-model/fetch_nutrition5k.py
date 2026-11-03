# SPDX-License-Identifier: AGPL-3.0-or-later
"""Download the Nutrition5k overhead RGB photos and metadata (Google, CC BY 4.0).

Only `metadata/*.csv`, `dish_ids/splits/rgb_{train,test}_ids.txt` and `imagery/realsense_overhead/<dish>/rgb.png`
(the 640 x 480 top-down photo of each dish); no videos, no depth. Public bucket, anonymous HTTPS.

Licence (README of github.com/google-research-datasets/Nutrition5k): "We release all Nutrition5k data under the
Creative Commons V4.0 license" (links to CC BY 4.0). Cite: Thames et al., "Nutrition5k", CVPR 2021.

usage: python fetch_nutrition5k.py --work D:/FoodML [--threads 8]
"""
import argparse
import os
import time
import urllib.request
from concurrent.futures import ThreadPoolExecutor

BASE = "https://storage.googleapis.com/nutrition5k_dataset/nutrition5k_dataset/"
META = ["metadata/dish_metadata_cafe1.csv", "metadata/dish_metadata_cafe2.csv", "metadata/ingredients_metadata.csv",
        "dish_ids/splits/rgb_train_ids.txt", "dish_ids/splits/rgb_test_ids.txt"]
UA = "Fortia-food-model/2.0 (enjoyyourtime.online@gmail.com)"


def get(url, dst, tries=4):
    if os.path.exists(dst) and os.path.getsize(dst) > 0:
        return "cached"
    for k in range(tries):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": UA})
            with urllib.request.urlopen(req, timeout=60) as r:
                data = r.read()
            with open(dst + ".tmp", "wb") as f:
                f.write(data)
            os.replace(dst + ".tmp", dst)
            return "ok"
        except urllib.error.HTTPError as e:
            if e.code == 404:
                return "missing"
            time.sleep(2 * (k + 1))
        except Exception:
            time.sleep(2 * (k + 1))
    return "failed"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--work", default="D:/FoodML")
    ap.add_argument("--threads", type=int, default=8)
    a = ap.parse_args()
    root = os.path.join(a.work, "dl", "n5k")
    os.makedirs(os.path.join(root, "meta"), exist_ok=True)
    os.makedirs(os.path.join(root, "rgb"), exist_ok=True)
    for m in META:
        print(m, get(BASE + m, os.path.join(root, "meta", os.path.basename(m))), flush=True)
    ids = []
    for s in ("rgb_train_ids.txt", "rgb_test_ids.txt"):
        with open(os.path.join(root, "meta", s)) as f:
            ids += [x.strip() for x in f if x.strip()]
    ids = sorted(set(ids))
    print(len(ids), "dishes", flush=True)
    jobs = [(BASE + f"imagery/realsense_overhead/{d}/rgb.png", os.path.join(root, "rgb", d + ".png")) for d in ids]
    stats = {}
    with ThreadPoolExecutor(a.threads) as pool:
        for i, st in enumerate(pool.map(lambda j: get(*j), jobs)):
            stats[st] = stats.get(st, 0) + 1
            if i % 250 == 0:
                print(i, stats, flush=True)
    print("done", stats, flush=True)


if __name__ == "__main__":
    main()
