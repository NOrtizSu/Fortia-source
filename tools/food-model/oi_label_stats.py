# SPDX-License-Identifier: AGPL-3.0-or-later
"""Count human-verified positive Open Images V7 image-level labels per class.

Reads the oidv7-*-annotations-human-imagelabels.csv files (train/val/test) and writes, for every label:
positives per subset and how many of those images are also verified positive for a generic food label
(Food, Dish, Cuisine, Fruit, Vegetable, ...). The co-occurrence ratio is how food classes were found.

Output: <work>/oi/label_stats.csv and <work>/oi/positives.parquet (ImageID, Subset, LabelName of every
positive label of the candidate classes, used later by build_oi_index.py).

usage: python oi_label_stats.py --work D:/FoodML
"""
import argparse
import os

import pandas as pd

GENERIC_FOOD = {
    "/m/02wbm": "Food", "/m/02q08p0": "Dish", "/m/01ykh": "Cuisine", "/m/02xwb": "Fruit",
    "/m/0f4s2w": "Vegetable", "/m/052lwg6": "Baked goods", "/m/0270h": "Dessert", "/m/036qh8": "Produce",
    "/m/01_bhs": "Fast food", "/m/0271t": "Drink", "/m/07xgrh": "Ingredient", "/m/0p57p": "Recipe",
    "/m/04q6ng": "Comfort food", "/m/022tld": "Staple food", "/m/0krfg": "Meal", "/m/0cyz6w": "Whole food",
    "/m/07_lq": "Vegetarian food", "/m/052w_x": "Vegan nutrition", "/m/0hz4q": "Breakfast",
    "/m/0jfd5": "Lunch", "/m/0jffp": "Dinner", "/m/01ww8y": "Snack", "/m/06nwz": "Seafood",
    "/m/04scj": "Meat", "/m/0b3dyl": "Finger food", "/m/07l8p5": "Side dish", "/m/0c5yy": "Confectionery",
    "/m/012mj": "Alcoholic beverage", "/m/0dpj6t": "Non-alcoholic beverage", "/m/01hxyd": "Local food",
    "/m/04jdhxy": "Superfood", "/m/0h55b": "Junk food", "/m/05xnqg": "Street food",
}
SUBSETS = {"train": "oidv7-train-annotations-human-imagelabels.csv",
           "validation": "oidv7-val-annotations-human-imagelabels.csv",
           "test": "oidv7-test-annotations-human-imagelabels.csv"}


def read_positives(path):
    parts = []
    for chunk in pd.read_csv(path, usecols=["ImageID", "LabelName", "Confidence"], chunksize=5_000_000,
                             dtype={"ImageID": str, "LabelName": str, "Confidence": "float32"}):
        chunk = chunk[chunk.Confidence >= 1.0][["ImageID", "LabelName"]]
        chunk["LabelName"] = chunk["LabelName"].astype("category")
        parts.append(chunk)
    df = pd.concat(parts, ignore_index=True)
    df["LabelName"] = df["LabelName"].astype("category")
    return df


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--work", default="D:/FoodML")
    args = ap.parse_args()
    meta = os.path.join(args.work, "data", "oi_meta")
    out = os.path.join(args.work, "oi")
    os.makedirs(out, exist_ok=True)
    names = pd.read_csv(os.path.join(meta, "oidv7-class-descriptions.csv")).set_index("LabelName")["DisplayName"]

    rows = {}
    all_pos = []
    for subset, fn in SUBSETS.items():
        df = read_positives(os.path.join(meta, fn))
        df["Subset"] = subset
        food_imgs = set(df.loc[df.LabelName.isin(GENERIC_FOOD.keys()), "ImageID"])
        df["food"] = df.ImageID.isin(food_imgs)
        g = df.groupby("LabelName", observed=True).agg(n=("ImageID", "size"), n_food=("food", "sum"))
        for mid, r in g.iterrows():
            d = rows.setdefault(mid, {"mid": mid})
            d[f"{subset}_n"] = int(r.n)
            d[f"{subset}_food"] = int(r.n_food)
        all_pos.append(df[["ImageID", "LabelName", "Subset"]].astype({"LabelName": str}))
        print(subset, len(df), "positives", len(food_imgs), "food images", flush=True)

    st = pd.DataFrame(rows.values()).fillna(0)
    for c in st.columns:
        if c != "mid":
            st[c] = st[c].astype(int)
    st["name"] = st.mid.map(names)
    st["n"] = st[[f"{s}_n" for s in SUBSETS]].sum(axis=1)
    st["n_food"] = st[[f"{s}_food" for s in SUBSETS]].sum(axis=1)
    st["food_ratio"] = (st.n_food / st.n.clip(lower=1)).round(3)
    st = st.sort_values(["food_ratio", "n"], ascending=False)
    st.to_csv(os.path.join(out, "label_stats.csv"), index=False)

    pos = pd.concat(all_pos, ignore_index=True)
    pos["LabelName"] = pos.LabelName.astype("category")
    pos["Subset"] = pos.Subset.astype("category")
    pos.to_parquet(os.path.join(out, "positives.parquet"), index=False)
    print("wrote", len(st), "labels;", len(pos), "positives")


if __name__ == "__main__":
    main()
