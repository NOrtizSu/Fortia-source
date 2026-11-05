# SPDX-License-Identifier: AGPL-3.0-or-later
"""Simulate TFLite full-int8 inference of the classifier in PyTorch (GPU) to find what breaks and test fixes fast.

TFLite int8 (what the converter produces from the float model): every conv/linear has its BatchNorm folded in and
**per-output-channel symmetric int8 weights**; every activation tensor (the input, each conv+BN(+ReLU) output, each
residual ADD, the global pool, the head) is **per-tensor asymmetric int8**, its range = min/max over the
calibration photos. Here: BN folded the same way, weights fake-quantised per channel, forward hooks fake-quantise
the same tensors with ranges calibrated on photos. (TFLite rounding details differ slightly; the simulation is for
finding the cause, the real TFLite file is measured by parity.py.)

Fix tested (`--smooth`): an exact re-parametrisation before export. Each residual stream of MobileNetV4 (all the
blocks of a stage add into it) can carry a few channels with values in the hundreds or thousands while the others
are a few units; one per-tensor int8 scale then rounds the normal channels to zero. Every writer of a stream is a
linear conv (pw_proj + BN, no activation) and every reader starts with a linear op (a depthwise conv without
activation or a 1×1 conv), so channel c of the stream can be divided by d_c (writers' output rows and biases / d_c)
and multiplied back in the readers (a reader 1×1 conv's input column c × d_c; a depthwise reader keeps its kernel,
its bias / d_c, and the 1×1 conv after it takes the × d_c) with the same function in float. d_c =
(max|x_c|)^α / (max|w_c|)^(1−α) (SmoothQuant, α = 0.5), normalised.

usage: python quant_sim.py --ckpt D:/FoodML/runs/cls2/final.pt --data E:/FoodML_ssd/dataset_v2 [--smooth 0.5]
"""
import argparse
import copy
import os
import sys

import numpy as np
import pandas as pd
import timm
import torch
import torch.nn as nn
from PIL import Image
from timm.layers.norm_act import BatchNormAct2d
from torchvision.transforms import v2 as T

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from food_items import fast_path  # noqa: E402
from train import build_model  # noqa: E402


# ---------------------------------------------------------------- BN folding
_BIAS = {}  # id(conv) -> the BN whose bias now holds the conv's folded bias


def fold_bn(conv, bn):
    """Folds an eval-mode BatchNorm(Act) into the conv before it: the conv takes the scale, the BN keeps only the
    bias (weight 1, mean 0, var 1 - eps: x + b). Same modules and state-dict keys as before (timm loads it)."""
    s = bn.weight.detach() / torch.sqrt(bn.running_var + bn.eps)
    w = conv.weight.detach() * s.view(-1, *([1] * (conv.weight.ndim - 1)))
    b = (conv.bias.detach() if conv.bias is not None else torch.zeros_like(s)) * s + bn.bias.detach() - bn.running_mean * s
    conv.weight.data.copy_(w)
    if conv.bias is not None:
        conv.bias.data.zero_()
    bn.weight.data.fill_(1.0)
    bn.bias.data.copy_(b)
    bn.running_mean.zero_()
    bn.running_var.fill_(1.0 - bn.eps)
    _BIAS[id(conv)] = bn


def bias_of(conv):
    """The (folded) bias of a conv: its BN's bias after fold_bn."""
    return _BIAS[id(conv)].bias


def conv_bn_pairs(model):
    """(conv, bn) pairs of timm's MobileNetV4: ConvNormAct (.conv/.bn), EdgeResidual (conv_exp/bn1, conv_pwl/bn2),
    stem (conv_stem/bn1), head (conv_head/norm_head)."""
    pairs = []
    for m in model.modules():
        if hasattr(m, "conv") and hasattr(m, "bn") and isinstance(m.bn, BatchNormAct2d):
            pairs.append((m.conv, m.bn))
        if hasattr(m, "conv") and hasattr(m, "bn1") and isinstance(m.bn1, BatchNormAct2d):  # ConvBnAct
            pairs.append((m.conv, m.bn1))
        if hasattr(m, "conv_exp") and hasattr(m, "bn1"):
            pairs.append((m.conv_exp, m.bn1))
        if hasattr(m, "conv_pwl") and hasattr(m, "bn2"):
            pairs.append((m.conv_pwl, m.bn2))
    pairs.append((model.conv_stem, model.bn1))
    if isinstance(getattr(model, "norm_head", None), BatchNormAct2d):
        pairs.append((model.conv_head, model.norm_head))
    return pairs


def fold_all(model):
    for c, b in conv_bn_pairs(model):
        fold_bn(c, b)
    return model


# ---------------------------------------------------------------- the residual-stream re-parametrisation
def stages(model):
    return list(model.blocks)


def block_reader(block):
    """(first linear op reading the block input, the 1x1 conv after it if the first is depthwise)."""
    if hasattr(block, "dw_start") and not isinstance(block.dw_start, nn.Identity):
        return block.dw_start.conv, block.pw_exp.conv
    if hasattr(block, "pw_exp"):
        return block.pw_exp.conv, None
    if hasattr(block, "conv_exp"):
        return block.conv_exp, None
    if hasattr(block, "conv"):
        return block.conv, None
    raise ValueError(type(block))


def block_writer(block):
    if hasattr(block, "pw_proj"):
        return block.pw_proj.conv
    if hasattr(block, "conv_pwl"):
        return block.conv_pwl
    if hasattr(block, "conv"):
        return block.conv
    raise ValueError(type(block))


@torch.no_grad()
def stream_stats(model, x_batches, device):
    """Per-channel max |x| of every stage's output stream (= each block output inside a stage)."""
    st = stages(model)
    stats = [None] * len(st)
    hooks = []
    for si, stage in enumerate(st):
        for blk in stage:
            def h(mod, inp, out, si=si):
                m = out.detach().abs().amax(dim=(0, 2, 3)).float()
                stats[si] = m if stats[si] is None else torch.maximum(stats[si], m)
            hooks.append(blk.register_forward_hook(h))
    for x in x_batches:
        model(x.to(device))
    for h in hooks:
        h.remove()
    return stats


@torch.no_grad()
def smooth_streams(model, x_batches, device, alpha=0.5, min_stage=1, inner=False, stream_alpha=None):
    """Applies the exact per-channel re-parametrisation to the residual streams of the stages >= min_stage that
    have skip blocks. Returns {stage: (max before, max after)}."""
    st = stages(model)
    stats = stream_stats(model, x_batches, device)
    report = {}
    for si in range(min_stage, len(st)):
        stage = st[si]
        if not any(getattr(b, "has_skip", False) for b in stage):
            continue
        amax = stats[si].clamp(min=1e-5)
        # readers: every block after the first reads the stream; the next stage's first block (or the head conv)
        readers = [block_reader(b) for b in list(stage)[1:]]
        nxt = st[si + 1][0] if si + 1 < len(st) else None
        readers.append(block_reader(nxt) if nxt is not None else (model.conv_head, None))
        # weight magnitude per input channel over the 1x1 readers (the column the scale multiplies)
        wmax = torch.zeros_like(amax)
        for first, after in readers:
            pw = after if after is not None else first
            if pw.groups == 1:
                wmax = torch.maximum(wmax, pw.weight.abs().amax(dim=(0, 2, 3)))
        sa = alpha if stream_alpha is None else stream_alpha.get(si, alpha)
        d = amax.pow(sa) / wmax.clamp(min=1e-5).pow(1 - sa)
        d = d / d.median()
        d = d.clamp(min=1.0)  # only shrink channels (never blow up a small channel's quantisation range)
        writers = [block_writer(b) for b in stage]
        for w in writers:
            w.weight.data /= d.view(-1, 1, 1, 1)
            bias_of(w).data /= d
        for first, after in readers:
            if after is None:  # 1x1 / full conv reads the stream: its input column c x d_c
                first.weight.data *= d.view(1, -1, 1, 1)
            else:  # depthwise reads it: its output is scaled down too, the 1x1 after it takes x d_c
                bias_of(first).data /= d
                after.weight.data *= d.view(1, -1, 1, 1)
        report[si] = (float(amax.max()), float((amax / d).max()), float(d.max()))
    # the head: the last conv's ReLU output (960 channels, pooled) -> conv_head. ReLU and the mean pool commute with
    # a positive per-channel scale, so channel c can be divided by d_c there and multiplied back in conv_head
    last = st[-1][-1]
    if hasattr(last, "conv") and not getattr(last, "has_skip", False):
        stats_h = []
        h = last.register_forward_hook(lambda m, i, o: stats_h.append(o.detach().amax(dim=(0, 2, 3)).float()))
        for x in x_batches:
            model(x.to(device))
        h.remove()
        amax = torch.stack(stats_h).amax(0).clamp(min=1e-5)
        wmax = model.conv_head.weight.abs().amax(dim=(0, 2, 3)).clamp(min=1e-5)
        d = amax.pow(alpha) / wmax.pow(1 - alpha)
        d = (d / d.median()).clamp(min=1.0)
        last.conv.weight.data /= d.view(-1, 1, 1, 1)
        bias_of(last.conv).data /= d
        model.conv_head.weight.data *= d.view(1, -1, 1, 1)
        report["head"] = (float(amax.max()), float((amax / d).max()), float(d.max()))
    if inner:
        report.update(smooth_blocks(model, x_batches, device, alpha))
    return report


def _smooth_factor(amax, wmax, alpha):
    d = amax.clamp(min=1e-5).pow(alpha) / wmax.clamp(min=1e-5).pow(1 - alpha)
    return (d / d.median()).clamp(min=1.0)


@torch.no_grad()
def smooth_blocks(model, x_batches, device, alpha=0.5):
    """The same exact re-parametrisation inside every inverted-residual block:
    - the depthwise start conv's output (no activation) -> divided per channel, the 1x1 expansion's columns x d;
    - the expanded channels (1x1 expansion + ReLU, then the optional depthwise + ReLU) -> the expansion's rows and
      biases and the middle depthwise's biases / d (ReLU and a depthwise conv commute with a positive per-channel
      scale), the 1x1 projection's columns x d."""
    blocks = [b for st in stages(model) for b in st if hasattr(b, "pw_exp")]
    stats = {}
    hooks = []

    def rec(key):
        def h(m, i, o):
            v = o.detach().abs().amax(dim=(0, 2, 3)).float()
            stats[key] = v if key not in stats else torch.maximum(stats[key], v)
        return h
    for k, b in enumerate(blocks):
        if not isinstance(b.dw_start, nn.Identity):
            hooks.append(b.dw_start.register_forward_hook(rec((k, "start"))))
        hooks.append(b.pw_exp.register_forward_hook(rec((k, "exp"))))
        if not isinstance(b.dw_mid, nn.Identity):
            hooks.append(b.dw_mid.register_forward_hook(rec((k, "mid"))))
    for x in x_batches:
        model(x.to(device))
    for h in hooks:
        h.remove()
    rep = {}
    for k, b in enumerate(blocks):
        if (k, "start") in stats:
            d = _smooth_factor(stats[(k, "start")], b.pw_exp.conv.weight.abs().amax(dim=(0, 2, 3)), alpha)
            b.dw_start.conv.weight.data /= d.view(-1, 1, 1, 1)
            bias_of(b.dw_start.conv).data /= d
            b.pw_exp.conv.weight.data *= d.view(1, -1, 1, 1)
        amax = stats[(k, "exp")]
        if (k, "mid") in stats:
            amax = torch.maximum(amax, stats[(k, "mid")])
        d = _smooth_factor(amax, b.pw_proj.conv.weight.abs().amax(dim=(0, 2, 3)), alpha)
        b.pw_exp.conv.weight.data /= d.view(-1, 1, 1, 1)
        bias_of(b.pw_exp.conv).data /= d
        if not isinstance(b.dw_mid, nn.Identity):
            bias_of(b.dw_mid.conv).data /= d
        b.pw_proj.conv.weight.data *= d.view(1, -1, 1, 1)
        rep[f"block{k}"] = round(float(d.max()), 2)
    return rep


# ---------------------------------------------------------------- fake quantisation
def fq_weight(w, bits=8):
    """Per-output-channel symmetric (TFLite int8 weights: [-127, 127])."""
    q = 2 ** (bits - 1) - 1
    s = w.abs().amax(dim=tuple(range(1, w.ndim)), keepdim=True).clamp(min=1e-12) / q
    return torch.round(w / s).clamp(-q, q) * s


def fq_act(x, lo, hi):
    """Per-tensor asymmetric int8 with the range nudged to contain 0 (TFLite)."""
    lo, hi = min(lo, 0.0), max(hi, 0.0)
    s = max(hi - lo, 1e-8) / 255.0
    zp = round(-128 - lo / s)
    q = torch.clamp(torch.round(x / s) + zp, -128, 127)
    return (q - zp) * s


class Sim:
    """Hooks on the tensors TFLite quantises; mode 'calib' records ranges, 'quant' fake-quantises them."""

    def __init__(self, model, skip=()):
        self.model, self.ranges, self.mode, self.skip, self.hooks = model, {}, "off", set(skip), []
        names = {}
        for n, m in model.named_modules():
            if isinstance(m, BatchNormAct2d):
                names[n] = m
            elif getattr(m, "has_skip", False):
                names[n] = m
        names["global_pool"] = model.global_pool
        names["classifier"] = model.get_classifier()
        for n, m in names.items():
            self.hooks.append(m.register_forward_hook(self._hook(n)))
        self.hooks.append(model.conv_stem.register_forward_pre_hook(self._pre("input")))

    def _apply(self, name, x):
        if self.mode == "calib":
            lo, hi = float(x.min()), float(x.max())
            r = self.ranges.get(name)
            self.ranges[name] = (lo, hi) if r is None else (min(r[0], lo), max(r[1], hi))
        elif (self.mode == "quant" and "" not in self.skip and name not in self.skip
              and not any(name.startswith(s + ".") for s in self.skip)):
            lo, hi = self.ranges[name]
            return fq_act(x, lo, hi)
        return x

    def _hook(self, name):
        def h(mod, inp, out):
            return self._apply(name, out)
        return h

    def _pre(self, name):
        def h(mod, inp):
            return (self._apply(name, inp[0]),)
        return h


def quantize_weights(model, skip=()):
    for n, m in model.named_modules():
        if isinstance(m, (nn.Conv2d, nn.Linear)) and not any(n.startswith(s) for s in skip):
            m.weight.data.copy_(fq_weight(m.weight.data))


# ---------------------------------------------------------------- data
def load_photos(df, size, resize, mean, std):
    tf = T.Compose([T.Resize(resize, interpolation=T.InterpolationMode.BILINEAR, antialias=True), T.CenterCrop(size),
                    T.ToImage()])
    xs = [tf(Image.open(fast_path(p)).convert("RGB")) for p in df.path]
    x = torch.stack(xs).float() / 255.0
    m = torch.tensor(mean).view(1, 3, 1, 1)
    s = torch.tensor(std).view(1, 3, 1, 1)
    return (x - m) / s


@torch.no_grad()
def accuracy(model, x, y, device, bs=100):
    out = torch.cat([model(x[i:i + bs].to(device)).float().cpu() for i in range(0, len(x), bs)])
    top5 = out.topk(5, 1).indices
    return float((top5[:, 0] == y).float().mean()), float((top5 == y[:, None]).any(1).float().mean()), out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--ckpt", default="D:/FoodML/runs/cls2/final.pt")
    ap.add_argument("--data", default="E:/FoodML_ssd/dataset_v2")
    ap.add_argument("--calib", type=int, default=500)
    ap.add_argument("--eval", type=int, default=2000)
    ap.add_argument("--smooth", type=float, default=0.0, help="SmoothQuant alpha for the residual streams (0 = off)")
    ap.add_argument("--inner", action="store_true", help="also inside every block (smooth_blocks)")
    ap.add_argument("--stream-alpha", default="", help="per-stage alpha of the residual streams, e.g. 3:1.0")
    ap.add_argument("--cache", default="D:/FoodML/runs/v3/qsim_cache.pt")
    ap.add_argument("--save", default="", help="write the folded (+ smoothed) checkpoint here")
    ap.add_argument("--only-save", action="store_true")
    ap.add_argument("--quick", action="store_true", help="only the full int8 result")
    a = ap.parse_args()
    device = torch.device("cuda")
    ck = torch.load(a.ckpt, map_location="cpu", weights_only=False)
    size, resize = ck.get("size", 320), ck.get("resize", 366)
    model = build_model(ck["model"], ck["n_classes"], ck.get("head_hidden", 0), pretrained=False)
    model.load_state_dict(ck["state_dict"])
    model.eval().to(device)
    if os.path.exists(a.cache):
        xc, xe, ye = torch.load(a.cache)
    else:
        val = pd.read_csv(os.path.join(a.data, "val.csv")).sample(frac=1.0, random_state=7)
        val = val[[os.path.exists(fast_path(p)) for p in val.path]]
        xc = load_photos(val.iloc[:a.calib], size, resize, ck["mean"], ck["std"])
        ev = val.iloc[a.calib:a.calib + a.eval]
        xe = load_photos(ev, size, resize, ck["mean"], ck["std"])
        ye = torch.as_tensor(ev.label.to_numpy())
        torch.save((xc, xe, ye), a.cache)
    calib_batches = [xc[i:i + 50] for i in range(0, len(xc), 50)]

    t1, t5, ref = accuracy(model, xe, ye, device)
    print(f"float: top1 {t1:.4f} top5 {t5:.4f}", flush=True)
    fm = fold_all(copy.deepcopy(model))
    if a.smooth:
        sa = {int(k): float(v) for k, v in (x.split(":") for x in a.stream_alpha.split(","))} if a.stream_alpha else None
        rep = smooth_streams(fm, calib_batches, device, alpha=a.smooth, inner=a.inner, stream_alpha=sa)
        for si, v in rep.items():
            if isinstance(v, tuple):
                print(f"stage {si}: stream max |x| {v[0]:.1f} -> {v[1]:.1f} (max d {v[2]:.1f})", flush=True)
        print("inner blocks max d:", {k: v for k, v in rep.items() if not isinstance(v, tuple)}, flush=True)
    t1, t5, out = accuracy(fm, xe, ye, device)
    print(f"folded{' + smooth' if a.smooth else ''} float: top1 {t1:.4f} top5 {t5:.4f} "
          f"max|logit diff| {float((out - ref).abs().max()):.4f}", flush=True)
    if a.save:  # the re-parametrised float model, same architecture (BN = identity): export.py takes it as is
        torch.save({k: v for k, v in ck.items() if k != "state_dict"} | {"state_dict": fm.state_dict(),
                   "reparam": {"bn_folded": True, "smooth_alpha": a.smooth}}, a.save)
        print("saved", a.save, flush=True)
        if a.only_save:
            return

    def run(skip=(), wq=True, label=""):
        m = copy.deepcopy(fm)
        if wq:
            quantize_weights(m)
        sim = Sim(m, skip)
        sim.mode = "calib"
        with torch.no_grad():
            for x in calib_batches:
                m(x.to(device))
        sim.mode = "quant"
        t1, t5, out = accuracy(m, xe, ye, device)
        agree = float((out.argmax(1) == ref.argmax(1)).float().mean())
        print(f"{label:40s} top1 {t1:.4f} top5 {t5:.4f} same top1 as float {agree:.4f}", flush=True)
        return sim

    sim = run(label="int8 (weights + activations)")
    if a.quick:
        return
    run(wq=True, skip=("",), label="int8 weights only")
    run(wq=False, label="int8 activations only")
    groups = ["input", "bn1", "blocks.0", "blocks.1", "blocks.2", "blocks.3", "blocks.4", "global_pool", "norm_head",
              "classifier"]
    # sensitivity: only one group of activations in int8 (weights int8 everywhere)
    names = list(sim.ranges)
    for g in groups:
        others = tuple(n for n in names if not (n == g or n.startswith(g + ".") or (g == "bn1" and n == "bn1")))
        run(skip=others, label=f"  only {g} activations int8")
    for g in ["blocks.2.", "blocks.3."]:  # per block of the two big stages
        for b in sorted({n.split(".")[2] for n in names if n.startswith(g)}, key=int):
            pre = f"{g}{b}"
            others = tuple(n for n in names if not (n == pre or n.startswith(pre + ".")))
            run(skip=others, label=f"  only {pre} activations int8")
    # the ranges of the residual streams
    for n, (lo, hi) in sim.ranges.items():
        if n.count(".") == 2 and n.startswith("blocks."):
            print(f"  range {n:20s} [{lo:9.2f}, {hi:9.2f}]")


if __name__ == "__main__":
    main()
