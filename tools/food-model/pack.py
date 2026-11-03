# SPDX-License-Identifier: AGPL-3.0-or-later
"""Pack each split's photos into one file so training doesn't seek through ~150k small files.

For <dataset>/{train,val}.csv writes <split>.bin (the photos one after another as JPEG, short side resized to
--short px, at least the eval resize: 256 for v1, 292 for v2; quality 90) and <split>.idx.npy (byte offsets,
n + 1); --reuse copies the training photos of an earlier pack as they are. train.py reads them when
present (the OS file cache keeps the whole pack in memory after the first pass). The test split is left as
files: evaluate.py and parity.py read the original copies.

usage: python pack.py --work D:/FoodML [--dataset dataset]
"""
import argparse
import io
import os
import sys
from concurrent.futures import ThreadPoolExecutor

import numpy as np
import pandas as pd
from PIL import Image

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from food_items import fast_path  # noqa: E402

SHORT = 256


def encode(path, short=SHORT):
    with Image.open(fast_path(path)) as im:
        im = im.convert("RGB")
    w, h = im.size
    if min(w, h) > short:
        size = (short, int(short * h / w)) if w <= h else (int(short * w / h), short)
        im = im.resize(size, Image.BILINEAR)
    b = io.BytesIO()
    im.save(b, "JPEG", quality=90)
    return b.getvalue()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--work", default="D:/FoodML")
    ap.add_argument("--dataset", default="dataset")
    ap.add_argument("--splits", default="train,val")
    ap.add_argument("--threads", type=int, default=8)
    ap.add_argument("--short", type=int, default=SHORT, help="short side in the pack (>= the eval resize)")
    ap.add_argument("--reuse", default="", help="an earlier dataset folder with a train pack: its photos are "
                                                "copied from that pack (as encoded there) instead of re-read")
    a = ap.parse_args()
    d = os.path.join(a.work, a.dataset)
    old = {}
    if a.reuse:
        oc = pd.read_csv(os.path.join(a.reuse, "train.csv"), dtype={"source_id": str})
        oo = np.load(os.path.join(a.reuse, "train.idx.npy"))
        mm = np.memmap(os.path.join(a.reuse, "train.bin"), dtype=np.uint8, mode="r")
        old = {(s, i): (int(oo[k]), int(oo[k + 1])) for k, (s, i) in enumerate(zip(oc.source, oc.source_id))}

    def get(row):
        span = old.get((row[1], row[2]))
        if span is not None:
            return mm[span[0]:span[1]].tobytes()
        return encode(row[0], a.short)

    for split in a.splits.split(","):
        df = pd.read_csv(os.path.join(d, f"{split}.csv"), dtype={"source_id": str})
        rows = list(zip(df.path, df.source, df.source_id))
        offsets = [0]
        with open(os.path.join(d, f"{split}.bin.tmp"), "wb") as f, ThreadPoolExecutor(a.threads) as pool:
            for i, b in enumerate(pool.map(get if split == "train" else (lambda r: encode(r[0], a.short)), rows)):
                f.write(b)
                offsets.append(offsets[-1] + len(b))
                if i % 10000 == 0:
                    print(split, i, len(rows), flush=True)
        os.replace(os.path.join(d, f"{split}.bin.tmp"), os.path.join(d, f"{split}.bin"))
        np.save(os.path.join(d, f"{split}.idx.npy"), np.array(offsets, dtype=np.int64))
        print(split, len(rows), "photos,", offsets[-1] // 2**20, "MB", flush=True)


if __name__ == "__main__":
    main()
