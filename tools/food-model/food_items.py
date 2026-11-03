# SPDX-License-Identifier: AGPL-3.0-or-later
"""What counts as one "food item" for the detector, from the two box sources.

- MyFoodRepo / Food Recognition Benchmark 2022 (frb_index.py): every food on the plate is segmented. A sauce,
  dressing, herb, seed or garnish lying on another food is part of that food for the user (pasta with tomato
  sauce is one thing to log), so those classes give no box (TOPPINGS).
- Open Images (oi_boxes.py): the same object is often boxed at several levels (Strawberry, Fruit, Food) and a
  plate or table can carry one "Food" box around several dishes. `oi_items` keeps the most specific box of an
  object and removes boxes that contain two or more other boxes.

Shared by build_detector_data.py and build_dataset_v2.py (crops for the classifier).
"""
import os

import numpy as np

# Optional fast copies of the photo folders (the work disk is a hard disk; random reads crawl):
# FOODML_MIRROR="D:/FoodML/images=E:/FoodML_ssd/images;D:/FoodML/dl/frb/x=E:/FoodML_ssd/frb_x". Paths in the
# dataset files stay the canonical ones; fast_path() reads the copy when it exists.
_MIRROR = [tuple(os.path.normpath(x) for x in m.split("=", 1))
           for m in os.environ.get("FOODML_MIRROR", "").split(";") if "=" in m]


def fast_path(p):
    q = os.path.normpath(p)
    for src, dst in _MIRROR:
        if q.lower().startswith(src.lower() + os.sep):
            alt = dst + q[len(src):]
            if os.path.exists(alt):
                return alt
    return p


TOPPINGS = {
    "tomato-sauce", "sauce-cream", "balsamic-salad-dressing", "french-salad-dressing", "salad-dressing",
    "sauce-savoury", "sauce-pesto", "sauce-roast", "bolognaise-sauce", "capers", "basil", "parsley", "coriander",
    "chives", "sesame-seeds", "seeds", "pumpkin-seeds", "sunflower-seeds", "mixed-seeds", "pine-nuts", "linseeds",
    "chia-grains", "oil-vinegar-salad-dressing", "mustard", "mustard-dijon", "cream", "sour-cream",
    "thickened-cream-35", "croutons", "breadcrumbs-unspiced", "cocoa-powder", "butter-herb", "sauce-soya",
    "sauce-curry", "sauce-mushroom", "sauce-cocktail", "tartar-sauce", "sauce-sweet-sour", "sauce-sweet-salted-asian",
    "fruit-coulis", "maple-syrup-concentrate", "bouquet-garni", "ice-cubes", "balsamic-vinegar", "alfa-sprouts",
    "shoots", "mungbean-sprouts", "garlic", "salt", "pepper", "spices", "sugar", "olive-oil", "oil", "vinegar",
    "herbs", "dill", "rosemary", "thyme", "mint", "lemon-juice", "sauce", "gravy", "dressing", "whipped-cream",
    "sauce-hollandaise", "sauce-tomato", "pesto", "aioli", "herbs-provence", "sprouts", "bean-seeds",
    "sauce-carbonara", "dips", "cream-spinach",
}
OI_GENERIC = {"Food", "Fast food", "Baked goods", "Dessert", "Snack", "Fruit", "Vegetable", "Seafood", "Shellfish",
              "Dairy Product", "Drink"}


def iou_matrix(b):
    """b: [n, 4] x0, y0, x1, y1 -> IoU [n, n] and IoA[i, j] = |i ∩ j| / |i|."""
    x0 = np.maximum(b[:, None, 0], b[None, :, 0])
    y0 = np.maximum(b[:, None, 1], b[None, :, 1])
    x1 = np.minimum(b[:, None, 2], b[None, :, 2])
    y1 = np.minimum(b[:, None, 3], b[None, :, 3])
    inter = np.clip(x1 - x0, 0, None) * np.clip(y1 - y0, 0, None)
    area = (b[:, 2] - b[:, 0]) * (b[:, 3] - b[:, 1])
    union = area[:, None] + area[None, :] - inter
    return inter / np.maximum(union, 1e-9), inter / np.maximum(area[:, None], 1e-9)


def oi_items(boxes, labels):
    """Open Images boxes of one photo (normalised x0, y0, x1, y1; labels) -> indices of the item boxes."""
    n = len(boxes)
    if n == 0:
        return []
    b = np.asarray(boxes, dtype=np.float64)
    iou, ioa = iou_matrix(b)
    generic = np.array([lab in OI_GENERIC for lab in labels])
    keep = np.ones(n, bool)
    # the same object boxed twice: keep the specific label (or the first of two equal ones)
    for i in range(n):
        for j in range(n):
            if i != j and keep[i] and keep[j] and iou[i, j] >= 0.6:
                if generic[i] and not generic[j]:
                    keep[i] = False
                elif generic[i] == generic[j] and j < i:
                    keep[i] = False
    # containers: a box holding two or more other boxes (a plate, a table, a basket)
    for i in range(n):
        if not keep[i]:
            continue
        inside = [j for j in range(n) if j != i and keep[j] and ioa[j, i] >= 0.85]
        if len(inside) >= 2:
            keep[i] = False
        elif len(inside) == 1 and generic[i]:
            j = inside[0]
            area_i = (b[i, 2] - b[i, 0]) * (b[i, 3] - b[i, 1])
            area_j = (b[j, 2] - b[j, 0]) * (b[j, 3] - b[j, 1])
            if area_j >= 0.4 * area_i:  # a generic box around one specific item: the same item
                keep[i] = False
    return [i for i in range(n) if keep[i]]


def group_rule(boxes, mode="drop_container", inside=0.8, min_inside=2, min_cover=0.0):
    """The app's container rule (detector_contract.md, `group_suppression`) on the kept boxes, in score order.

    boxes: list of (x0, y0, x1, y1) in one coordinate frame. A box "contains" another when ≥ `inside` of the other
    box's area lies in it. For a box that contains ≥ `min_inside` other boxes (whose union covers ≥ `min_cover`
    of it, 0 = any):
      - drop_container: drop the big box, keep the boxes inside (a box around a mixed plate is the worse failure);
      - keep_container: keep the big box, drop the boxes inside it (a pile of one food = one item).
    Returns the indices kept, in their original order."""
    n = len(boxes)
    if mode in ("", "none", None) or n < 3:
        return list(range(n))
    b = np.asarray(boxes, dtype=np.float64)
    _, ioa = iou_matrix(b)  # ioa[i, j] = |i ∩ j| / |i|
    drop = set()
    for c in range(n):
        inner = [i for i in range(n) if i != c and ioa[i, c] >= inside]
        if len(inner) < min_inside:
            continue
        if min_cover > 0:
            # union of the inner boxes, measured on a coarse grid of the container
            g = 32
            xs = np.linspace(b[c, 0], b[c, 2], g, endpoint=False) + (b[c, 2] - b[c, 0]) / (2 * g)
            ys = np.linspace(b[c, 1], b[c, 3], g, endpoint=False) + (b[c, 3] - b[c, 1]) / (2 * g)
            gx, gy = np.meshgrid(xs, ys)
            cov = np.zeros_like(gx, dtype=bool)
            for i in inner:
                cov |= (gx >= b[i, 0]) & (gx <= b[i, 2]) & (gy >= b[i, 1]) & (gy <= b[i, 3])
            if cov.mean() < min_cover:
                continue
        if mode == "drop_container":
            drop.add(c)
        else:
            drop.update(inner)
    return [i for i in range(n) if i not in drop]


def square_crop(box, w, h, margin=0.1):
    """The app's crop of an item (detector_contract.md): box x0, y0, x1, y1 in pixels -> (left, top, side)."""
    x0, y0, x1, y1 = box
    side = max(x1 - x0, y1 - y0) * (1 + 2 * margin)
    side = min(side, min(w, h))
    cx, cy = (x0 + x1) / 2, (y0 + y1) / 2
    left = min(max(cx - side / 2, 0), w - side)
    top = min(max(cy - side / 2, 0), h - side)
    return int(np.floor(left)), int(np.floor(top)), int(round(side))
