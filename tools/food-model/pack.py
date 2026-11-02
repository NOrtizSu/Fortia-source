# SPDX-License-Identifier: AGPL-3.0-or-later
"""Pack each split's photos into one file so training doesn't seek through ~150k small files.

For <dataset>/{train,val}.csv writes <split>.bin (the photos one after another as JPEG, short side resized to
256 px = the eval resize, quality 90) and <split>.idx.npy (byte offsets, n + 1). train.py reads them when
present (the OS file cache keeps the whole pack in memory after the first pass). The test split is left as
files: evaluate.py and parity.py read the original copies.

usage: python pack.py --work D:/FoodML [--dataset dataset]
"""
import argparse
import io
import os
from concurrent.futures import ThreadPoolExecutor

import numpy as np
import pandas as pd
from PIL import Image

SHORT = 256


def encode(path):
    with Image.open(path) as im:
        im = im.convert("RGB")
    w, h = im.size
    if min(w, h) > SHORT:
        size = (SHORT, int(SHORT * h / w)) if w <= h else (int(SHORT * w / h), SHORT)
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
    a = ap.parse_args()
    d = os.path.join(a.work, a.dataset)
    for split in a.splits.split(","):
        paths = pd.read_csv(os.path.join(d, f"{split}.csv")).path.tolist()
        offsets = [0]
        with open(os.path.join(d, f"{split}.bin.tmp"), "wb") as f, ThreadPoolExecutor(a.threads) as pool:
            for i, b in enumerate(pool.map(encode, paths)):
                f.write(b)
                offsets.append(offsets[-1] + len(b))
                if i % 10000 == 0:
                    print(split, i, len(paths), flush=True)
        os.replace(os.path.join(d, f"{split}.bin.tmp"), os.path.join(d, f"{split}.bin"))
        np.save(os.path.join(d, f"{split}.idx.npy"), np.array(offsets, dtype=np.int64))
        print(split, len(paths), "photos,", offsets[-1] // 2**20, "MB", flush=True)


if __name__ == "__main__":
    main()
