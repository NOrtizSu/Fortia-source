# SPDX-License-Identifier: AGPL-3.0-or-later
"""Replace timm's dynamic 'SAME' padding convolutions with static ZeroPad2d + Conv2d for a fixed input size.

The tf_* timm models (ports of TensorFlow checkpoints) pad like TensorFlow's 'SAME', computed from the input
shape at run time. That exports to ONNX as shape arithmetic that the ONNX -> TFLite converter can't handle,
so for export we fix the input size and turn every such conv into an explicit, constant pad + plain conv
(numerically identical).
"""
import math

import torch
from torch import nn


def _same_pad(size, k, s, d):
    total = max((math.ceil(size / s) - 1) * s + (k - 1) * d + 1 - size, 0)
    return total // 2, total - total // 2


def make_static(model, input_size=224):
    from timm.layers.conv2d_same import Conv2dSame
    sizes = {}
    hooks = []
    for name, mod in model.named_modules():
        if isinstance(mod, Conv2dSame):
            hooks.append(mod.register_forward_pre_hook(
                lambda m, args, name=name: sizes.__setitem__(name, tuple(args[0].shape[-2:]))))
    model.eval()
    with torch.no_grad():
        model(torch.zeros(1, 3, input_size, input_size))
    for h in hooks:
        h.remove()
    for name, (ih, iw) in sizes.items():
        parent = model.get_submodule(name.rsplit(".", 1)[0]) if "." in name else model
        attr = name.rsplit(".", 1)[-1]
        old = getattr(parent, attr)
        kh, kw = old.kernel_size
        sh, sw = old.stride
        dh, dw = old.dilation
        pt, pb = _same_pad(ih, kh, sh, dh)
        pl, pr = _same_pad(iw, kw, sw, dw)
        conv = nn.Conv2d(old.in_channels, old.out_channels, old.kernel_size, old.stride, 0, old.dilation,
                         old.groups, old.bias is not None)
        conv.weight = old.weight
        if old.bias is not None:
            conv.bias = old.bias
        layer = nn.Sequential(nn.ZeroPad2d((pl, pr, pt, pb)), conv) if (pl or pr or pt or pb) else conv
        setattr(parent, attr, layer)
    return model
