# SPDX-License-Identifier: AGPL-3.0-or-later
"""Write the attribution manifests of the training data into docs/third-party/food-model/.

- images.csv.gz: one row per classifier picture used (train, val or test; photos and item crops): split, class,
  source, id, author, licence and the source page. Kept small by writing the page in a short form:
  `flickr:<user>/<photo id>` = https://www.flickr.com/photos/<user>/<photo id>, `commons:<File name>` =
  https://commons.wikimedia.org/wiki/File:<File name>, `frb2022` = the Food Recognition Benchmark 2022 page
  (MyFoodRepo crops; their id is `<split letter><image>#x0,y0,x1,y1`, t = training, v = validation).
- sources.csv: pictures per class and source; licences.csv: pictures per source and licence.
- detector_photos.csv.gz (with --detector): the detector's photos (split, source, id).
- nutrition5k_dishes.csv.gz (with --nutrition5k): the Nutrition5k dishes of the grams head (dish id, split).

usage: python write_attribution.py --work D:/FoodML --dataset dataset_v2 [--detector E:/FoodML_ssd/det]
       [--nutrition5k] [--out ../../docs/third-party/food-model]
"""
import argparse
import gzip
import io
import os

import pandas as pd

HERE = os.path.dirname(os.path.abspath(__file__))


def short_page(row):
    url = str(row.url or "")
    if row.source in ("openimages", "openimages_box"):
        for p in ("https://www.flickr.com/photos/", "http://www.flickr.com/photos/"):
            if url.startswith(p):
                return "flickr:" + url[len(p):].rstrip("/")
        return url
    if row.source == "myfoodrepo":
        return "frb2022"
    t = str(row.source_id)
    return "commons:" + (t[5:] if t.startswith("File:") else t)


def short_frb(source_id):
    """training/012345.jpg#10,20,300,400 -> t012345#10,20,300,400"""
    split, _, rest = source_id.partition("/")
    image, _, box = rest.partition("#")
    return split[:1] + image.replace(".jpg", "") + "#" + box


def write_gz(df, path):
    buf = io.StringIO()
    df.to_csv(buf, index=False, lineterminator="\n")
    with gzip.GzipFile(path, "wb", compresslevel=9, mtime=0) as f:
        f.write(buf.getvalue().encode("utf-8"))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--work", default="D:/FoodML")
    ap.add_argument("--dataset", default="dataset_v2")
    ap.add_argument("--detector", default="", help="the YOLO dataset folder of build_detector_data.py")
    ap.add_argument("--nutrition5k", action="store_true")
    ap.add_argument("--out", default=os.path.join(HERE, "..", "..", "docs", "third-party", "food-model"))
    a = ap.parse_args()
    df = pd.read_csv(os.path.join(a.work, a.dataset, "attribution.csv"), dtype=str).fillna("")
    df["page"] = [short_page(r) for r in df.itertuples(index=False)]
    df["licence"] = [("CC BY 2.0" if s in ("openimages", "openimages_box") else lic)
                     for s, lic in zip(df.source, df.licence)]
    df["id"] = [("" if s == "wikimedia" else short_frb(sid) if s == "myfoodrepo" else sid)
                for s, sid in zip(df.source, df.source_id)]
    df["author"] = [("MyFoodRepo users" if s == "myfoodrepo" else a_) for s, a_ in zip(df.source, df.author)]
    out = df[["split", "class", "source", "id", "page", "author", "licence"]].sort_values(
        ["source", "class", "page", "id"])
    os.makedirs(a.out, exist_ok=True)
    write_gz(out, os.path.join(a.out, "images.csv.gz"))
    src = df.groupby(["class", "source"]).size().unstack(fill_value=0).reset_index()
    src.to_csv(os.path.join(a.out, "sources.csv"), index=False, lineterminator="\n")
    lic = df.groupby(["source", "licence"]).size().reset_index(name="pictures").sort_values(
        ["source", "pictures"], ascending=[True, False])
    lic.to_csv(os.path.join(a.out, "licences.csv"), index=False, lineterminator="\n")
    size = os.path.getsize(os.path.join(a.out, "images.csv.gz"))
    print(len(out), "pictures;", size // 1024, "KB;", df.source.value_counts().to_dict())
    if a.detector:
        rows = []
        for split in ("train", "val", "test"):
            for f in sorted(os.listdir(os.path.join(a.detector, "images", split))):
                stem = os.path.splitext(f)[0]
                src_, _, ident = stem.partition("_")
                rows.append((split, {"frb": "myfoodrepo", "oi": "openimages", "nf": "openimages"}[src_], ident,
                             "background (no food)" if src_ == "nf" else ""))
        det = pd.DataFrame(rows, columns=["split", "source", "id", "note"])
        write_gz(det, os.path.join(a.out, "detector_photos.csv.gz"))
        print(len(det), "detector photos", det.groupby(["split", "source"]).size().to_dict())
    if a.nutrition5k:
        meta = os.path.join(a.work, "dl", "n5k", "meta")
        have = {f[:-4] for f in os.listdir(os.path.join(a.work, "dl", "n5k", "rgb"))}
        rows = []
        for split, fn in (("train", "rgb_train_ids.txt"), ("test", "rgb_test_ids.txt")):
            rows += [(d, split) for d in open(os.path.join(meta, fn)).read().split() if d in have]
        n5 = pd.DataFrame(sorted(rows), columns=["dish_id", "split"])
        write_gz(n5, os.path.join(a.out, "nutrition5k_dishes.csv.gz"))
        print(len(n5), "Nutrition5k dishes")


if __name__ == "__main__":
    main()
