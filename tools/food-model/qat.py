# SPDX-License-Identifier: AGPL-3.0-or-later
"""Quantisation-aware fine-tuning so the classifier keeps its accuracy as a full-int8 TFLite model.

Starts from a trained checkpoint, folds BatchNorm into the convs and applies the exact residual-stream
re-parametrisation of quant_sim.py (--smooth), then fine-tunes with TFLite-like fake quantisation:
per-output-channel symmetric int8 weights and per-tensor asymmetric int8 activations at the tensors TFLite
quantises (quant_sim.Sim), straight-through gradients. The ranges are the min/max of the float model on the
calibration photos (what the TFLite converter computes), recomputed every --recalib steps. The loss is the
distillation loss towards the float model's own predictions (+ a little of the label loss), so the int8 model
learns to give the float model's answers. The result is a float checkpoint (BN folded, same architecture) that
export.py turns into an int8 TFLite whose accuracy is checked by parity.py.

usage: python qat.py --ckpt D:/FoodML/runs/cls3/final.pt --data E:/FoodML_ssd/dataset_v3 --out D:/FoodML/runs/cls3/qat.pt
"""
import argparse
import copy
import math
import os
import sys
import time

import numpy as np
import pandas as pd
import timm
import torch
import torch.nn as nn
import torch.nn.functional as F
import torch.nn.utils.parametrize as P
from torch.utils.data import DataLoader

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import quant_sim as Q  # noqa: E402
import train as TR  # noqa: E402


class FakeQuantWeight(nn.Module):
    enabled = True  # class-wide switch: off while calibrating (the converter calibrates the float model)

    def forward(self, w):
        if not FakeQuantWeight.enabled:
            return w
        return w + (Q.fq_weight(w) - w).detach()


class QATSim(Q.Sim):
    """quant_sim.Sim with straight-through fake quantisation (training). Values outside the int8 range are clipped
    with a zero gradient (as torch.fake_quantize does): the network gains nothing from pushing activations past the
    range, so the min/max that the converter will measure stays where training put it."""

    penalty = None  # sum over the quantised tensors of how far the float values left their range (training)

    def _apply(self, name, x):
        if self.mode == "quant":
            lo, hi = self.ranges[name]
            lo, hi = min(lo, 0.0), max(hi, 0.0)
            if self.model.training:
                # keep the float activations inside the range they are quantised with: the converter measures
                # the float model's min/max, so values drifting outside would widen the steps after export.
                # Cheap: only each channel's largest / smallest value of the batch is pulled back.
                dims = (0, 2, 3) if x.ndim == 4 else (0,)
                p = (F.relu(x.amax(dim=dims).float() - hi) + F.relu(lo - x.amin(dim=dims).float())).mean() / (hi - lo + 1e-8)
                self.penalty = p if self.penalty is None else self.penalty + p
            xc = torch.clamp(x, lo, hi)
            return xc + (Q.fq_act(xc, lo, hi) - xc).detach()
        return super()._apply(name, x)


def set_bn_eval(model):
    for m in model.modules():
        if isinstance(m, nn.modules.batchnorm._BatchNorm):
            m.eval()


@torch.no_grad()
def calibrate(model, sim, calib, device):
    """Ranges = min/max of the float model on the calibration photos (as the TFLite converter does)."""
    sim.ranges, sim.mode = {}, "calib"
    was = model.training
    model.eval()
    FakeQuantWeight.enabled = False
    for x in calib:
        model(x.to(device))
    FakeQuantWeight.enabled = True
    sim.mode = "quant"
    model.train(was)
    set_bn_eval(model)


@torch.no_grad()
def evaluate(model, xe, ye, device):
    model.eval()
    t1, t5, out = Q.accuracy(model, xe, ye, device)
    return t1, t5, out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--ckpt", required=True)
    ap.add_argument("--data", required=True, help="dataset folder: train.csv (+ train.bin pack) and val.csv")
    ap.add_argument("--out", required=True)
    ap.add_argument("--smooth", type=float, default=0.75)
    ap.add_argument("--steps", type=int, default=3000)
    ap.add_argument("--batch", type=int, default=48)
    ap.add_argument("--lr", type=float, default=2e-5)
    ap.add_argument("--size", type=int, default=0, help="training crop (default: the checkpoint's eval size)")
    ap.add_argument("--label-weight", type=float, default=0.1, help="weight of the label loss (rest: distillation)")
    ap.add_argument("--recalib", type=int, default=500)
    ap.add_argument("--workers", type=int, default=3)
    ap.add_argument("--cache", default="D:/FoodML/runs/v3/qsim_cache.pt", help="quant_sim photo cache (calib/eval)")
    ap.add_argument("--not-food-shift", type=float, default=0.0, help="added to the `_not_food` bias of the result")
    ap.add_argument("--range-weight", type=float, default=1.0,
                    help="weight of the penalty on float activations outside their int8 range")
    a = ap.parse_args()
    torch.manual_seed(0)
    np.random.seed(0)
    device = torch.device("cuda")
    ck = torch.load(a.ckpt, map_location="cpu", weights_only=False)
    TR.configure_from_checkpoint(ck)
    size = a.size or TR.SIZE
    net = TR.build_model(ck["model"], ck["n_classes"], ck.get("head_hidden", 0), pretrained=False)
    net.load_state_dict(ck["state_dict"])
    net.eval().to(device)
    teacher = copy.deepcopy(net).eval()
    for p in teacher.parameters():
        p.requires_grad_(False)

    xc, xe, ye = torch.load(a.cache)
    calib = [xc[i:i + 50] for i in range(0, len(xc), 50)]
    t1, t5, ref = evaluate(net, xe, ye, device)
    print(f"float: top1 {t1:.4f} top5 {t5:.4f}", flush=True)

    model = Q.fold_all(copy.deepcopy(net))
    if a.smooth:
        print("smooth", Q.smooth_streams(model, calib, device, alpha=a.smooth, inner=True), flush=True)
    for n, m in model.named_modules():
        if isinstance(m, (nn.Conv2d, nn.Linear)):
            P.register_parametrization(m, "weight", FakeQuantWeight())
    sim = QATSim(model)
    calibrate(model, sim, calib, device)
    t1, t5, out = evaluate(model, xe, ye, device)
    print(f"int8 sim before QAT: top1 {t1:.4f} top5 {t5:.4f} same top1 {float((out.argmax(1) == ref.argmax(1)).float().mean()):.4f}",
          flush=True)

    train_csv = os.path.join(a.data, "train.csv")
    if os.path.exists(os.path.splitext(train_csv)[0] + ".bin"):
        ds = TR.PackStream(train_csv, TR.T.Compose([
            TR.T.RandomResizedCrop(size, scale=(0.35, 1.0), ratio=(3 / 4, 4 / 3), antialias=True),
            TR.T.RandomHorizontalFlip(), TR.T.ToImage()]))
        dl = DataLoader(ds, a.batch, num_workers=a.workers, drop_last=True, persistent_workers=True, prefetch_factor=3)
    else:
        from food_items import fast_path
        df = pd.read_csv(train_csv)
        df = df[[os.path.exists(fast_path(p)) and fast_path(p) != p for p in df.path]]  # only the fast copies
        tmp = os.path.join(os.path.dirname(a.out), "qat_train_subset.csv")
        df.to_csv(tmp, index=False)
        print(len(df), "training photos on the fast disk", flush=True)
        ds = TR.Photos(tmp, TR.T.Compose([
            TR.T.RandomResizedCrop(size, scale=(0.35, 1.0), ratio=(3 / 4, 4 / 3), antialias=True),
            TR.T.RandomHorizontalFlip(), TR.T.ToImage()]), packed=False)
        dl = DataLoader(ds, a.batch, shuffle=True, num_workers=a.workers, drop_last=True, persistent_workers=True)

    params = [p for n, p in model.named_parameters()]
    opt = torch.optim.AdamW(params, lr=a.lr, weight_decay=0.0)
    sched = torch.optim.lr_scheduler.LambdaLR(opt, lambda s: 0.5 * (1 + math.cos(math.pi * min(1.0, s / a.steps))))
    model.train()
    set_bn_eval(model)
    step, t0 = 0, time.time()
    best = (t1, None)
    while step < a.steps:
        for batch in dl:
            x = TR.gpu_normalize(batch[0].to(device, non_blocking=True))
            y = batch[1].to(device, non_blocking=True)
            with torch.no_grad():
                t = teacher(x)
            sim.penalty = None
            out = model(x)
            kd = F.kl_div(F.log_softmax(out, 1), F.log_softmax(t, 1), log_target=True, reduction="batchmean")
            loss = (1 - a.label_weight) * kd + a.label_weight * F.cross_entropy(out, y, label_smoothing=0.1)
            if sim.penalty is not None and a.range_weight:
                loss = loss + a.range_weight * sim.penalty
            opt.zero_grad(set_to_none=True)
            loss.backward()
            torch.nn.utils.clip_grad_norm_(params, 2.0)
            opt.step()
            sched.step()
            step += 1
            if step % 100 == 0:
                print(f"step {step}/{a.steps} loss {loss.item():.4f} kd {kd.item():.4f} "
                      f"range {float(sim.penalty or 0):.2e} "
                      f"{step * a.batch / (time.time() - t0):.0f} img/s", flush=True)
            if step % a.recalib == 0 or step >= a.steps:
                k1, k5, _ = evaluate(model, xe, ye, device)
                print(f"step {step}: int8 sim with the training ranges top1 {k1:.4f} top5 {k5:.4f}", flush=True)
                calibrate(model, sim, calib, device)
                t1, t5, out = evaluate(model, xe, ye, device)
                agree = float((out.argmax(1) == ref.argmax(1)).float().mean())
                print(f"step {step}: int8 sim top1 {t1:.4f} top5 {t5:.4f} same top1 {agree:.4f}", flush=True)
                model.train()
                set_bn_eval(model)
                if best[1] is None or t1 >= best[0]:
                    best = (t1, {k: v.detach().clone() for k, v in model.state_dict().items()})
            if step >= a.steps:
                break
    # back to a plain float model (the QAT-adapted float weights, BN folded) for export.py
    model.load_state_dict(best[1])
    for m in list(model.modules()):
        if P.is_parametrized(m, "weight"):
            P.remove_parametrizations(m, "weight", leave_parametrized=False)
    for h in sim.hooks:
        h.remove()
    plain = TR.build_model(ck["model"], ck["n_classes"], ck.get("head_hidden", 0), pretrained=False)
    plain.load_state_dict(model.state_dict())
    if a.not_food_shift:  # the `_not_food` logit (last label) shifted: fewer foods rejected as "not food"
        with torch.no_grad():
            plain.get_classifier().bias[-1] += a.not_food_shift
    torch.save({k: v for k, v in ck.items() if k != "state_dict"} | {
        "state_dict": plain.state_dict(), "reparam": {"bn_folded": True, "smooth_alpha": a.smooth, "qat_steps": step},
        "qat_sim_top1": best[0]}, a.out)
    print("saved", a.out, "int8 sim top1", best[0], flush=True)


if __name__ == "__main__":
    main()
