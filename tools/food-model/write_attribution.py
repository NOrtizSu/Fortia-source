# SPDX-License-Identifier: AGPL-3.0-or-later
"""Write the attribution manifest of the training photos into docs/third-party/food-model/.

- images.csv.gz: one row per photo used (train, val or test): split, class, source, id, author, licence and
  the source page (Open Images: the Flickr photo page; Commons: the file page). Kept < 5 MB by writing the
  page as a short form: `flickr:<user>/<photo id>` = https://www.flickr.com/photos/<user>/<photo id> and
  `commons:<File name>` = https://commons.wikimedia.org/wiki/File:<File name>.
- sources.csv: photos per class and source; licences.csv: photos per licence.

usage: python write_attribution.py --work D:/FoodML [--dataset dataset] [--out ../../docs/third-party/food-model]
"""
import argparse
import gzip
import io
import os

import pandas as pd

HERE = os.path.dirname(os.path.abspath(__file__))


def short_page(row):
    url = str(row.url or "")
    if row.source == "openimages":
        for p in ("https://www.flickr.com/photos/", "http://www.flickr.com/photos/"):
            if url.startswith(p):
                return "flickr:" + url[len(p):].rstrip("/")
        return url
    t = str(row.source_id)
    return "commons:" + (t[5:] if t.startswith("File:") else t)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--work", default="D:/FoodML")
    ap.add_argument("--dataset", default="dataset")
    ap.add_argument("--out", default=os.path.join(HERE, "..", "..", "docs", "third-party", "food-model"))
    a = ap.parse_args()
    df = pd.read_csv(os.path.join(a.work, a.dataset, "attribution.csv"), dtype=str).fillna("")
    df["page"] = [short_page(r) for r in df.itertuples(index=False)]
    df["licence"] = [("CC BY 2.0" if s == "openimages" else lic) for s, lic in zip(df.source, df.licence)]
    df["id"] = [sid if s == "openimages" else "" for s, sid in zip(df.source, df.source_id)]
    out = df[["split", "class", "source", "id", "page", "author", "licence"]].sort_values(
        ["source", "class", "page"])
    os.makedirs(a.out, exist_ok=True)
    buf = io.StringIO()
    out.to_csv(buf, index=False, lineterminator="\n")
    with gzip.GzipFile(os.path.join(a.out, "images.csv.gz"), "wb", compresslevel=9, mtime=0) as f:
        f.write(buf.getvalue().encode("utf-8"))
    src = df.groupby(["class", "source"]).size().unstack(fill_value=0).reset_index()
    src.to_csv(os.path.join(a.out, "sources.csv"), index=False, lineterminator="\n")
    lic = df.groupby(["source", "licence"]).size().reset_index(name="photos").sort_values(
        ["source", "photos"], ascending=[True, False])
    lic.to_csv(os.path.join(a.out, "licences.csv"), index=False, lineterminator="\n")
    size = os.path.getsize(os.path.join(a.out, "images.csv.gz"))
    print(len(out), "photos;", size // 1024, "KB;", df.source.value_counts().to_dict())


if __name__ == "__main__":
    main()
