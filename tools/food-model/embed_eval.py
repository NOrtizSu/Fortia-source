# SPDX-License-Identifier: AGPL-3.0-or-later
"""Which image embedding matches a package photo to the Open Food Facts front photos best (off_retrieval.py set)?

Candidates, each with the classifier's own preprocessing (short side 366, centre crop 320, ImageNet mean/std;
`--size` for others): features tapped from our classifier (it would be an extra output of food_model.tflite, free
in size and time) at several depths and poolings, the same backbone with its ImageNet weights (what fine-tuning on
food changed), and small separate ImageNet models (an extra file). Every embedding is L2-normalised; similarity =
cosine (dot product).

Metrics: recall@1 / @5 of the product among N gallery products (the true one + N−1 random others, 20 draws), for
query = the raw upload (another contributor's uncropped photo) and = that upload's selected front (cropped); the
open-set check for the app's rule: a gallery of 100 products, queries of products in it (should match) and not in
it + meal photos (should not), accept when best ≥ threshold and best − second ≥ margin.

usage: python embed_eval.py --off D:/FoodML/off_eval --ckpt D:/FoodML/runs/cls2/final.pt --meals <test.csv>
"""
import argparse
import json
import os
import sys

import numpy as np
import pandas as pd
import timm
import torch
import torch.nn.functional as F
from PIL import Image, ImageOps
from torchvision.transforms import v2 as T

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from food_items import fast_path  # noqa: E402
from train import build_model  # noqa: E402

MEAN, STD = (0.485, 0.456, 0.406), (0.229, 0.224, 0.225)


PREPROC = "center"  # center (the classifier's crop) | letterbox (whole picture padded to a square) | stretch


def load(paths, size, resize, mean=MEAN, std=STD):
    if PREPROC == "center":
        tf = T.Compose([T.Resize(resize, interpolation=T.InterpolationMode.BILINEAR, antialias=True),
                        T.CenterCrop(size), T.ToImage()])
    elif PREPROC == "stretch":
        tf = T.Compose([T.Resize((size, size), interpolation=T.InterpolationMode.BILINEAR, antialias=True),
                        T.ToImage()])
    else:
        def tf(im):
            s = max(im.size)
            sq = Image.new("RGB", (s, s), tuple(int(255 * v) for v in mean))  # pad = the mean colour (0 after norm)
            sq.paste(im, ((s - im.size[0]) // 2, (s - im.size[1]) // 2))
            return T.ToImage()(sq.resize((size, size), Image.BILINEAR))
    out = []
    for p in paths:
        with Image.open(p) as im:
            out.append(tf(ImageOps.exif_transpose(im).convert("RGB")))
    x = torch.stack(out).float() / 255.0
    return (x - torch.tensor(mean).view(1, 3, 1, 1)) / torch.tensor(std).view(1, 3, 1, 1)


def gem(x, p=3.0):
    return x.clamp(min=1e-6).pow(p).mean(dim=(2, 3)).pow(1.0 / p)


class Taps:
    """Pooled features of a timm MobileNet's last stages + its pre-logits vector."""

    def __init__(self, model):
        self.model, self.out = model, {}
        n = len(model.blocks)
        self.names = {f"s{n - 3}": model.blocks[n - 3], f"s{n - 2}": model.blocks[n - 2], f"s{n - 1}": model.blocks[n - 1]}
        for k, m in self.names.items():
            m.register_forward_hook(lambda mod, i, o, k=k: self.out.__setitem__(k, o))

    @torch.no_grad()
    def __call__(self, x):
        self.out = {}
        f = self.model.forward_features(x)
        pre = self.model.forward_head(f, pre_logits=True)
        feats = {"prelogits": pre}
        for k, v in self.out.items():
            v = v.float()
            feats[f"{k}_mean"] = v.mean(dim=(2, 3))
            feats[f"{k}_gem"] = gem(v)
            feats[f"{k}_max"] = v.amax(dim=(2, 3))
        ks = sorted(self.out)
        feats["cat_gem_last2"] = torch.cat([F.normalize(gem(self.out[ks[-2]].float()), dim=1),
                                            F.normalize(gem(self.out[ks[-1]].float()), dim=1)], 1)
        return {k: F.normalize(v.float(), dim=1) for k, v in feats.items()}


def embed(model, x, device, bs=64):
    taps = Taps(model)
    res = {}
    for i in range(0, len(x), bs):
        with torch.autocast("cuda", dtype=torch.float16):
            f = taps(x[i:i + bs].to(device))
        for k, v in f.items():
            res.setdefault(k, []).append(v.cpu())
    return {k: torch.cat(v) for k, v in res.items()}


def recall(q, g, q_idx, n_gallery, draws=20, seed=0):
    """q: [Q, D] query embeddings, their product index q_idx into g [G, D]; gallery = the true + n-1 random."""
    rng = np.random.default_rng(seed)
    G = len(g)
    sims = (q @ g.T).numpy()
    r1 = r5 = 0.0
    for _ in range(draws):
        for i, t in enumerate(q_idx):
            if n_gallery >= G:
                cand = np.concatenate([[t], np.delete(np.arange(G), t)])
            else:
                others = rng.choice(G - 1, n_gallery - 1, replace=False)
                others = others + (others >= t)
                cand = np.concatenate([[t], others])
            s = sims[i, cand]
            rank = int((s >= s[0]).sum()) - 1  # ties count against (an all-zero embedding matches nothing)
            if not np.isfinite(s[0]) or s[0] <= 0:
                rank = n_gallery
            r1 += rank < 1
            r5 += rank < 5
    n = draws * len(q_idx)
    return r1 / n, r5 / n


def open_set(qe, ge, q_idx, meal, n_gallery=100, draws=20, seed=1):
    """Scores for the app's accept rule: positives (query's product in the gallery) / negatives (not in it, meals)."""
    rng = np.random.default_rng(seed)
    G = len(ge)
    pos, neg, mealneg = [], [], []
    sims_q = (qe @ ge.T).numpy()
    sims_m = (meal @ ge.T).numpy() if meal is not None else None
    qset = np.unique(q_idx)
    rest = np.setdiff1d(np.arange(G), qset)
    for _ in range(draws):
        # half of the gallery: products that have a query photo (those queries should match), half: others
        gal = np.concatenate([rng.choice(qset, n_gallery // 2, replace=False),
                              rng.choice(rest, n_gallery - n_gallery // 2, replace=False)])
        ing = set(gal.tolist())
        for i, t in enumerate(q_idx):
            s = sims_q[i, gal]
            o = np.argsort(-s)
            best, second = s[o[0]], s[o[1]]
            if t in ing:
                pos.append((best, best - second, gal[o[0]] == t))
            else:
                neg.append((best, best - second))
        if sims_m is not None:
            for i in rng.choice(len(sims_m), min(500, len(sims_m)), replace=False):
                s = np.sort(sims_m[i, gal])[::-1]
                mealneg.append((s[0], s[0] - s[1]))
    return np.array(pos, dtype=float), np.array(neg, dtype=float), np.array(mealneg, dtype=float)


def rule_table(pos, neg, mealneg, thresholds, margins):
    rows = []
    for th in thresholds:
        for mg in margins:
            acc = (pos[:, 0] >= th) & (pos[:, 1] >= mg)
            right = acc & (pos[:, 2] > 0)
            fa_prod = ((neg[:, 0] >= th) & (neg[:, 1] >= mg)).mean() if len(neg) else 0.0
            fa_meal = ((mealneg[:, 0] >= th) & (mealneg[:, 1] >= mg)).mean() if len(mealneg) else 0.0
            rows.append({"threshold": th, "margin": mg, "accepted": acc.mean(), "right_of_accepted":
                         right.sum() / max(1, acc.sum()), "right": right.mean(), "false_accept_other_product": fa_prod,
                         "false_accept_meal": fa_meal})
    return pd.DataFrame(rows)


def load_emb(f):
    """Saved embeddings: .pt (this script) or .npz (embed_tflite.py)."""
    if f.endswith(".npz"):
        z = np.load(f)
        return {k: torch.from_numpy(z[k]) for k in ("g", "q", "m")} | {"q_idx": z["q_idx"]}
    return torch.load(f, weights_only=False)


def rule_report(files, out_json=None):
    """Recall at several gallery sizes and the accept rule (threshold, margin) from saved embeddings; several files
    = their L2-normalised concatenation."""
    d = [load_emb(f) for f in files]
    cat = lambda k: F.normalize(torch.cat([x[k].float() for x in d], 1), dim=1)  # noqa: E731
    g, q, m, q_idx = cat("g"), cat("q"), cat("m"), d[0]["q_idx"]
    rep = {"files": [os.path.basename(f) for f in files], "recall": {}}
    for n in (10, 30, 100, 300, 1000):
        r1, r5 = recall(q, g, q_idx, n)
        rep["recall"][n] = {"r1": round(r1, 4), "r5": round(r5, 4)}
    rep["recall"]["all"] = dict(zip(("r1", "r5"), (round(v, 4) for v in recall(q, g, q_idx, len(g), draws=1))))
    tables = {}
    for n in (30, 100):
        pos, neg, mealneg = open_set(q, g, q_idx, m, n_gallery=n)
        tab = rule_table(pos, neg, mealneg, thresholds=[round(x, 2) for x in np.arange(0.40, 0.96, 0.01)],
                         margins=[round(x, 3) for x in np.arange(0.0, 0.151, 0.005)])
        # the recommended rule: most matches accepted with >= 95 % of them right, <= 5 % of photos of a product
        # not in the gallery and <= 1 % of meal photos wrongly accepted
        ok = tab[(tab.right_of_accepted >= 0.95) & (tab.false_accept_other_product <= 0.05)
                 & (tab.false_accept_meal <= 0.01)]
        rep[f"rule_gallery{n}"] = ok.sort_values("accepted", ascending=False).head(1).round(4).to_dict("records")
        # a looser "suggest" level: best match shown among suggestions (no auto-accept), right ≥ 80 %
        sug = tab[(tab.margin == 0.0) & (tab.right_of_accepted >= 0.80)]
        rep[f"suggest_gallery{n}"] = sug.sort_values("accepted", ascending=False).head(1).round(4).to_dict("records")
        tables[n] = tab[tab.threshold.isin([0.5, 0.55, 0.6, 0.65, 0.7, 0.75, 0.8, 0.85, 0.9]) & tab.margin.isin([0.0, 0.02, 0.04, 0.06, 0.08, 0.1])]
        # similarity distributions: right best match / wrong best / product not in the gallery / meal photo
        rep[f"scores_gallery{n}"] = {
            "positive_right_best_median": float(np.median(pos[pos[:, 2] > 0, 0])),
            "positive_wrong_best_median": float(np.median(pos[pos[:, 2] == 0, 0])) if (pos[:, 2] == 0).any() else None,
            "not_in_gallery_best_p95": float(np.percentile(neg[:, 0], 95)),
            "meal_best_p95": float(np.percentile(mealneg[:, 0], 95)),
            "meal_best_p99": float(np.percentile(mealneg[:, 0], 99))}
    print(json.dumps(rep, indent=1), flush=True)
    for n, tab in tables.items():
        print(f"gallery of {n} products, half of them with a query photo:")
        print(tab.round(4).to_string(index=False), flush=True)
    if out_json:
        with open(out_json, "w") as f:
            json.dump(rep | {"tables": {n: t.round(4).to_dict("records") for n, t in tables.items()}}, f, indent=1)
    return rep, tables


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--off", default="D:/FoodML/off_eval")
    ap.add_argument("--ckpt", default="D:/FoodML/runs/cls2/final.pt")
    ap.add_argument("--meals", default="D:/FoodML/dataset_v2/test.csv")
    ap.add_argument("--n-meals", type=int, default=1000)
    ap.add_argument("--others", default="mobilenetv4_conv_medium.e250_r384_in12k_ft_in1k,mobilenetv3_small_100.lamb_in1k,"
                                        "mobilenetv4_conv_small.e2400_r224_in1k,mobilenetv3_large_100.ra_in1k,"
                                        "efficientnet_b0.ra_in1k")
    ap.add_argument("--out", default="D:/FoodML/runs/v3/embed_eval")
    ap.add_argument("--rule", default="", help="only the rule report of these saved embeddings (comma-separated)")
    ap.add_argument("--tag", default="ours_cls2", help="name of our model's results")
    ap.add_argument("--preproc", default="center", choices=("center", "letterbox", "stretch"))
    a = ap.parse_args()
    global PREPROC
    PREPROC = a.preproc
    if a.rule:
        rule_report(a.rule.split(","), os.path.join(a.out, "rule_" + "+".join(
            os.path.splitext(os.path.basename(f))[0] for f in a.rule.split(",")) + ".json"))
        return
    os.makedirs(a.out, exist_ok=True)
    device = torch.device("cuda")
    pr = pd.read_csv(os.path.join(a.off, "products.csv"), dtype=str).fillna("")
    img = os.path.join(a.off, "img")
    gal_paths = [os.path.join(img, f) for f in pr.gallery]
    qmask = pr["query"] != ""
    q_idx = np.flatnonzero(qmask.to_numpy())
    q_paths = [os.path.join(img, f) for f in pr["query"][qmask]]
    s_paths = [os.path.join(img, f) if f else os.path.join(img, g) for f, g in zip(pr.query_sel[qmask], pr.gallery[qmask])]
    meals = pd.read_csv(a.meals).sample(a.n_meals, random_state=5)
    meal_paths = [fast_path(p) for p in meals.path]
    print(f"{len(pr)} gallery products, {len(q_idx)} queries, {len(meal_paths)} meal photos", flush=True)

    models = []
    ck = torch.load(a.ckpt, map_location="cpu", weights_only=False)
    m = build_model(ck["model"], ck["n_classes"], ck.get("head_hidden", 0), pretrained=False)
    m.load_state_dict(ck["state_dict"])
    models.append((a.tag, m, ck.get("size", 320), ck.get("resize", 366)))
    for name in [x for x in a.others.split(",") if x]:
        mm = timm.create_model(name, pretrained=True)
        cfg = mm.pretrained_cfg
        sz = 320 if "medium" in name else cfg["input_size"][-1]
        models.append((name.split(".")[0] + "_imagenet", mm, sz, int(round(sz / cfg.get("crop_pct", 0.875)))))

    results = []
    cache = {}
    for name, model, size, resize in models:
        model.eval().to(device)
        key = (size, resize)
        if key not in cache:
            cache[key] = (load(gal_paths, size, resize), load(q_paths, size, resize), load(s_paths, size, resize),
                          load(meal_paths, size, resize))
        xg, xq, xs, xm = cache[key]
        eg, eq, es, em = (embed(model, x, device) for x in (xg, xq, xs, xm))
        nparam = sum(p.numel() for p in model.parameters())
        for k in eg:
            row = {"model": name, "tap": k, "dim": eg[k].shape[1], "params_M": round(nparam / 1e6, 2)}
            for n in (100, 1000):
                row[f"raw_r1@{n}"], row[f"raw_r5@{n}"] = recall(eq[k], eg[k], q_idx, n)
                row[f"sel_r1@{n}"], row[f"sel_r5@{n}"] = recall(es[k], eg[k], q_idx, n)
            row["raw_r1@all"], row["raw_r5@all"] = recall(eq[k], eg[k], q_idx, len(eg[k]), draws=1)
            results.append(row)
            print(json.dumps({x: (round(v, 4) if isinstance(v, float) else v) for x, v in row.items()}), flush=True)
            torch.save({"g": eg[k], "q": eq[k], "s": es[k], "m": em[k], "q_idx": q_idx},
                       os.path.join(a.out, f"emb_{name}_{k}.pt"))
        model.cpu()
    df = pd.DataFrame(results).sort_values("raw_r1@100", ascending=False)
    df.to_csv(os.path.join(a.out, "recall.csv"), index=False)
    print(df.head(25).to_string(), flush=True)


if __name__ == "__main__":
    main()
