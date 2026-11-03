# SPDX-License-Identifier: AGPL-3.0-or-later
"""Copy photo folders from the work disk (a hard disk) to a fast mirror (an SSD), see food_items.fast_path.

Files are read in the order they were written (NTFS file index ~ creation order), so the hard disk reads mostly
sequentially instead of seeking for every small photo; one reader thread, already-copied files are skipped.

usage: python -I mirror_photos.py D:/FoodML/images/oi E:/FoodML_ssd/images/oi [more src dst pairs]
"""
import os
import shutil
import sys
import time


def mirror(src, dst):
    os.makedirs(dst, exist_ok=True)
    have = set(os.listdir(dst))
    entries = [e for e in os.scandir(src) if e.is_file() and e.name not in have and not e.name.endswith(".tmp")]
    entries.sort(key=lambda e: e.inode())
    t = time.time()
    for k, e in enumerate(entries):
        shutil.copyfile(e.path, os.path.join(dst, e.name))
        if k % 5000 == 0:
            print(src, k, len(entries), f"{k / max(1e-6, time.time() - t):.0f}/s", flush=True)
    print(src, "done", len(entries), flush=True)


if __name__ == "__main__":
    args = sys.argv[1:]
    for i in range(0, len(args), 2):
        mirror(args[i], args[i + 1])
