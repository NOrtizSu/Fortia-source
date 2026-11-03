# SPDX-License-Identifier: AGPL-3.0-or-later
"""Food boxes of Open Images V7 (human-drawn boxes; annotations CC BY 4.0 by Google, photos CC BY 2.0).

Reads oidv6-train-annotations-bbox.csv, validation- and test-annotations-bbox.csv (from
https://storage.googleapis.com/openimages/), keeps the boxes of the boxable food/drink classes below and writes
<work>/oi/boxes_food.parquet (ImageID, Subset, label, XMin, XMax, YMin, YMax, IsGroupOf, IsDepiction, IsOccluded,
IsTruncated). Live animals ("Fish", "Turkey", "Chicken", "Crab"... as animals) and empty tableware are not food
items, so they are not listed (Crab, Lobster, Shrimp, Oyster, Squid are kept: in Open Images they are mostly food).

usage: python -I oi_boxes.py --work D:/FoodML
"""
import argparse
import os

import pandas as pd

FOOD_BOX_CLASSES = [
    "Apple", "Artichoke", "Bagel", "Baked goods", "Banana", "Beer", "Bell pepper", "Bread", "Broccoli", "Burrito",
    "Cabbage", "Cake", "Candy", "Cantaloupe", "Carrot", "Cheese", "Cocktail", "Coconut", "Coffee (drink)", "Cookie",
    "Crab", "Cream", "Croissant", "Cucumber", "Dairy Product", "Dessert", "Doughnut", "Drink", "Egg", "Fast food",
    "Food", "French fries", "Fruit", "Garden Asparagus", "Grape", "Grapefruit", "Guacamole", "Hamburger",
    "Honeycomb", "Hot dog", "Ice cream", "Juice", "Lemon (plant)", "Lobster", "Mango", "Milk", "Muffin", "Mushroom",
    "Orange (fruit)", "Oyster", "Pancake", "Pasta", "Pastry", "Peach", "Pear", "Pineapple", "Pizza", "Pomegranate",
    "Popcorn", "Potato", "Pretzel", "Pumpkin", "Radish", "Salad", "Sandwich", "Seafood", "Shellfish", "Shrimp",
    "Snack", "Squash (Plant)", "Squid", "Strawberry", "Submarine sandwich", "Sushi", "Taco", "Tart", "Tea", "Tomato",
    "Vegetable", "Waffle", "Watermelon", "Wine", "Winter melon", "Zucchini",
]
GENERIC = {"Food", "Fast food", "Baked goods", "Dessert", "Snack", "Fruit", "Vegetable", "Seafood", "Shellfish",
           "Dairy Product", "Drink"}
FILES = {"train": "oidv6-train-annotations-bbox.csv", "validation": "validation-annotations-bbox.csv",
         "test": "test-annotations-bbox.csv"}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--work", default="D:/FoodML")
    a = ap.parse_args()
    d = os.path.join(a.work, "data", "oi_boxes")
    desc = pd.read_csv(os.path.join(d, "oidv7-class-descriptions-boxable.csv"))
    name2mid = dict(zip(desc.DisplayName, desc.LabelName))
    missing = [n for n in FOOD_BOX_CLASSES if n not in name2mid]
    assert not missing, missing
    mid2name = {name2mid[n]: n for n in FOOD_BOX_CLASSES}
    parts = []
    cols = ["ImageID", "LabelName", "XMin", "XMax", "YMin", "YMax", "IsOccluded", "IsTruncated", "IsGroupOf",
            "IsDepiction"]
    for subset, fn in FILES.items():
        for ch in pd.read_csv(os.path.join(d, fn), usecols=cols, chunksize=2_000_000):
            ch = ch[ch.LabelName.isin(mid2name)].copy()
            ch["Subset"] = subset
            parts.append(ch)
        print(subset, sum(len(p) for p in parts), flush=True)
    df = pd.concat(parts, ignore_index=True)
    df["label"] = df.LabelName.map(mid2name)
    df = df.drop(columns=["LabelName"])
    out = os.path.join(a.work, "oi")
    df.to_parquet(os.path.join(out, "boxes_food.parquet"), index=False)
    print(len(df), "boxes on", df.ImageID.nunique(), "photos")
    print(df.label.value_counts().to_string())


if __name__ == "__main__":
    main()
