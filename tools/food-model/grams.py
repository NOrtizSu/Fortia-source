# SPDX-License-Identifier: AGPL-3.0-or-later
"""Grams estimate (suggestion only, grams_contract.md): a regression head on the classifier's features,
calibrated on Nutrition5k (Google, CC BY 4.0: overhead photos of cafeteria dishes with the weighed mass).

Steps (training venv):
  features  For every Nutrition5k dish with an overhead photo: the classifier's pooled features and top-5 of
            the whole photo (the app's single flow) and of the square crop of **every** detector box above the
            score threshold and the minimum side (the multi-item flow is decided later, so the container rule
            can be compared without re-running the models). -> <run>/grams/features.npz
  fit       Applies the app's box rules (container rule `--rule`, at most 6 items; 2+ items = multi flow) and
            fits a linear head y = w·f + b ("log grams of the food in the picture") so that the photo's estimate
            matches the dish mass: single flow exp(y_whole) ≈ M, multi flow Σ_i exp(y_i) ≈ M (loss on log M).
            Ridge strength picked on a validation part of Nutrition5k's training dishes; on the same dishes the
            app formula ln est_i = b0 + b_model·y_i + b_portion·ln(p_i) + b_share·ln(share_i) (p = catalog portion
            of the item's top-1 food, share = its box area / all items' box area) and its clamp to
            [lo, hi] × p are chosen. Reported on Nutrition5k's test dishes:
              - per plate: the suggested total (sum over items in the multi flow) vs the dish mass;
              - per item: dishes of one food (≥ 90 % of the mass one ingredient): the estimate of each item
                crop the detector found alone (share 1), and the sum over the pieces when it found several;
            against the baseline the app uses today (the default portion of the recognised food) and a constant.
            -> <run>/grams/{head.pt,grams.json,report.json}
  compare   fit for every container rule (none, drop_container, keep_container, drop_container_cover0.5) and
            prints the per-plate / per-item errors side by side.

Caveat: Nutrition5k photos come from one rig (fixed camera height, the same white plates), so pixel size means
the same physical size in every photo; a phone photo has no such scale. To keep the head from relying on that,
every dish is also seen "closer" (--zooms: the central 65 % of the photo, as a phone held nearer would see it)
with the same mass to predict, and the test error is reported per zoom. The error on real phone photos is still
expected to be higher than measured here.

usage: python grams.py features --work D:/FoodML --run cls2 --detector D:/FoodML/runs/det/det1/weights/best.pt \
           --detector-spec ../../app/src/main/assets/food/food_detector.json
       python grams.py fit|compare --work D:/FoodML --run cls2 --foods <foods.tsv> --classes classes.csv \
           [--rule drop_container]
"""
import argparse
import csv
import json
import os
import sys

import numpy as np
import torch

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from food_items import group_rule  # noqa: E402

N5K = "dl/n5k"
RULES = {"none": None,
         "drop_container": {"mode": "drop_container", "inside": 0.8, "min_inside": 2},
         "keep_container": {"mode": "keep_container", "inside": 0.8, "min_inside": 2},
         "drop_container_cover0.5": {"mode": "drop_container", "inside": 0.8, "min_inside": 2, "min_cover": 0.5}}


def load_dishes(work):
    """(dish id, photo, mass g, split, share of the main ingredient) of every usable Nutrition5k dish."""
    root = os.path.join(work, N5K)
    mass, main = {}, {}
    for f in ("dish_metadata_cafe1.csv", "dish_metadata_cafe2.csv"):
        for r in csv.reader(open(os.path.join(root, "meta", f))):
            mass[r[0]] = float(r[2])
            grams = [float(r[i + 2]) for i in range(6, len(r) - 6, 7)]
            main[r[0]] = max(grams) / max(1e-9, sum(grams)) if grams else 0.0
    split = {}
    for s, f in (("train", "rgb_train_ids.txt"), ("test", "rgb_test_ids.txt")):
        for d in open(os.path.join(root, "meta", f)).read().split():
            split[d] = s
    out = []
    for fn in sorted(os.listdir(os.path.join(root, "rgb"))):
        d = fn[:-4]
        if d in mass and d in split and mass[d] >= 5:
            out.append((d, os.path.join(root, "rgb", fn), mass[d], split[d], main[d]))
    return out


def step_features(a):
    import timm
    from PIL import Image
    from ultralytics import YOLO
    import train as T
    from food_items import square_crop

    run = os.path.join(a.runs or os.path.join(a.work, "runs"), a.run)
    ck = torch.load(os.path.join(run, a.ckpt), map_location="cpu")
    T.configure_from_checkpoint(ck)
    model = timm.create_model(ck["model"], pretrained=False, num_classes=ck["n_classes"])
    model.load_state_dict(ck["state_dict"])
    model.eval().cuda()
    tf = T.eval_tf()
    det = YOLO(a.detector)
    spec = json.load(open(a.detector_spec)) if a.detector_spec else {}
    thr = spec.get("score_threshold", a.score_threshold)
    min_side = spec.get("min_box_side", 0.06)
    margin = spec.get("crop", {}).get("margin", 0.1)
    zooms = [float(z) for z in a.zooms.split(",")]
    keys = ("dish", "split", "mass", "main", "zoom", "kind", "box", "feat", "top5")
    rec = {k: [] for k in keys}

    @torch.no_grad()
    def feats(imgs):
        x = T.gpu_normalize(torch.stack([tf(im) for im in imgs]).cuda())
        with torch.autocast("cuda", dtype=torch.float16):
            f = model.forward_head(model.forward_features(x), pre_logits=True)
            lg = model.get_classifier()(f)
        return f.float().cpu().numpy(), lg.float().topk(5, 1).indices.cpu().numpy()

    for k, (d, path, m, s, main) in enumerate(load_dishes(a.work)):
        full = Image.open(path).convert("RGB")
        W0, H0 = full.size
        for z in zooms:
            # z < 1: the central part of the photo, as a camera held nearer would see the plate
            cw, ch = round(W0 * z), round(H0 * z)
            x0, y0 = (W0 - cw) // 2, (H0 - ch) // 2
            im = full if z == 1.0 else full.crop((x0, y0, x0 + cw, y0 + ch))
            W, H = im.size
            r = det.predict(im.resize((a.det_size, a.det_size), Image.BILINEAR), imgsz=a.det_size, conf=thr,
                            iou=0.5, verbose=False, max_det=10)[0]
            boxes = [b for b, c in zip(r.boxes.xyxyn.cpu().numpy(), r.boxes.conf.cpu().numpy())
                     if c >= thr and min((b[2] - b[0]) * W, (b[3] - b[1]) * H) >= min_side * min(W, H)]
            imgs, kinds, bx = [im], ["whole"], [np.array([0.0, 0.0, 1.0, 1.0])]
            for b in boxes:
                left, top, side = square_crop((b[0] * W, b[1] * H, b[2] * W, b[3] * H), W, H, margin)
                imgs.append(im.crop((left, top, left + side, top + side)))
                kinds.append("box")
                bx.append(np.asarray(b, dtype=np.float64))
            f, t5 = feats(imgs)
            for i in range(len(imgs)):
                for key, v in zip(keys, (d, s, m, main, z, kinds[i], bx[i], f[i], t5[i])):
                    rec[key].append(v)
        if k % 250 == 0:
            print(k, flush=True)
    out = os.path.join(run, "grams")
    os.makedirs(out, exist_ok=True)
    np.savez_compressed(os.path.join(out, "features.npz"), **{k: np.array(v) for k, v in rec.items()})
    print("written", out, len(rec["dish"]), "pictures")


def load_portions(foods, labels):
    p = {}
    with open(foods, encoding="utf-8") as f:
        for r in csv.DictReader(f, delimiter="\t"):
            try:
                p[r["id"]] = float(r["portion"])
            except (KeyError, ValueError):
                pass
    return np.array([p.get(c, np.nan) for c in labels])


def scores(est, true):
    rel = np.abs(est - true) / true
    return round(float(np.median(rel)), 4), round(float(np.mean(rel <= 0.25)), 4)


def fit(a, rule_name, verbose=True):
    run = os.path.join(a.runs or os.path.join(a.work, "runs"), a.run)
    z = np.load(os.path.join(run, "grams", "features.npz"))
    labels = [r["id"] for r in csv.DictReader(open(a.classes, encoding="utf-8"))]
    portion = load_portions(a.foods, labels)
    dish, split, mass, kind, zoom, main = z["dish"], z["split"], z["mass"], z["kind"], z["zoom"], z["main"]
    box = z["box"]
    feat = torch.tensor(z["feat"], dtype=torch.float32)
    p_top1 = portion[z["top5"][:, 0]]
    p_top1 = np.where(np.isnan(p_top1), np.nanmedian(portion), p_top1)
    rule = RULES[rule_name]
    rng = np.random.default_rng(0)
    ud = sorted(set(dish))
    first = {}
    for i, d in enumerate(dish):
        first.setdefault(d, i)
    train_d = [d for d in ud if split[first[d]] == "train"]
    val_d = set(rng.choice(train_d, int(0.15 * len(train_d)), replace=False))
    # groups: (picture indices, shares, mass, flow, part, zoom, one-food dish)
    by = {}
    for i, (d, zz) in enumerate(zip(dish, zoom)):
        by.setdefault((d, float(zz)), []).append(i)
    groups = []
    for (d, zz), idx in by.items():
        idx = np.array(idx)
        part = "test" if split[idx[0]] == "test" else "val" if d in val_d else "train"
        whole = idx[kind[idx] == "whole"]
        bxs = idx[kind[idx] == "box"]
        keep = [bxs[i] for i in (group_rule([box[j] for j in bxs], **rule) if rule else range(len(bxs)))][:6]
        one = bool(main[idx[0]] >= 0.9)
        m = float(mass[idx[0]])
        if len(keep) >= 2:
            ar = np.array([(box[j][2] - box[j][0]) * (box[j][3] - box[j][1]) for j in keep])
            groups.append((np.array(keep), ar / ar.sum(), m, "multi", part, zz, one))
        else:
            groups.append((whole, np.ones(1), m, "single", part, zz, one))
            if len(keep) == 1:  # one item found: its crop alone (per-item check, share 1)
                groups.append((np.array(keep), np.ones(1), m, "item1", part, zz, one))
    mu, sd = feat.mean(0), feat.std(0) + 1e-6
    xf = (feat - mu) / sd

    def flat(parts, flows=("single", "multi")):
        gs = [g for g in groups if g[4] in parts and g[3] in flows]
        idx = torch.tensor(np.concatenate([g[0] for g in gs]), dtype=torch.long)
        gid = torch.tensor(np.concatenate([np.full(len(g[0]), k) for k, g in enumerate(gs)]), dtype=torch.long)
        logm = torch.tensor([float(np.log(g[2])) for g in gs])
        return idx, gid, logm, len(gs)

    def group_lse(y, gid, n):
        mx = torch.full((n,), -1e9).scatter_reduce(0, gid, y.detach(), "amax")
        return mx + torch.log(torch.zeros(n).index_add(0, gid, torch.exp(y - mx[gid])))

    def train_head(lam):
        idx, gid, logm, n = flat(("train",))
        x = xf[idx]
        w = torch.zeros(xf.shape[1], requires_grad=True)
        b = torch.tensor(float(np.log(np.median(mass))), requires_grad=True)
        opt = torch.optim.Adam([w, b], lr=0.01)
        for _ in range(a.epochs):
            opt.zero_grad()
            loss = ((group_lse(x @ w + b, gid, n) - logm) ** 2).mean() + lam * (w ** 2).sum()
            loss.backward()
            opt.step()
        return w.detach(), b.detach()

    def rows(w, b, part, flows, zz=None):
        return [(g, (xf[g[0]] @ w + b).numpy()) for g in groups
                if g[4] == part and g[3] in flows and (zz is None or g[5] == zz)]

    def head_total(rs):
        return np.array([np.exp(y).sum() for _, y in rs])

    best = None
    for lam in a.lambdas:
        w, b = train_head(lam)
        rs = rows(w, b, "val", ("single", "multi"))
        err = scores(head_total(rs), np.array([g[2] for g, _ in rs]))[0]
        if verbose:
            print("lambda", lam, "val MdAPE", err, flush=True)
        if best is None or err < best[0]:
            best = (err, lam, w, b)
    _, lam, w, b = best

    def app(rs, coef, lo=0.0, hi=1e9):
        b0, b1, b2, b3 = coef
        out = []
        for g, y in rs:
            p = p_top1[g[0]]
            e = np.exp(b0 + b1 * y + b2 * np.log(p) + b3 * np.log(np.clip(g[1], 1e-3, 1)))
            out.append(np.clip(e, lo * p, hi * p).sum())
        return np.array(out)

    from scipy.optimize import minimize
    val_rs = rows(w, b, "val", ("single", "multi"))
    true_val = np.array([g[2] for g, _ in val_rs])
    coef = minimize(lambda c: float(np.mean(np.abs(np.log(app(val_rs, c)) - np.log(true_val)))),
                    x0=[0.0, 1.0, 0.0, 0.0], method="Nelder-Mead", options={"maxiter": 4000}).x
    # the clamp around the catalog portion, chosen on the same validation dishes (plates and one-food items)
    # one-food dishes only: an item found alone, and the pieces of one food (a pile of potatoes) summed
    val_items = [r for r in rows(w, b, "val", ("item1",)) if r[0][6]]
    true_items = np.array([g[2] for g, _ in val_items])
    val_pieces = [r for r in rows(w, b, "val", ("multi",)) if r[0][6]]
    true_pieces = np.array([g[2] for g, _ in val_pieces])
    clamp = None
    for lo in (0.0, 0.05, 0.1, 0.15, 0.25):
        for hi in (3.0, 4.0, 6.0):
            e = (scores(app(val_rs, coef, lo, hi), true_val)[0] + scores(app(val_items, coef, lo, hi), true_items)[0]
                 + (scores(app(val_pieces, coef, lo, hi), true_pieces)[0] if val_pieces else 0.0))
            if clamp is None or e < clamp[0] - 1e-9:
                clamp = (e, lo, hi)
    _, lo, hi = clamp
    const = float(np.median([g[2] for g in groups if g[4] == "train" and g[3] in ("single", "multi")]))
    rep = {"rule": rule_name, "lambda": lam, "coef_b0_bmodel_bportion_bshare": [round(float(c), 4) for c in coef],
           "clamp_portion": [lo, hi], "test_dishes": int(len({d for d in dish[split == "test"]}))}
    for zz in sorted(set(zoom.tolist())):
        zkey = "" if zz == 1.0 else f"zoom{zz:g}/"
        for name, flows, one_only in (("plate_single", ("single",), False), ("plate_multi", ("multi",), False),
                                      ("plate_all", ("single", "multi"), False),
                                      ("item_alone", ("item1",), True), ("item_pieces", ("multi",), True)):
            rs = [r for r in rows(w, b, "test", flows, zz) if r[0][6] or not one_only]
            if not rs:
                continue
            true = np.array([g[2] for g, _ in rs])
            r = {"n": len(rs)}
            for k, e in (("app", app(rs, coef, lo, hi)), ("app_no_clamp", app(rs, coef)),
                         ("head", head_total(rs)),
                         ("default_portion", np.array([p_top1[g[0]].sum() for g, _ in rs])),
                         ("constant_median", np.full(len(rs), const))):
                r[f"mdape_{k}"], r[f"within25_{k}"] = scores(e, true)
            rep[zkey + name] = r
    return rep, w, b, mu, sd, coef, lo, hi


def step_fit(a):
    rep, w, b, mu, sd, coef, lo, hi = fit(a, a.rule)
    print(json.dumps(rep, indent=1))
    out = os.path.join(a.runs or os.path.join(a.work, "runs"), a.run, "grams")
    # fold the feature standardisation into the head: y = w·(f - mu)/sd + b = (w/sd)·f + (b - w·mu/sd)
    torch.save({"weight": (w / sd)[None, :].clone(), "bias": torch.tensor([float(b - (w * mu / sd).sum())])},
               os.path.join(out, "head.pt"))
    with open(os.path.join(out, "report.json"), "w") as f:
        json.dump(rep, f, indent=1)
    # enabled only if clearly better than the default portion on plates at every simulated camera distance
    better = all(v["mdape_app"] < v["mdape_default_portion"] * (1 - a.min_gain)
                 for k, v in rep.items() if k.endswith("plate_all"))
    gj = {"enabled": bool(better), "b0": round(float(coef[0]), 4), "b_model": round(float(coef[1]), 4),
          "b_portion": round(float(coef[2]), 4), "b_share": round(float(coef[3]), 4),
          "clamp_portion": [lo, hi], "clamp_grams": [5, 1500], "round_to": 5}
    with open(os.path.join(out, "grams.json"), "w") as f:
        json.dump(gj, f, indent=1)
    print("grams block:", gj)


def step_compare(a):
    for name in RULES:
        rep = fit(a, name, verbose=False)[0]
        print(name, "coef", rep["coef_b0_bmodel_bportion_bshare"], "clamp", rep["clamp_portion"])
        for k, v in rep.items():
            if isinstance(v, dict):
                print("   ", k, v["n"], "app", v["mdape_app"], f"({v['within25_app']})", "no-clamp",
                      v["mdape_app_no_clamp"], "portion", v["mdape_default_portion"],
                      "const", v["mdape_constant_median"],
                      flush=True)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("step", choices=("features", "fit", "compare"))
    ap.add_argument("--work", default="D:/FoodML")
    ap.add_argument("--runs", default="")
    ap.add_argument("--run", required=True)
    ap.add_argument("--ckpt", default="best.pt")
    ap.add_argument("--detector", default="")
    ap.add_argument("--detector-spec", default="", help="food_detector.json (thresholds as in the app)")
    ap.add_argument("--det-size", type=int, default=320)
    ap.add_argument("--score-threshold", type=float, default=0.25)
    ap.add_argument("--foods", default="")
    ap.add_argument("--classes", default="classes.csv")
    ap.add_argument("--rule", default="drop_container", choices=list(RULES))
    ap.add_argument("--lambdas", type=float, nargs="+", default=[1e-3, 1e-2, 3e-2, 1e-1])
    ap.add_argument("--epochs", type=int, default=300)
    ap.add_argument("--min-gain", type=float, default=0.1, help="enable only if this much better than the portion")
    ap.add_argument("--zooms", default="1.0,0.65", help="features: also the central part of each photo")
    a = ap.parse_args()
    {"features": step_features, "fit": step_fit, "compare": step_compare}[a.step](a)


if __name__ == "__main__":
    main()
