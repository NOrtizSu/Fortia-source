# SPDX-License-Identifier: AGPL-3.0-or-later
"""Fine-tune an ImageNet-pretrained mobile backbone on the food dataset (GPU, mixed precision).

Backbones: v1 timm `tf_efficientnet_lite0.in1k` (port of Google's TensorFlow EfficientNet-Lite0 checkpoint,
Apache-2.0; 224 px, mean = std = 0.5); v2 timm `mobilenetv4_conv_medium.e250_r384_in12k_ft_in1k` (Ross Wightman's
ImageNet-12k + 1k weights, Apache-2.0; 256 px, ImageNet mean/std). Both have plain ReLU-type activations and no
squeeze-excite, so they quantize well to int8 TFLite.

- Augmentation for phone photos of food: random resized crop (scale 0.25-1), horizontal flip and 90-degree
  rotations (top-down plates), TrivialAugmentWide (colour, contrast, sharpness, small rotations/shears...) on
  the CPU; normalisation and random erasing batched on the GPU.
- Square-root class-balanced sampling, label smoothing 0.1, AdamW + cosine schedule with warm-up, weight EMA.
- Eval preprocessing (what the app does): resize the short side to --resize (= size / 0.875, bilinear), centre
  crop --size, scale to 0..1, normalise with the backbone's mean/std (v1: 0.5/0.5).
- Progressive resizing (v2): --sizes 224,256 --switch 0.6 trains at 224 for the first 60 % of the epochs.

usage: python train.py --work D:/FoodML --epochs 30 [--out run1] [--resume] [--max-steps N (smoke)]
"""
import argparse
import io
import json
import math
import os
import sys
import time
from concurrent.futures import ThreadPoolExecutor

import numpy as np
import pandas as pd
import timm
import torch
import torch.nn.functional as F
from PIL import Image
from timm.utils import ModelEmaV3
from torch.utils.data import DataLoader, Dataset, IterableDataset, WeightedRandomSampler, get_worker_info
from torchvision.transforms import v2 as T
from torchvision.transforms.v2 import functional as TF

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from food_items import fast_path  # noqa: E402

MEAN = (0.5, 0.5, 0.5)  # set from the backbone by configure()
STD = (0.5, 0.5, 0.5)
SIZE = 224
RESIZE = 256  # = round(SIZE / 0.875)
THREADS_PER_WORKER = 8


def configure(size=None, resize=None, mean=None, std=None):
    """Sets the module's preprocessing constants (used by the transforms below and by evaluate/export/parity)."""
    global SIZE, RESIZE, MEAN, STD
    if size:
        SIZE = int(size)
        RESIZE = int(resize) if resize else int(round(SIZE / 0.875))
    if mean is not None:
        MEAN, STD = tuple(float(x) for x in mean), tuple(float(x) for x in std)


def configure_from_checkpoint(ck):
    """v2 checkpoints carry their preprocessing; v1 ones use the defaults (224 / 256 / 0.5)."""
    configure(ck.get("size", 224), ck.get("resize", 256), ck.get("mean", (0.5,) * 3), ck.get("std", (0.5,) * 3))


def narrow_head(model, hidden, keep=None):
    """v3 (size budget): MobileNet's head (global pool -> 1x1 conv_head -> BN + ReLU -> classifier) with `hidden`
    units instead of 1 280; `keep` = the indices of the units kept (their weights are copied), else new ones."""
    import torch.nn as nn
    from timm.layers.norm_act import BatchNormAct2d
    old_c, old_n, old_l = model.conv_head, model.norm_head, model.get_classifier()
    keep = torch.arange(hidden) if keep is None else torch.as_tensor(keep)
    conv = nn.Conv2d(old_c.in_channels, hidden, 1, bias=old_c.bias is not None)
    norm = BatchNormAct2d(hidden, eps=old_n.eps, act_layer=type(old_n.act))
    lin = nn.Linear(hidden, old_l.out_features)
    with torch.no_grad():
        conv.weight.copy_(old_c.weight[keep])
        for k in ("weight", "bias", "running_mean", "running_var"):
            getattr(norm, k).copy_(getattr(old_n, k)[keep])
        lin.weight.copy_(old_l.weight[:, keep])
        lin.bias.copy_(old_l.bias)
    model.conv_head, model.norm_head, model.classifier = conv, norm, lin
    model.head_hidden_size = hidden
    return model


def build_model(name, n_classes, head_hidden=0, **kw):
    """timm model (+ narrow_head when a checkpoint says `head_hidden`): use for every checkpoint of ours."""
    model = timm.create_model(name, num_classes=n_classes, **kw)
    if head_hidden and head_hidden != model.conv_head.out_channels:
        narrow_head(model, head_hidden)
    return model


def model_from_checkpoint(ck):
    model = build_model(ck["model"], ck["n_classes"], ck.get("head_hidden", 0), pretrained=False)
    model.load_state_dict(ck["state_dict"])
    return model


class Rot90:
    def __call__(self, img):
        k = np.random.randint(4)
        return img if k == 0 else img.rotate(90 * k, expand=True)


def train_tf(size=None):
    """CPU part of the training augmentation; returns uint8 CHW (gpu_normalize + random erasing on the GPU)."""
    return T.Compose([
        T.RandomResizedCrop(size or SIZE, scale=(0.25, 1.0), ratio=(3 / 4, 4 / 3), antialias=True),
        T.RandomHorizontalFlip(),
        T.RandomApply([Rot90()], p=0.25),
        T.TrivialAugmentWide(),
        T.ToImage(),
    ])


class ViewTf:
    """v3 distillation: the crop and flip of a stored teacher view (teacher.py), then the rest of train_tf."""

    def __init__(self, size):
        self.size = size
        self.rest = T.Compose([T.RandomApply([Rot90()], p=0.25), T.TrivialAugmentWide(), T.ToImage()])

    def __call__(self, img, view):
        top, left, h, w, flip = (int(v) for v in view)
        img = TF.resized_crop(img, top, left, h, w, [self.size, self.size], antialias=True)
        if flip:
            img = TF.horizontal_flip(img)
        return self.rest(img)


def eval_tf():
    """The app's preprocessing up to the crop; returns uint8 CHW (gpu_normalize does /255 and mean/std)."""
    return T.Compose([
        T.Resize(RESIZE, interpolation=T.InterpolationMode.BILINEAR, antialias=True),
        T.CenterCrop(SIZE),
        T.ToImage(),
    ])


def gpu_normalize(x):
    """uint8 NCHW on the GPU -> float, 0..1, then (x - mean) / std."""
    m = torch.tensor(MEAN, device=x.device).view(1, 3, 1, 1)
    s = torch.tensor(STD, device=x.device).view(1, 3, 1, 1)
    return (x.float() / 255.0 - m) / s


def gpu_random_erasing(x, p=0.25, scale=(0.02, 0.15), ratio=(0.3, 3.3)):
    """Random erasing (one rectangle of normal noise in a fraction p of the images), batched on the GPU."""
    n, _, h, w = x.shape
    area = torch.empty(n).uniform_(*scale) * h * w
    logr = torch.empty(n).uniform_(math.log(ratio[0]), math.log(ratio[1]))
    eh = (area * torch.exp(logr)).sqrt().clamp(1, h).long()
    ew = (area / torch.exp(logr)).sqrt().clamp(1, w).long()
    top = (torch.rand(n) * (h - eh + 1).float()).long()
    left = (torch.rand(n) * (w - ew + 1).float()).long()
    on = torch.rand(n) < p
    ys = torch.arange(h).view(1, h, 1)
    xs = torch.arange(w).view(1, 1, w)
    mask = ((ys >= top.view(n, 1, 1)) & (ys < (top + eh).view(n, 1, 1)) &
            (xs >= left.view(n, 1, 1)) & (xs < (left + ew).view(n, 1, 1)) & on.view(n, 1, 1))
    mask = mask.to(x.device).unsqueeze(1)
    return torch.where(mask, torch.randn_like(x), x)


class Photos(Dataset):
    """Photos of a split; read from the split's pack (pack.py) when there is one and packed=True."""

    def __init__(self, csv_path, tf, packed=True):
        df = pd.read_csv(csv_path)
        self.paths = df.path.tolist()
        self.labels = df.label.to_numpy()
        self.tf = tf
        base = os.path.splitext(csv_path)[0]
        self.pack = base + ".bin" if packed and os.path.exists(base + ".bin") else None
        self.offsets = np.load(base + ".idx.npy") if self.pack else None

    def __len__(self):
        return len(self.paths)

    def _open(self, i):
        if self.pack is None:
            return Image.open(fast_path(self.paths[i]))
        if not hasattr(self, "_mm"):
            # small packs (val) are read into memory once; big ones are memory-mapped
            big = os.path.getsize(self.pack) > 600 * 2**20
            self._mm = np.memmap(self.pack, dtype=np.uint8, mode="r") if big else np.fromfile(self.pack, np.uint8)
        return Image.open(io.BytesIO(self._mm[self.offsets[i]:self.offsets[i + 1]].tobytes()))

    def __getitem__(self, i):
        with self._open(i) as im:
            img = im.convert("RGB")
        return self.tf(img), int(self.labels[i])

    def __getitems__(self, indices):
        # A DataLoader worker process fetches a whole batch with a few threads (PIL releases the GIL).
        # On Windows every worker process maps the CUDA DLLs (~1.5 GB of commit each), so few processes with
        # threads inside go further than many processes.
        if not hasattr(self, "_pool"):
            self._pool = ThreadPoolExecutor(THREADS_PER_WORKER)
        return list(self._pool.map(self.__getitem__, indices))

    def __getstate__(self):
        d = dict(self.__dict__)
        d.pop("_pool", None)
        d.pop("_mm", None)
        return d


class PackStream(IterableDataset):
    """Training stream over a pack, read in big sequential blocks (the work disk is a hard disk: random reads of
    small photos ran at ~150 photos/s, sequential blocks at thousands).

    The pack is already in random order. Each epoch: blocks of `block` consecutive photos in a new random
    order, split between the workers; `group` blocks are loaded together and their photos shuffled. Class
    balance: each photo is repeated Poisson(weight) times, weight = n_class^-0.5 scaled so that an epoch has
    about as many samples as photos (square-root balancing, sampling with replacement)."""

    def __init__(self, csv_path, tf, block=512, group=4, seed=0, boost=None, views=None):
        base = os.path.splitext(csv_path)[0]
        self.pack = base + ".bin"
        self.offsets = np.load(base + ".idx.npy")
        self.labels = pd.read_csv(csv_path).label.to_numpy()
        counts = np.bincount(self.labels)
        w = 1.0 / np.sqrt(counts[self.labels])
        for label, factor in (boost or {}).items():  # e.g. sample _not_food more often
            w[self.labels == label] *= factor
        self.rate = w * len(w) / w.sum()
        self.tf, self.block, self.group, self.seed = tf, block, group, seed
        self.views = views  # [n, V, 5] teacher views (crop on the packed picture): tf is then a ViewTf
        self.epoch = 0

    def __len__(self):
        return len(self.labels)

    def _item(self, args):
        """(picture, label, code): code = i * V + v, the teacher view used (-1 without a teacher)."""
        buf, start, i = args
        with Image.open(io.BytesIO(buf[self.offsets[i] - start:self.offsets[i + 1] - start])) as im:
            img = im.convert("RGB")
        if self.views is None:
            return self.tf(img), int(self.labels[i]), -1
        V = self.views.shape[1]
        v = np.random.randint(V)
        return self.tf(img, self.views[i, v]), int(self.labels[i]), int(i * V + v)

    def __iter__(self):
        info = get_worker_info()
        wid, nw = (info.id, info.num_workers) if info else (0, 1)
        rng = np.random.default_rng((self.seed, self.epoch))  # same block order in every worker
        self.epoch += 1
        n = len(self.labels)
        blocks = rng.permutation((n + self.block - 1) // self.block)[wid::nw]
        rng = np.random.default_rng((self.seed, self.epoch, wid))
        pool = ThreadPoolExecutor(THREADS_PER_WORKER)
        with open(self.pack, "rb") as f:
            for g in range(0, len(blocks), self.group):
                items = []
                for b in sorted(blocks[g:g + self.group]):
                    lo, hi = b * self.block, min(n, (b + 1) * self.block)
                    f.seek(self.offsets[lo])
                    buf = f.read(int(self.offsets[hi] - self.offsets[lo]))
                    idx = np.arange(lo, hi)
                    reps = rng.poisson(self.rate[idx])
                    items += [(buf, int(self.offsets[lo]), int(i)) for i in np.repeat(idx, reps)]
                rng.shuffle(items)
                for k in range(0, len(items), 64):
                    yield from pool.map(self._item, items[k:k + 64])
        pool.shutdown()


def param_groups(model, wd, lr, head_lr=0.0):
    """AdamW groups: no weight decay on biases and norm weights; the classifier at head_lr (if given)."""
    head = {id(p) for p in model.get_classifier().parameters()} if head_lr else set()
    groups = {}
    for n, p in model.named_parameters():
        key = ("head" if id(p) in head else "body", p.ndim <= 1 or n.endswith(".bias"))
        groups.setdefault(key, []).append(p)
    return [{"params": ps, "weight_decay": 0.0 if nd else wd, "lr": head_lr if part == "head" else lr}
            for (part, nd), ps in groups.items()]


@torch.no_grad()
def evaluate(model, loader, device, n_classes):
    model.eval()
    logits, labels = [], []
    for x, y in loader:
        x = gpu_normalize(x.to(device, non_blocking=True))
        with torch.autocast("cuda", dtype=torch.float16):
            out = model(x)
        logits.append(out.float().cpu())
        labels.append(y)
    logits, labels = torch.cat(logits), torch.cat(labels)
    top5 = logits.topk(5, dim=1).indices
    t1 = (top5[:, 0] == labels).float().mean().item()
    t5 = (top5 == labels[:, None]).any(1).float().mean().item()
    return t1, t5, logits, labels


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--work", default="D:/FoodML")
    ap.add_argument("--dataset", default="dataset", help="under --work")
    ap.add_argument("--out", default="run1")
    ap.add_argument("--model", default="tf_efficientnet_lite0.in1k")
    ap.add_argument("--epochs", type=int, default=25)
    ap.add_argument("--batch", type=int, default=64)
    ap.add_argument("--lr", type=float, default=3e-4)
    ap.add_argument("--wd", type=float, default=0.02)
    ap.add_argument("--warmup", type=float, default=1.0, help="epochs")
    ap.add_argument("--workers", type=int, default=2)
    ap.add_argument("--max-steps", type=int, default=0, help="stop early (smoke checkpoint)")
    ap.add_argument("--ema", type=float, default=0.9998)
    ap.add_argument("--resume", action="store_true", help="continue from <run>/state.pt")
    ap.add_argument("--init", default="", help="start from these weights (a checkpoint of an earlier run)")
    ap.add_argument("--boost-not-food", type=float, default=1.0,
                    help="sample `_not_food` photos this many times more often")
    ap.add_argument("--size", type=int, default=224, help="final training / eval size")
    ap.add_argument("--resize", type=int, default=0, help="eval short side (default size / 0.875)")
    ap.add_argument("--sizes", default="", help="progressive resizing, e.g. 224,256 (the last = --size)")
    ap.add_argument("--switch", type=float, default=0.6, help="fraction of the epochs at the first size")
    ap.add_argument("--norm", default="model", choices=("model", "half"),
                    help="mean/std: the backbone's own (model) or 0.5/0.5 (half, v1)")
    ap.add_argument("--runs", default="", help="runs folder (default <work>/runs)")
    ap.add_argument("--drop-path", type=float, default=0.1)
    ap.add_argument("--head-lr", type=float, default=0.0,
                    help="learning rate of the new classifier (default: --lr). MobileNetV4 needs a low backbone lr "
                         "(~5e-5) with a 10-20x higher head lr, else its pretrained features are lost early")
    ap.add_argument("--zero-head", action="store_true",
                    help="start the classifier at zero (no large random logits, no huge first gradients)")
    ap.add_argument("--init-classes", default="", help="v3: the class list (csv, id column) of the --init checkpoint; "
                    "its classifier rows are copied to the same ids of --classes, new classes start at zero")
    ap.add_argument("--classes", default="", help="v3: this run's class list (csv in label order)")
    ap.add_argument("--teacher", default="", help="v3: teacher.py folder (train_views.npy, train_teacher_logits.npy)")
    ap.add_argument("--kd-alpha", type=float, default=0.7, help="weight of the distillation loss")
    ap.add_argument("--kd-t", type=float, default=1.0, help="distillation temperature")
    ap.add_argument("--head-hidden", type=int, default=0, help="v3: narrow the 1 280-unit head (size budget)")
    a = ap.parse_args()
    torch.manual_seed(0)
    np.random.seed(0)
    torch.backends.cudnn.benchmark = True
    device = torch.device("cuda")
    ds_dir = os.path.join(a.work, a.dataset)
    out = os.path.join(a.runs or os.path.join(a.work, "runs"), a.out)
    os.makedirs(out, exist_ok=True)

    train_csv = os.path.join(ds_dir, "train.csv")
    probe = timm.create_model(a.model, pretrained=False)
    cfg = probe.pretrained_cfg
    del probe
    mean, std = (cfg.get("mean", MEAN), cfg.get("std", STD)) if a.norm == "model" else ((0.5,) * 3, (0.5,) * 3)
    configure(a.size, a.resize or None, mean, std)
    sizes = [int(x) for x in a.sizes.split(",")] if a.sizes else [a.size]
    switch_epoch = int(round(a.switch * a.epochs)) if len(sizes) > 1 else 0
    print(f"size {SIZE} (resize {RESIZE}), mean {MEAN}, std {STD}, sizes {sizes} switch at {switch_epoch}",
          flush=True)
    val_ds = Photos(os.path.join(ds_dir, "val.csv"), eval_tf())
    views = teacher_logits = None
    if a.teacher:
        views = np.load(os.path.join(a.teacher, "train_views.npy"))
        tl = np.load(os.path.join(a.teacher, "train_teacher_logits.npy"), mmap_mode="r")
        assert tl.shape[0] == len(pd.read_csv(train_csv)), "teacher made for another train.csv"
        teacher_logits = torch.from_numpy(np.ascontiguousarray(tl).reshape(-1, tl.shape[-1])).to(device)
        print(f"teacher: {tl.shape[1]} views per picture, alpha {a.kd_alpha}, T {a.kd_t}", flush=True)

    def make_loader(size, first_epoch=0):
        not_food = int(pd.read_csv(train_csv).label.max())  # `_not_food` is the last label
        ds = PackStream(train_csv, ViewTf(size) if views is not None else train_tf(size),
                        boost={not_food: a.boost_not_food}, views=views)
        ds.epoch = first_epoch
        return ds, DataLoader(ds, a.batch, num_workers=a.workers, pin_memory=False, drop_last=True,
                              persistent_workers=True, prefetch_factor=3)

    if os.path.exists(os.path.splitext(train_csv)[0] + ".bin"):
        train_ds, train_dl = make_loader(sizes[0])
    else:
        train_ds = Photos(train_csv, train_tf())
        w = (1.0 / np.sqrt(np.bincount(train_ds.labels)))[train_ds.labels]
        sampler = WeightedRandomSampler(torch.as_tensor(w, dtype=torch.double), num_samples=len(train_ds),
                                        replacement=True)
        train_dl = DataLoader(train_ds, a.batch, sampler=sampler, num_workers=a.workers, pin_memory=False,
                              drop_last=True, persistent_workers=True, prefetch_factor=3)
    n_classes = int(max(train_ds.labels.max(), val_ds.labels.max())) + 1
    val_dl = DataLoader(val_ds, 256, shuffle=False, num_workers=0)  # threads in this process
    print(f"{len(train_ds)} train / {len(val_ds)} val photos, {n_classes} classes", flush=True)

    model = timm.create_model(a.model, pretrained=not a.init, num_classes=n_classes, drop_rate=0.2,
                              drop_path_rate=a.drop_path)
    if a.init:
        sd = torch.load(a.init, map_location="cpu", weights_only=False)["state_dict"]
        if a.init_classes:  # class lists differ: copy the classifier rows by class id
            old = pd.read_csv(a.init_classes).id.tolist()
            new = pd.read_csv(a.classes).id.tolist()
            assert len(new) == n_classes, (len(new), n_classes)
            wk = [k for k in sd if k.endswith("classifier.weight")][0]
            bk = wk[:-len("weight")] + "bias"
            w = torch.zeros(n_classes, sd[wk].shape[1])
            b = torch.zeros(n_classes)
            for j, cid in enumerate(new):
                if cid in old:
                    w[j], b[j] = sd[wk][old.index(cid)], sd[bk][old.index(cid)]
                else:  # a new class starts as the average class (its logit = the mean logit: never the top one)
                    w[j], b[j] = sd[wk].mean(0), sd[bk].mean()
            sd[wk], sd[bk] = w, b
            print(f"init from {a.init}: {sum(c in old for c in new)} classes copied, "
                  f"{sum(c not in old for c in new)} new", flush=True)
        model.load_state_dict(sd)
    elif a.zero_head:
        torch.nn.init.zeros_(model.get_classifier().weight)
        torch.nn.init.zeros_(model.get_classifier().bias)
    if a.head_hidden and a.head_hidden < model.conv_head.out_channels:
        if a.init and not a.resume:
            # keep the head units that matter most (mean activation on training pictures x weight to the classes),
            # then refit the classifier on the kept units to reproduce the full head's logits (least squares), so
            # training starts close to the trained model instead of from a crippled head
            feats = []
            h = model.norm_head.register_forward_hook(lambda m, i, o: feats.append(o.detach().float().flatten(1).cpu()))
            model.to(device).eval()
            with torch.no_grad():
                for k, batch in enumerate(train_dl):
                    with torch.autocast("cuda", dtype=torch.float16):
                        model(gpu_normalize(batch[0].to(device)))
                    if k >= 60:
                        break
            h.remove()
            f = torch.cat(feats)  # [n, 1280] pre-logits (after BN + ReLU, pooled)
            lin = model.get_classifier()
            z = f @ lin.weight.detach().cpu().T + lin.bias.detach().cpu()
            imp = f.mean(0) * lin.weight.detach().cpu().norm(dim=0)
            keep = imp.argsort(descending=True)[:a.head_hidden].sort().values
            narrow_head(model.cpu(), a.head_hidden, keep)
            fk = torch.cat([f[:, keep], torch.ones(len(f), 1)], 1)
            sol = torch.linalg.solve(fk.T @ fk + 1e-2 * torch.eye(fk.shape[1]), fk.T @ z)
            with torch.no_grad():
                model.get_classifier().weight.copy_(sol[:-1].T)
                model.get_classifier().bias.copy_(sol[-1])
            fit = 1 - float(((fk @ sol - z) ** 2).mean() / z.var(0).mean())
            print(f"head narrowed to {a.head_hidden} units (kept {float(imp[keep].sum() / imp.sum()):.3f} of the "
                  f"importance; classifier refit on {len(f)} pictures, logits R^2 {fit:.3f})", flush=True)
        else:
            narrow_head(model, a.head_hidden)
    model.to(device)
    ema = ModelEmaV3(model, decay=a.ema)
    opt = torch.optim.AdamW(param_groups(model, a.wd, a.lr, a.head_lr), lr=a.lr, betas=(0.9, 0.999))
    steps_per_epoch = len(train_dl)
    total = a.epochs * steps_per_epoch
    warm = int(a.warmup * steps_per_epoch)
    sched = torch.optim.lr_scheduler.LambdaLR(
        opt, lambda s: (s + 1) / warm if s < warm else
        0.5 * (1 + math.cos(math.pi * min(1.0, (s - warm) / max(1, total - warm)))) * 0.99 + 0.01)
    scaler = torch.amp.GradScaler()
    with open(os.path.join(out, "args.json"), "w") as f:
        json.dump(vars(a) | {"n_classes": n_classes, "train": len(train_ds), "mean": MEAN, "std": STD,
                             "resize": RESIZE}, f, indent=1)

    best, step, log, first = 0.0, 0, [], 0
    state_path = os.path.join(out, "state.pt")
    if a.resume and os.path.exists(state_path):
        st = torch.load(state_path, map_location=device, weights_only=False)
        model.load_state_dict(st["model"])
        ema.module.load_state_dict(st["ema"])
        opt.load_state_dict(st["opt"])
        scaler.load_state_dict(st["scaler"])
        sched.load_state_dict(st["sched"])
        best, step, log, first = st["best"], st["step"], st["log"], st["epoch"] + 1
        if hasattr(train_ds, "epoch"):
            train_ds.epoch = first  # PackStream: next epoch's block order (the workers start after this)
        print(f"resumed after epoch {st['epoch']} (step {step})", flush=True)
    cur_size = sizes[0]
    for epoch in range(first, a.epochs):
        want = sizes[-1] if epoch >= switch_epoch and len(sizes) > 1 else sizes[0]
        if want != cur_size:
            del train_dl
            train_ds, train_dl = make_loader(want, epoch)
            cur_size = want
            print(f"training size {want} from epoch {epoch}", flush=True)
        model.train()
        t0, seen, loss_sum = time.time(), 0, 0.0
        for batch in train_dl:
            x, y = batch[0], batch[1]
            x = gpu_random_erasing(gpu_normalize(x.to(device, non_blocking=True)))
            y = y.to(device, non_blocking=True)
            with torch.autocast("cuda", dtype=torch.float16):
                logits = model(x)
                loss = F.cross_entropy(logits, y, label_smoothing=0.1)
                if teacher_logits is not None:
                    t = teacher_logits[batch[2].to(device, non_blocking=True)].float() / a.kd_t
                    kd = F.kl_div(F.log_softmax(logits.float() / a.kd_t, 1), F.log_softmax(t, 1), log_target=True,
                                  reduction="batchmean") * a.kd_t ** 2
                    loss = (1 - a.kd_alpha) * loss + a.kd_alpha * kd
            opt.zero_grad(set_to_none=True)
            scaler.scale(loss).backward()
            scaler.unscale_(opt)
            torch.nn.utils.clip_grad_norm_(model.parameters(), 5.0)
            scaler.step(opt)
            scaler.update()
            sched.step()
            ema.update(model, step=step)
            step += 1
            seen += x.shape[0]
            loss_sum += loss.item() * x.shape[0]
            if step % 200 == 0:
                print(f"ep {epoch} step {step}/{total} loss {loss_sum / seen:.3f} "
                      f"{seen / (time.time() - t0):.0f} img/s lr {sched.get_last_lr()[0]:.2e}", flush=True)
            if a.max_steps and step >= a.max_steps:
                break
        t1, t5, _, _ = evaluate(ema.module, val_dl, device, n_classes)
        m1, m5, _, _ = evaluate(model, val_dl, device, n_classes)
        rec = {"epoch": epoch, "loss": loss_sum / max(1, seen), "ema_top1": t1, "ema_top5": t5,
               "top1": m1, "top5": m5, "sec": time.time() - t0}
        log.append(rec)
        print(json.dumps(rec), flush=True)
        ck = {"model": a.model, "n_classes": n_classes, "epoch": epoch, "size": SIZE, "resize": RESIZE,
              "mean": list(MEAN), "std": list(STD), "head_hidden": int(model.conv_head.out_channels)}
        torch.save(ck | {"state_dict": ema.module.state_dict()}, os.path.join(out, "last_ema.pt"))
        if max(t1, m1) > best:
            best = max(t1, m1)
            sd = (ema.module if t1 >= m1 else model).state_dict()
            torch.save(ck | {"state_dict": sd, "val_top1": best, "ema": t1 >= m1}, os.path.join(out, "best.pt"))
        with open(os.path.join(out, "log.json"), "w") as f:
            json.dump(log, f, indent=1)
        torch.save({"model": model.state_dict(), "ema": ema.module.state_dict(), "opt": opt.state_dict(),
                    "scaler": scaler.state_dict(), "sched": sched.state_dict(), "best": best, "step": step,
                    "log": log, "epoch": epoch}, state_path + ".tmp")
        os.replace(state_path + ".tmp", state_path)
        if a.max_steps and step >= a.max_steps:
            break
    print("best val top1", best)


if __name__ == "__main__":
    main()
