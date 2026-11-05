# SPDX-License-Identifier: AGPL-3.0-or-later
"""Export a checkpoint to TFLite (LiteRT) + food_model.json.

Two steps, each in its own virtualenv (PyTorch and TensorFlow pin different protobuf versions):

  1. python export.py onnx --work D:/FoodML --run run1 [--grams-head <run>/grams/head.pt]   (training venv)
     -> <run>/export/model.onnx (static 1x3xSxS, TF-'SAME' padding made explicit, see static_pad.py)
        <run>/export/calib.npy  (val photos for int8 calibration, NHWC, 0..1)
     With --grams-head the model gets a second output `log_grams` [1, 1] = a linear head on the classifier's
     pooled features (grams.py; grams_contract.md).
  2. python export.py tflite --work D:/FoodML --run run1 --classes classes.csv --temperature 1.0
        --min-confidence 0.05 --variant integer_quant --assets ../../app/src/main/assets/food
        [--grams <run>/grams/grams.json]   (export venv)
     -> runs onnx2tf (ONNX -> TF -> TFLite: float32, float16, dynamic-range and int8 variants), puts the logits
        output first in the flatbuffer (--outputs-order), copies the chosen variant to
        <assets>/food_model.tflite and writes <assets>/food_model.json (version 2 when it has the grams output).

onnx2tf transposes the model to NHWC, so the TFLite input is [1, S, S, 3] float32 RGB normalised with the
backbone's mean/std after scaling to 0..1 (v1: 224, 0.5/0.5; v2: 256, ImageNet mean/std); the logits output is
[1, n_classes] float32 (labels in that order).
"""
import argparse
import csv
import datetime
import json
import os
import shutil
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
VARIANTS = ("float32", "float16", "dynamic_range_quant", "integer_quant", "full_integer_quant",
            "selective_int8")


def fuse_pads(path):
    """Fold every constant zero Pad that feeds a Conv into the Conv's own (asymmetric) `pads`.

    onnx2tf puts the H/W padding of a standalone NCHW Pad on the wrong NHWC axes (N and H), which breaks the
    model; a Conv with asymmetric pads converts correctly."""
    import numpy as np
    import onnx
    import onnxruntime as ort
    from onnx import numpy_helper
    m = onnx.load(path)
    g = m.graph
    consts = {i.name: numpy_helper.to_array(i) for i in g.initializer}
    for n in g.node:
        if n.op_type == "Constant":
            consts[n.output[0]] = numpy_helper.to_array(n.attribute[0].t)
    # torch exports the pads of F.pad as a small chain of shape ops: evaluate them once with ONNX Runtime
    wanted = [n.input[1] for n in g.node if n.op_type == "Pad" and n.input[1] not in consts]
    if wanted:
        probe = onnx.load(path)
        for name in wanted:
            probe.graph.output.append(onnx.helper.make_tensor_value_info(name, onnx.TensorProto.INT64, None))
        sess = ort.InferenceSession(probe.SerializeToString())
        shape = [d.dim_value for d in g.input[0].type.tensor_type.shape.dim]
        vals = sess.run(wanted, {g.input[0].name: np.zeros(shape, np.float32)})
        consts.update(dict(zip(wanted, vals)))
    users = {}
    for n in g.node:
        for i in n.input:
            users.setdefault(i, []).append(n)
    remove, fused = [], 0
    for n in g.node:
        if n.op_type != "Pad":
            continue
        pads = consts.get(n.input[1])
        value = consts.get(n.input[2]) if len(n.input) > 2 and n.input[2] else np.zeros(())
        mode = next((a.s.decode() for a in n.attribute if a.name == "mode"), "constant")
        nxt = users.get(n.output[0], [])
        if pads is None or mode != "constant" or float(np.asarray(value).reshape(-1)[0] if np.asarray(value).size else 0) != 0 \
                or len(nxt) != 1 or nxt[0].op_type != "Conv" or pads[0] or pads[1] or pads[4] or pads[5]:
            continue
        conv = nxt[0]
        old = next((list(a.ints) for a in conv.attribute if a.name == "pads"), [0, 0, 0, 0])
        new = [old[0] + int(pads[2]), old[1] + int(pads[3]), old[2] + int(pads[6]), old[3] + int(pads[7])]
        for a in list(conv.attribute):
            if a.name in ("pads", "auto_pad"):
                conv.attribute.remove(a)
        conv.attribute.append(onnx.helper.make_attribute("pads", new))
        conv.input[0] = n.input[0]
        remove.append(n)
        fused += 1
    for n in remove:
        g.node.remove(n)
    while True:  # drop the now unused pad-computing nodes
        used = {i for n in g.node for i in n.input} | {o.name for o in g.output}
        dead = [n for n in g.node if not any(o in used for o in n.output)]
        if not dead:
            break
        for n in dead:
            g.node.remove(n)
    onnx.checker.check_model(m)
    onnx.save(m, path)
    return fused


def step_onnx(a):
    import numpy as np
    import pandas as pd
    import timm
    import torch
    from PIL import Image
    from torchvision.transforms import v2 as T
    sys.path.insert(0, HERE)
    import train as TR
    from static_pad import make_static
    from food_items import fast_path

    run = os.path.join(a.runs or os.path.join(a.work, "runs"), a.run)
    out = os.path.join(run, "export")
    os.makedirs(out, exist_ok=True)
    ck = torch.load(os.path.join(run, a.ckpt), map_location="cpu")
    TR.configure_from_checkpoint(ck)
    SIZE, RESIZE, MEAN, STD = TR.SIZE, TR.RESIZE, TR.MEAN, TR.STD
    model = TR.build_model(ck["model"], ck["n_classes"], ck.get("head_hidden", 0), pretrained=False)
    model.load_state_dict(ck["state_dict"])
    model.eval()
    make_static(model, SIZE)

    class WithGrams(torch.nn.Module):
        """(logits, log_grams[, embedding]): the classifier, a linear grams head on the same pooled features and
        (v3, --embedding) the image embedding: the spatial maximum of the last conv's ReLU output (960 values;
        embed_eval.py chose it: the best retrieval of Open Food Facts photos, never all-zero, exact in int8). The
        app L2-normalises it (embedding_contract.md), so int8 never rounds normalised values."""

        def __init__(self, net, head, embedding=False, features=False):
            super().__init__()
            self.net = net
            self.embedding = embedding
            self.features = features
            self.grams = torch.nn.Linear(head["weight"].shape[1], 1)
            self.grams.weight.data.copy_(head["weight"])
            self.grams.bias.data.copy_(head["bias"])

        def forward(self, x):
            m = self.net.forward_features(x)
            f = self.net.forward_head(m, pre_logits=True)
            if self.features:  # debug export for grams.py tflite --with-features: also the features the head reads
                return self.net.get_classifier()(f), self.grams(f), m.amax(dim=(2, 3)), f
            if self.embedding:
                return self.net.get_classifier()(f), self.grams(f), m.amax(dim=(2, 3))
            return self.net.get_classifier()(f), self.grams(f)

    grams = bool(a.grams_head)
    if grams:
        head = torch.load(a.grams_head, map_location="cpu")
        model = WithGrams(model, head, embedding=a.embedding or a.features_output, features=a.features_output).eval()
    x = torch.zeros(1, 3, SIZE, SIZE)
    onnx_path = os.path.join(out, "model.onnx")
    names = ["logits", "log_grams", "embedding", "features"][:(4 if grams and a.features_output else
                                                                3 if grams and a.embedding else 2 if grams else 1)]
    torch.onnx.export(model, x, onnx_path, input_names=["input"], output_names=names, opset_version=17, dynamo=False)
    print("Pad ops folded into Conv:", fuse_pads(onnx_path))
    import onnxruntime as ort
    xr = torch.randn(2, 3, SIZE, SIZE)
    with torch.no_grad():
        ref = model(xr)
        ref = [r.numpy() for r in ref] if grams else [ref.numpy()]
    sess = ort.InferenceSession(onnx_path)
    for k in range(len(ref)):
        got = np.concatenate([sess.run(None, {"input": xr[i:i + 1].numpy()})[k] for i in range(2)])
        print("ONNX vs PyTorch max |diff| output", k, float(np.abs(got - ref[k]).max()))
        assert np.abs(got - ref[k]).max() < 1e-3
    tf = T.Compose([T.Resize(RESIZE, interpolation=T.InterpolationMode.BILINEAR, antialias=True),
                    T.CenterCrop(SIZE)])
    val = pd.read_csv(os.path.join(a.work, a.dataset, "val.csv")).sample(frac=1.0, random_state=3)
    val = val[[os.path.exists(fast_path(p)) for p in val.path[:a.calib * 2]] + [False] * (len(val) - min(len(val), a.calib * 2))]
    val = val.head(a.calib)
    arr = np.stack([np.asarray(tf(Image.open(fast_path(p)).convert("RGB")), dtype=np.float32) / 255.0
                    for p in val.path])
    np.save(os.path.join(out, "calib.npy"), arr)
    # onnx2tf checks its conversion on a sample batch it would otherwise download: give it local photos
    small = np.stack([np.asarray(Image.open(fast_path(p)).convert("RGB").resize((128, 128), Image.BILINEAR),
                                 dtype=np.float32) / 255.0 for p in val.path[:20]])
    np.save(os.path.join(out, "calibration_image_sample_data_20x128x128x3_float32.npy"), small)
    meta = {"model": ck["model"], "n_classes": ck["n_classes"], "epoch": ck.get("epoch"),
            "checkpoint": a.ckpt, "val_top1": ck.get("val_top1"), "size": SIZE, "resize": RESIZE,
            "mean": list(MEAN), "std": list(STD), "grams_output": grams,
            "embedding_output": bool(grams and a.embedding)}
    with open(os.path.join(out, "export_meta.json"), "w") as f:
        json.dump(meta, f, indent=1)
    print("onnx + calibration written to", out)


def reorder_outputs(path, n_labels, logits_first=True):
    """Puts the outputs in the order logits (n_labels values), log_grams (1 value), embedding (the rest) — or
    with logits last (the v2 dummy's test order) — in the flatbuffer's output list; returns the final
    {"logits": i, "log_grams": j, "embedding": k} indices as the LiteRT interpreter reports them."""
    import numpy as np
    from ai_edge_litert.interpreter import Interpreter
    from tensorflow.lite.tools import flatbuffer_utils as fu

    def kind(n):
        return "logits" if n == n_labels else "log_grams" if n == 1 else "embedding"

    def order():
        outs = Interpreter(model_path=path).get_output_details()
        return {kind(int(np.prod(o["shape"]))): k for k, o in enumerate(outs)}
    idx = order()
    want = [k for k in ("logits", "log_grams", "embedding") if k in idx]
    if not logits_first:
        want = want[1:] + want[:1]
    if [idx[k] for k in want] != list(range(len(want))):
        m = fu.read_model(path)
        outs = list(m.subgraphs[0].outputs)
        m.subgraphs[0].outputs = [outs[idx[k]] for k in want]
        fu.write_model(m, path)
        idx = order()
    return idx


def step_tflite(a):
    run = os.path.join(a.runs or os.path.join(a.work, "runs"), a.run)
    out = os.path.join(run, "export")
    tfl = os.path.join(out, "tflite")
    with open(os.path.join(out, "export_meta.json")) as f:
        meta = json.load(f)
    mean, std = meta.get("mean", [0.5] * 3), meta.get("std", [0.5] * 3)
    size, resize = meta.get("size", 224), meta.get("resize", 256)
    if not a.skip_convert:
        shutil.rmtree(tfl, ignore_errors=True)
        exe = os.path.join(os.path.dirname(sys.executable), "onnx2tf")
        cmd = [exe, "-i", "model.onnx", "-o", "tflite", "-n"]
        if not a.skip_int8:
            cmd[5:5] = ["-oiqt", "-qt", "per-channel", "-cind", "input", "calib.npy", json.dumps([[[mean]]]),
                        json.dumps([[[std]]])]
        with open(os.path.join(out, "onnx2tf.log"), "w") as log:
            subprocess.run(cmd, cwd=out, check=True, stdout=log, stderr=subprocess.STDOUT)
    src = os.path.join(tfl, f"model_{a.variant}.tflite")
    labels = [r["id"] for r in csv.DictReader(open(a.classes, encoding="utf-8"))]
    assert len(labels) == meta["n_classes"], (len(labels), meta["n_classes"])
    os.makedirs(a.assets, exist_ok=True)
    dst = os.path.join(a.assets, "food_model.tflite")
    shutil.copyfile(src, dst)
    idx = reorder_outputs(dst, len(labels), logits_first=a.outputs_order == "logits_first")
    if a.strip:  # tensor names and other strings the app never reads (~20 KB); outputs keep their order
        from tensorflow.lite.tools import flatbuffer_utils as fu
        m = fu.read_model(dst)
        fu.strip_strings(m)
        fu.write_model(m, dst)
        assert reorder_outputs(dst, len(labels), logits_first=a.outputs_order == "logits_first") == idx
    has_grams = "log_grams" in idx
    spec = {
        "version": 2 if has_grams or size != 224 else 1,
        "labels": labels,
        "input": {"width": size, "height": size, "layout": "NHWC", "dtype": "float32", "channels": "RGB",
                  "mean": [round(float(v), 6) for v in mean], "std": [round(float(v), 6) for v in std],
                  "range": "0..1 before mean/std", "resize": "short_side_then_center_crop",
                  "crop_fraction": round(size / resize, 6), "resize_short_side": resize, "interpolation": "bilinear"},
        "output": {"dtype": "float32", "scale": 1.0, "zero_point": 0, "kind": "logits", "index": idx["logits"]},
        "temperature": a.temperature,
        "min_confidence": a.min_confidence,
        "not_food_label": "_not_food",
        "model": {"name": a.name, "backbone": meta["model"], "quantization": a.variant,
                  "trained": datetime.date.today().isoformat(), "notes": a.notes},
    }
    if has_grams:
        g = json.load(open(a.grams)) if a.grams else {}
        spec["grams"] = {
            "enabled": bool(g.get("enabled", False)),
            "output": {"index": idx["log_grams"], "shape": [1, 1], "dtype": "float32", "kind": "log_grams"},
            "b0": g.get("b0", 0.0), "b_model": g.get("b_model", 1.0), "b_portion": g.get("b_portion", 0.0),
            "b_share": g.get("b_share", 0.0), "clamp_portion": g.get("clamp_portion", [0.25, 4.0]),
            "clamp_grams": g.get("clamp_grams", [5, 1500]), "round_to": g.get("round_to", 5),
        }
        if "notes" in g:
            spec["grams"]["notes"] = g["notes"]
    if "embedding" in idx:  # v3: embedding_contract.md
        e = json.load(open(a.embedding_spec)) if a.embedding_spec else {}
        spec["version"] = 3
        spec["embedding"] = {
            "output": {"index": idx["embedding"], "shape": [1, int(e.get("dim", 1280))], "dtype": "float32",
                       "kind": "embedding"},
            # its own run of the model: the whole upright picture stretched to the input size (as the detector's
            # input, no crop); then the same 0..1 and mean/std as "input"
            "input": {"resize": "stretch"},
            "l2_normalize": True, "metric": "cosine", "space": e.get("space", a.name),
            "match": e.get("match", {"threshold": 0.85, "margin": 0.05, "suggest_threshold": 0.8, "suggest_max": 3}),
        }
        if "notes" in e:
            spec["embedding"]["notes"] = e["notes"]
    with open(os.path.join(a.assets, "food_model.json"), "w", encoding="utf-8", newline="\n") as f:
        json.dump(spec, f, ensure_ascii=False, indent=1)
        f.write("\n")
    print("wrote", a.assets, os.path.getsize(src), "bytes")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("step", choices=("onnx", "tflite"))
    ap.add_argument("--work", default="D:/FoodML")
    ap.add_argument("--dataset", default="dataset", help="under --work")
    ap.add_argument("--run", default="run1")
    ap.add_argument("--ckpt", default="best.pt")
    ap.add_argument("--calib", type=int, default=500)
    ap.add_argument("--classes", default=os.path.join(HERE, "classes.csv"))
    ap.add_argument("--variant", default="integer_quant", choices=VARIANTS)
    ap.add_argument("--temperature", type=float, default=1.0)
    ap.add_argument("--min-confidence", type=float, default=0.05)
    ap.add_argument("--assets", default=os.path.join(HERE, "..", "..", "app", "src", "main", "assets", "food"))
    ap.add_argument("--name", default="fortia-food")
    ap.add_argument("--notes", default="")
    ap.add_argument("--skip-convert", action="store_true", help="reuse the tflite folder")
    ap.add_argument("--skip-int8", action="store_true",
                    help="onnx2tf without its int8 variants (quant_debug.py makes the selective int8 one)")
    ap.add_argument("--runs", default="", help="runs folder (default <work>/runs)")
    ap.add_argument("--grams-head", default="", help="onnx step: grams.py head.pt -> second output log_grams")
    ap.add_argument("--grams", default="", help="tflite step: grams.py grams.json (the json's grams block)")
    ap.add_argument("--outputs-order", default="logits_first", choices=("logits_first", "logits_last"))
    ap.add_argument("--embedding", action="store_true", help="onnx step: a third output, the image embedding")
    ap.add_argument("--embedding-spec", default="", help="tflite step: json with dim, space, match (thresholds)")
    ap.add_argument("--strip", action="store_true", help="tflite step: strip tensor names from the copied asset")
    ap.add_argument("--features-output", action="store_true",
                    help="onnx step, debug only: a 4th output, the features the grams head reads (grams.py tflite "
                         "--with-features on the converted file; never shipped)")
    a = ap.parse_args()
    step_onnx(a) if a.step == "onnx" else step_tflite(a)


if __name__ == "__main__":
    main()
