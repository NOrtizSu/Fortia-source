# SPDX-License-Identifier: AGPL-3.0-or-later
"""Evaluate a checkpoint on the held-out test set and calibrate it.

- top-1 / top-5 overall, per food group, on the Spanish/European dishes, per class (worst 30) and the main
  confusions; `_not_food` precision/recall.
- Temperature scaling: the temperature T that minimises the negative log-likelihood on the *val* split;
  expected calibration error (15 bins) on test before/after.
- min_confidence suggestion: the probability under which the app should say "no food recognised" when even
  the best food class is that unsure (keeps >= 99 % of the food test photos above it).

Writes <run>/eval/{metrics.json,per_class.csv,confusions.csv,test_logits.npy,test_labels.npy}
(--test test_v1.csv --tag v1: <run>/eval_v1/..., the v1 test photos, for the comparison with the v1 model).

usage: python evaluate.py --work D:/FoodML --run run1 --classes classes.csv [--test test.csv --tag ""]
"""
import argparse
import csv
import json
import os
import sys

import numpy as np
import pandas as pd
import timm
import torch
from torch.utils.data import DataLoader

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from train import Photos, configure_from_checkpoint, eval_tf, evaluate  # noqa: E402

NOT_FOOD = "_not_food"
# Dishes and foods typical of Spain / southern Europe, reported separately.
SPANISH_EUROPEAN = """paella spanish_omelette gazpacho croquettes patatas_bravas churros cured_ham chorizo salami
black_pudding calamari octopus prawns mussels clams anchovies sardines fried_fish lentils bean_stew tripe_stew
suckling_pig meatballs breaded_cutlet baguette_sandwich bread_with_tomato flan creme_brulee custard rice_pudding
french_toast turron custard_tart sangria vermouth horchata gin_tonic coffee_with_milk espresso ratatouille
potato_salad fritters swiss_roll mille_feuille sponge_cake muffin green_salad grilled_fish steak roast_chicken
fried_egg olives cheese bread baguette pizza lasagna cannelloni pasta_tomato carbonara risotto tiramisu
spaghetti_bolognese greek_salad caprese_salad moussaka quiche soup cream_soup fish_soup ham sobrasada cocido
migas pimientos_de_padron huevos_rotos salmorejo fideua ensaimada chickpeas canned_tuna""".split()


def nll(logits, labels, t):
    return torch.nn.functional.cross_entropy(logits / t, labels).item()


def fit_temperature(logits, labels):
    best = (1.0, nll(logits, labels, 1.0))
    for t in np.arange(0.5, 3.01, 0.02):
        v = nll(logits, labels, float(t))
        if v < best[1]:
            best = (float(t), v)
    return round(best[0], 2)


def ece(probs, labels, bins=15):
    conf, pred = probs.max(1)
    acc = (pred == labels).float()
    e = 0.0
    edges = torch.linspace(0, 1, bins + 1)
    for lo, hi in zip(edges[:-1], edges[1:]):
        m = (conf > lo) & (conf <= hi)
        if m.any():
            e += m.float().mean().item() * abs(acc[m].mean().item() - conf[m].mean().item())
    return round(e, 4)


def topk_acc(logits, labels, mask=None):
    if mask is not None:
        logits, labels = logits[mask], labels[mask]
    if len(labels) == 0:
        return None, None, 0
    top5 = logits.topk(5, 1).indices
    return (round((top5[:, 0] == labels).float().mean().item(), 4),
            round((top5 == labels[:, None]).any(1).float().mean().item(), 4), int(len(labels)))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--work", default="D:/FoodML")
    ap.add_argument("--dataset", default="dataset", help="under --work")
    ap.add_argument("--run", default="run1")
    ap.add_argument("--ckpt", default="best.pt")
    ap.add_argument("--classes", default="classes.csv")
    ap.add_argument("--runs", default="", help="runs folder (default <work>/runs)")
    ap.add_argument("--data", default="", help="dataset folder (default <work>/<dataset>)")
    ap.add_argument("--test", default="test.csv")
    ap.add_argument("--tag", default="")
    a = ap.parse_args()
    rows = list(csv.DictReader(open(a.classes, encoding="utf-8")))
    ids = [r["id"] for r in rows]
    group = {r["id"]: r["group"] for r in rows}
    run = os.path.join(a.runs or os.path.join(a.work, "runs"), a.run)
    out = os.path.join(run, "eval" + (f"_{a.tag}" if a.tag else ""))
    data = a.data or os.path.join(a.work, a.dataset)
    os.makedirs(out, exist_ok=True)
    ck = torch.load(os.path.join(run, a.ckpt), map_location="cpu")
    configure_from_checkpoint(ck)
    model = timm.create_model(ck["model"], pretrained=False, num_classes=ck["n_classes"])
    model.load_state_dict(ck["state_dict"])
    device = torch.device("cuda")
    model.to(device)
    res = {}
    for split, fn in (("val", "val.csv"), ("test", a.test)):
        dl = DataLoader(Photos(os.path.join(data, fn), eval_tf(), packed=False), 256, num_workers=3)
        _, _, logits, labels = evaluate(model, dl, device, ck["n_classes"])
        res[split] = (logits, labels)
    vl, vy = res["val"]
    tl, ty = res["test"]
    np.save(os.path.join(out, "test_logits.npy"), tl.numpy())
    np.save(os.path.join(out, "test_labels.npy"), ty.numpy())
    T = fit_temperature(vl, vy)
    nf = ids.index(NOT_FOOD)
    probs = torch.softmax(tl / T, 1)
    pred = tl.argmax(1)

    m = {"checkpoint": a.ckpt, "epoch": ck.get("epoch"), "classes": len(ids), "test_photos": int(len(ty)),
         "temperature": T, "ece_before": ece(torch.softmax(tl, 1), ty), "ece_after": ece(probs, ty)}
    m["top1"], m["top5"], _ = topk_acc(tl, ty)
    food = ty != nf
    m["food_only_top1"], m["food_only_top5"], _ = topk_acc(tl, ty, food)
    tp = int(((pred == nf) & (ty == nf)).sum())
    m["not_food_precision"] = round(tp / max(1, int((pred == nf).sum())), 4)
    m["not_food_recall"] = round(tp / max(1, int((ty == nf).sum())), 4)
    # food photos the app would wrongly call "not food" (argmax is _not_food)
    m["food_called_not_food"] = round(float(((pred == nf) & food).sum()) / float(food.sum()), 4)
    # min_confidence: best food-class probability (excluding _not_food) on food photos
    pf = probs.clone()
    pf[:, nf] = 0
    best_food = pf.max(1).values[food].numpy()
    m["suggested_min_confidence"] = round(float(np.quantile(best_food, 0.01)), 3)
    m["best_food_prob_quantiles_on_food"] = {q: round(float(np.quantile(best_food, q)), 3)
                                             for q in (0.01, 0.02, 0.05, 0.1, 0.5)}
    by_group = {}
    for g in sorted(set(group.values()) - {""}):
        mask = torch.tensor([group[ids[int(y)]] == g for y in ty])
        by_group[g] = dict(zip(("top1", "top5", "n"), topk_acc(tl, ty, mask)))
    m["by_group"] = by_group
    sp = {ids.index(c) for c in SPANISH_EUROPEAN if c in ids}
    mask = torch.tensor([int(y) in sp for y in ty])
    m["spanish_european"] = dict(zip(("top1", "top5", "n"), topk_acc(tl, ty, mask)))
    m["spanish_european_classes"] = sorted(ids[i] for i in sp)

    per = []
    top5 = tl.topk(5, 1).indices
    conf = {}
    for i, c in enumerate(ids):
        mk = ty == i
        n = int(mk.sum())
        if n == 0:
            continue
        t1 = float((pred[mk] == i).float().mean())
        t5 = float((top5[mk] == i).any(1).float().mean())
        wrong = pred[mk][pred[mk] != i]
        if len(wrong):
            vals, cnts = torch.unique(wrong, return_counts=True)
            for v, k in zip(vals.tolist(), cnts.tolist()):
                conf[(c, ids[v])] = k
        per.append({"class": c, "group": group[c], "n_test": n, "top1": round(t1, 3), "top5": round(t5, 3)})
    per_df = pd.DataFrame(per).sort_values(["top1", "top5"])
    per_df.to_csv(os.path.join(out, "per_class.csv"), index=False)
    cf = sorted(conf.items(), key=lambda kv: -kv[1])
    pd.DataFrame([{"true": k[0], "predicted": k[1], "count": v} for k, v in cf]).to_csv(
        os.path.join(out, "confusions.csv"), index=False)
    m["worst30"] = per_df.head(30)[["class", "top1", "top5", "n_test"]].to_dict("records")
    m["top_confusions"] = [{"true": k[0], "predicted": k[1], "count": v} for k, v in cf[:30]]
    with open(os.path.join(out, "metrics.json"), "w", encoding="utf-8") as f:
        json.dump(m, f, indent=1, ensure_ascii=False)
    print(json.dumps({k: v for k, v in m.items() if k not in ("worst30", "top_confusions",
                                                                "spanish_european_classes")}, indent=1))


if __name__ == "__main__":
    main()
