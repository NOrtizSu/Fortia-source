# SPDX-License-Identifier: AGPL-3.0-or-later
"""A stronger teacher for knowledge distillation: a linear probe on a large frozen image model's features.

The phone model stays MobileNetV4 (size budget); during training it also learns from the soft predictions of a
teacher. The teacher = a big permissively licensed image encoder, frozen (no fine-tuning, too slow on one 8 GB GPU),
plus a linear layer trained on our classes. Its predictions are computed once, offline, for several views of every
training picture: view 0 = the eval centre crop, views 1..V-1 = random resized crops (scale 0.25-1, ratio 3/4-4/3,
random flip) whose parameters are stored, so train.py --teacher can give the student the same crop and the
teacher's predictions for it (consistent views, "function matching" without running the teacher each step).

- `features`: encoder features of every view of every picture of a split (+ the crop parameters) -> <out>/<split>_*.npy;
  with --old-ckpt also an earlier classifier of ours (1.6.0) on the same views (its log-probabilities in this
  dataset's label order; -30 for the classes it does not know).
- `probe`: trains the linear layer on the training views, reports val/test top-1/top-5 (view 0) of the probe and
  of the ensemble, writes the teacher's logits for every training view (float16) for train.py. The teacher is the
  weighted geometric mean of the probe and the earlier classifier (weight --old-weight 0.3; the earlier one
  abstains on new classes): on v3's test pictures (centre crop) probe 60.6 % top-1, ensemble 62.7 %.
- `ensemble`: the same output from the saved probe (another --old-weight) without training it again.

Default encoder: timm `vit_base_patch16_siglip_224.v2_webli` (SigLIP 2, Google, Apache-2.0).

usage: python teacher.py features --data E:/FoodML_ssd/dataset_v3 --out E:/FoodML_ssd/teacher_v3 --split train \
           --views 4 --old-ckpt D:/FoodML/runs/cls2/best.pt --old-classes classes_v2.csv --classes classes.csv
       (and --split val / test), then python teacher.py probe --data E:/FoodML_ssd/dataset_v3 --out E:/FoodML_ssd/teacher_v3
"""
import argparse
import io
import json
import os
import sys
import time
from concurrent.futures import ThreadPoolExecutor

import numpy as np
import pandas as pd
import timm
import torch
import torch.nn.functional as F
from PIL import Image, ImageOps
from torchvision.transforms import v2 as T
from torchvision.transforms.v2 import functional as TF

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from food_items import fast_path  # noqa: E402
from train import build_model  # noqa: E402

ENCODER = "vit_base_patch16_siglip_224.v2_webli"


def view_params(w, h, rng, scale=(0.25, 1.0), ratio=(3 / 4, 4 / 3)):
    """torchvision RandomResizedCrop.get_params, with our rng: (top, left, height, width)."""
    area = w * h
    for _ in range(10):
        a = area * rng.uniform(*scale)
        r = np.exp(rng.uniform(np.log(ratio[0]), np.log(ratio[1])))
        cw, ch = int(round(np.sqrt(a * r))), int(round(np.sqrt(a / r)))
        if 0 < cw <= w and 0 < ch <= h:
            return int(rng.integers(0, h - ch + 1)), int(rng.integers(0, w - cw + 1)), ch, cw
    s = min(w, h)
    return (h - s) // 2, (w - s) // 2, s, s


class Source:
    """Pictures of a split: from its pack (pack.py) when there is one, else the files."""

    def __init__(self, data, split):
        self.df = pd.read_csv(os.path.join(data, f"{split}.csv"))
        base = os.path.join(data, split)
        self.pack = base + ".bin" if os.path.exists(base + ".bin") else None
        if self.pack:
            self.off = np.load(base + ".idx.npy")
            self.mm = np.memmap(self.pack, dtype=np.uint8, mode="r")

    def __len__(self):
        return len(self.df)

    def image(self, i):
        if self.pack:
            im = Image.open(io.BytesIO(self.mm[self.off[i]:self.off[i + 1]].tobytes()))
        else:
            im = ImageOps.exif_transpose(Image.open(fast_path(self.df.path.iloc[i])))
        return im.convert("RGB")


def features(a):
    device = torch.device("cuda")
    enc = timm.create_model(a.encoder, pretrained=True, num_classes=0).eval().to(device).half()
    cfg = timm.data.resolve_data_config({}, model=enc)
    size = cfg["input_size"][-1]
    mean = torch.tensor(cfg["mean"], device=device).view(1, 3, 1, 1).half()
    std = torch.tensor(cfg["std"], device=device).view(1, 3, 1, 1).half()
    src = Source(a.data, a.split)
    n, V = len(src), a.views if a.split == "train" else 1
    os.makedirs(a.out, exist_ok=True)
    dim = enc.num_features
    feats = np.lib.format.open_memmap(os.path.join(a.out, f"{a.split}_feat.npy"), "w+", np.float16, (n, V, dim))
    params = np.zeros((n, V, 5), np.int32)  # top, left, height, width, flip (on the picture as stored)
    # optional second teacher: an earlier classifier of ours on the same views (its log-probabilities, in this
    # dataset's label order; classes it does not know get -30)
    old = old_map = old_lp = None
    if a.old_ckpt:
        ck = torch.load(a.old_ckpt, map_location="cpu", weights_only=False)
        old = build_model(ck["model"], ck["n_classes"], ck.get("head_hidden", 0), pretrained=False)
        old.load_state_dict(ck["state_dict"])
        old = old.eval().to(device).half()
        old_mean = torch.tensor(ck["mean"], device=device).view(1, 3, 1, 1).half()
        old_std = torch.tensor(ck["std"], device=device).view(1, 3, 1, 1).half()
        old_ids = pd.read_csv(a.old_classes).id.tolist()
        new_ids = pd.read_csv(a.classes).id.tolist()
        old_map = torch.tensor([old_ids.index(c) if c in old_ids else -1 for c in new_ids], device=device)
        old_lp = np.lib.format.open_memmap(os.path.join(a.out, f"{a.split}_old_logp.npy"), "w+", np.float16,
                                           (n, V, len(new_ids)))
    rng = np.random.default_rng(a.seed)
    seeds = rng.integers(0, 2**31, n)
    vsize = max(size, a.old_size if a.old_ckpt else 0)

    def prep(i):
        """Every view as a crop of the picture (view 0: the centre crop of 0.875 x the short side, the eval crop),
        resized to vsize; the encoder gets it resized to its own size on the GPU."""
        im = src.image(i)
        w, h = im.size
        r = np.random.default_rng(seeds[i])
        s0 = int(round(min(w, h) * 0.875))
        p = [((h - s0) // 2, (w - s0) // 2, s0, s0, 0)]
        for _ in range(1, V):
            top, left, ch, cw = view_params(w, h, r)
            p.append((top, left, ch, cw, int(r.random() < 0.5)))
        out = []
        for top, left, ch, cw, flip in p:
            v = TF.resized_crop(im, top, left, ch, cw, [vsize, vsize], interpolation=T.InterpolationMode.BICUBIC,
                                antialias=True)
            out.append(TF.pil_to_tensor(TF.horizontal_flip(v) if flip else v))
        return torch.stack(out), p

    t0 = time.time()
    bs = a.batch
    with ThreadPoolExecutor(a.threads) as pool:
        for s in range(0, n, bs):
            idx = list(range(s, min(n, s + bs)))
            got = list(pool.map(prep, idx))
            x0 = torch.cat([g[0] for g in got]).to(device).half().div_(255)
            x = x0 if vsize == size else F.interpolate(x0, size=(size, size), mode="bilinear", antialias=True)
            with torch.no_grad():
                f = enc((x - mean) / std).float()
                if old is not None:
                    xo = x0 if vsize == a.old_size else F.interpolate(x0, size=(a.old_size, a.old_size),
                                                                      mode="bilinear", antialias=True)
                    lp = torch.log_softmax(old((xo - old_mean) / old_std).float(), 1)
                    lp = torch.where(old_map[None] >= 0, lp[:, old_map.clamp(min=0)], torch.full_like(lp[:, :1], -30.0))
                    old_lp[s:s + len(idx)] = lp.view(len(idx), V, -1).cpu().numpy().astype(np.float16)
            feats[s:s + len(idx)] = f.view(len(idx), V, dim).cpu().numpy().astype(np.float16)
            for k, g in zip(idx, got):
                params[k] = np.array(g[1], np.int32)
            if (s // bs) % 50 == 0:
                print(f"{a.split} {s + len(idx)}/{n} {(s + len(idx)) / (time.time() - t0):.0f} pictures/s", flush=True)
    feats.flush()
    if old_lp is not None:
        old_lp.flush()
    np.save(os.path.join(a.out, f"{a.split}_views.npy"), params)
    with open(os.path.join(a.out, "encoder.json"), "w") as f:
        json.dump({"encoder": a.encoder, "size": size, "mean": cfg["mean"], "std": cfg["std"], "dim": dim}, f)
    print("done", a.split, n, "x", V, flush=True)


def probe(a):
    device = torch.device("cuda")
    tr = np.load(os.path.join(a.out, "train_feat.npy"), mmap_mode="r")
    ytr = pd.read_csv(os.path.join(a.data, "train.csv")).label.to_numpy()
    n, V, D = tr.shape
    C = int(ytr.max()) + 1
    X = torch.from_numpy(np.ascontiguousarray(tr)).to(device)  # float16 [n, V, D]
    probe_path = os.path.join(a.out, "probe.pt")
    if a.step == "ensemble":  # the trained probe again, only the ensemble is recomputed
        st = torch.load(probe_path, weights_only=False)
        mu, sd = st["mu"].to(device), st["sd"].to(device)
        head = torch.nn.Linear(D, C).to(device)
        head.load_state_dict(st["head"])
    else:
        Y = torch.from_numpy(ytr).long().to(device)
        mu = X[:, 0].float().mean(0)
        sd = X[:, 0].float().std(0) + 1e-3
        head = torch.nn.Linear(D, C).to(device)
        opt = torch.optim.AdamW(head.parameters(), lr=a.lr, weight_decay=a.wd)
        counts = np.bincount(ytr, minlength=C)
        w = torch.from_numpy((1.0 / np.sqrt(counts[ytr])).astype(np.float32)).to(device)
        steps = a.epochs * (n // a.pbatch)
        sched = torch.optim.lr_scheduler.OneCycleLR(opt, a.lr, total_steps=steps, pct_start=0.05)
        for s in range(steps):
            i = torch.multinomial(w, a.pbatch, replacement=True)
            v = torch.randint(0, V, (a.pbatch,), device=device)
            x = (X[i, v].float() - mu) / sd
            loss = F.cross_entropy(head(x), Y[i], label_smoothing=0.1)
            opt.zero_grad()
            loss.backward()
            opt.step()
            sched.step()
            if s % 2000 == 0:
                print("step", s, steps, round(loss.item(), 3), flush=True)

    def ensemble(lo, old_lp):
        """Weighted geometric mean of the probe's and the earlier classifier's probabilities (weight a.old_weight);
        the earlier classifier abstains on the classes it does not know (log-prob -30 there)."""
        if old_lp is None:
            return lo
        old_lp = old_lp.float()
        w = a.old_weight * (old_lp > -29).float()
        return (1 - w) * torch.log_softmax(lo, -1) + w * old_lp

    def score(split):
        p = os.path.join(a.out, f"{split}_feat.npy")
        if not os.path.exists(p):
            return None
        x = torch.from_numpy(np.load(p)[:, 0].astype(np.float32)).to(device)
        y = torch.from_numpy(pd.read_csv(os.path.join(a.data, f"{split}.csv")).label.to_numpy()).to(device)
        with torch.no_grad():
            lo = head((x - mu) / sd)
        out = {}
        po = os.path.join(a.out, f"{split}_old_logp.npy")
        for k, z in (("probe", lo), ("ensemble", ensemble(lo, torch.from_numpy(np.load(po)[:, 0]).to(device))
                                                  if os.path.exists(po) else None)):
            if z is not None:
                t5 = z.topk(5, 1).indices
                out[k] = (round(float((t5[:, 0] == y).float().mean()), 4),
                          round(float((t5 == y[:, None]).any(1).float().mean()), 4))
        return out

    res = {s: score(s) for s in ("val", "test", "test_v1")}
    print("teacher probe", json.dumps(res), flush=True)
    po = os.path.join(a.out, "train_old_logp.npy")
    old_lp = np.load(po, mmap_mode="r") if os.path.exists(po) else None
    with torch.no_grad():
        logits = np.zeros((n, V, C), np.float16)
        for s in range(0, n, 4096):
            x = (X[s:s + 4096].float() - mu) / sd
            z = ensemble(head(x), torch.from_numpy(np.ascontiguousarray(old_lp[s:s + 4096])).to(device)
                         if old_lp is not None else None)
            logits[s:s + 4096] = z.cpu().numpy().astype(np.float16)
    np.save(os.path.join(a.out, "train_teacher_logits.npy"), logits)
    if a.step != "ensemble":
        torch.save({"head": head.state_dict(), "mu": mu.cpu(), "sd": sd.cpu(), "result": res}, probe_path)
    with open(os.path.join(a.out, "probe.json"), "w") as f:
        json.dump(res | {"old_weight": a.old_weight}, f, indent=1)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("step", choices=("features", "probe", "ensemble"))
    ap.add_argument("--data", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--split", default="train")
    ap.add_argument("--views", type=int, default=4)
    ap.add_argument("--encoder", default=ENCODER)
    ap.add_argument("--batch", type=int, default=64, help="pictures per batch (x views)")
    ap.add_argument("--threads", type=int, default=12)
    ap.add_argument("--seed", type=int, default=0)
    ap.add_argument("--epochs", type=int, default=6)
    ap.add_argument("--pbatch", type=int, default=1024)
    ap.add_argument("--lr", type=float, default=2e-3)
    ap.add_argument("--wd", type=float, default=1e-4)
    ap.add_argument("--old-ckpt", default="", help="features: also an earlier classifier's predictions per view")
    ap.add_argument("--old-classes", default="", help="its class list (csv, id column)")
    ap.add_argument("--classes", default="", help="this dataset's class list (csv, id column, label order)")
    ap.add_argument("--old-size", type=int, default=256)
    ap.add_argument("--old-weight", type=float, default=0.3, help="probe/ensemble: weight of the earlier classifier")
    a = ap.parse_args()
    features(a) if a.step == "features" else probe(a)


if __name__ == "__main__":
    main()
