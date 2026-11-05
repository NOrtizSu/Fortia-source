# SPDX-License-Identifier: AGPL-3.0-or-later
"""Export the class-agnostic food-item detector (Ultralytics YOLO, AGPL-3.0) to TFLite with box decoding and
non-maximum suppression inside the graph, + food_detector.json (contract:
.omc/handoffs/2026-11-02/detector_contract.md).

Two steps, each in its own virtualenv:

  1. python export_detector.py onnx --weights best.pt --out D:/FoodML/runs/det1/export --size 320
        [--score-classes 0]  (training venv: torch + ultralytics)
     -> detector.onnx: input `images` [1, 3, S, S] float32 RGB 0..1 (the photo stretched to S x S);
        outputs `boxes` [1, A, 4] = ymin, xmin, ymax, xmax normalised 0..1 and `scores` [1, A] (sigmoid; with
        several score classes, their maximum). Boxes and scores are separate tensors so int8 gives each its own
        scale (one concatenated tensor would quantise the scores to nothing).
     -> calib.npy: stretched photos for the int8 calibration (NHWC 0..1) when --calib-dir is given.
  2. python export_detector.py tflite --out D:/FoodML/runs/det1/export --variant int8|fp16|fp32
        --assets <dir>   (export venv: TensorFlow + onnx2tf)
     -> onnx2tf -> SavedModel, wrapped in a tf.function that keeps the `max_detections` best boxes after NMS
        (TFLite NON_MAX_SUPPRESSION_V4/V5 builtin) and returns, in this order:
          0 boxes  [1, K, 4] float32  ymin, xmin, ymax, xmax normalised 0..1 to the photo (rows >= count are 0)
          1 scores [1, K]    float32  descending (rows >= count are 0)
          2 count  [1]       float32  number of valid rows
        then food_detector.tflite + food_detector.json in --assets. The converter does not keep that order in
        the flatbuffer: the json records the real output index of each (also recognisable by rank: boxes 3-D,
        scores 2-D, count 1-D).

The dummy model (COCO YOLO11n, score = max of the 10 COCO food classes) is made the same way with
--weights yolo11n.pt --score-classes 46,47,48,49,50,51,52,53,54,55.
"""
import argparse
import datetime
import glob
import json
import os
import shutil
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))


def step_onnx(a):
    import numpy as np
    import torch
    from ultralytics import YOLO
    from ultralytics.nn.modules import Detect

    os.makedirs(a.out, exist_ok=True)
    y = YOLO(a.weights)
    net = y.model.float().fuse().eval()
    S = a.size
    for m in net.modules():
        if isinstance(m, Detect):
            m.export = True
            m.format = "onnx"
            m.dynamic = False
            # v3 (int8): never concatenate the decoded boxes (pixels, 0..S) with the scores (0..1) as Ultralytics
            # does: one int8 scale for both rounds every score to 0 or ~S/255. Boxes are normalised to 0..1
            # and the two stay separate tensors all the way to the outputs.
            m._inference = (lambda x, m=m: (m._get_decode_boxes(x) / S, x["scores"].sigmoid()))
    chans = [int(c) for c in a.score_classes.split(",")]

    class Wrap(torch.nn.Module):
        def __init__(self, net):
            super().__init__()
            self.net = net

        def forward(self, images):
            dbox, cls = self.net(images)  # [1, 4, A] cx, cy, w, h normalised; [1, nc, A] sigmoid scores
            cx, cy, w, h = dbox[:, 0], dbox[:, 1], dbox[:, 2], dbox[:, 3]
            boxes = torch.stack([cy - h / 2, cx - w / 2, cy + h / 2, cx + w / 2], -1)
            boxes = boxes.clamp(0.0, 1.0)
            sc = cls[:, chans]
            scores = sc.max(1).values if len(chans) > 1 else sc[:, 0]
            return boxes, scores

    wrap = Wrap(net).eval()
    x = torch.zeros(1, 3, S, S)
    with torch.no_grad():
        b, s = wrap(x)
    print("anchors", b.shape, s.shape)
    path = os.path.join(a.out, "detector.onnx")
    torch.onnx.export(wrap, x, path, input_names=["images"], output_names=["boxes", "scores"], opset_version=17,
                      dynamo=False)
    try:
        import onnxslim
        import onnx
        onnx.save(onnxslim.slim(onnx.load(path)), path)
    except Exception as e:  # optional
        print("onnxslim skipped:", e)
    import onnxruntime as ort
    xr = torch.rand(1, 3, S, S)
    with torch.no_grad():
        rb, rs = wrap(xr)
    gb, gs = ort.InferenceSession(path).run(None, {"images": xr.numpy()})
    print("ONNX vs PyTorch max |diff| boxes", float(np.abs(gb - rb.numpy()).max()), "scores",
          float(np.abs(gs - rs.numpy()).max()))
    if a.calib_dir:
        from PIL import Image
        files = sorted(glob.glob(os.path.join(a.calib_dir, "*.jpg")))
        rng = np.random.default_rng(0)
        files = [files[i] for i in rng.permutation(len(files))[:a.calib]]
        arr = np.stack([np.asarray(Image.open(f).convert("RGB").resize((S, S), Image.BILINEAR), np.float32) / 255.0
                        for f in files])
        np.save(os.path.join(a.out, "calib.npy"), arr)
        small = np.stack([np.asarray(Image.open(f).convert("RGB").resize((128, 128), Image.BILINEAR), np.float32)
                          / 255.0 for f in files[:20]])
        np.save(os.path.join(a.out, "calibration_image_sample_data_20x128x128x3_float32.npy"), small)
    with open(os.path.join(a.out, "export_meta.json"), "w") as f:
        json.dump({"weights": os.path.abspath(a.weights), "size": S, "score_classes": chans,
                   "anchors": int(b.shape[1])}, f, indent=1)
    print("written", path)


def step_tflite(a):
    import numpy as np
    import tensorflow as tf

    with open(os.path.join(a.out, "export_meta.json")) as f:
        meta = json.load(f)
    S = meta["size"]
    sm_dir = os.path.join(a.out, "saved_model")
    if not a.skip_convert:
        shutil.rmtree(sm_dir, ignore_errors=True)
        exe = os.path.join(os.path.dirname(sys.executable), "onnx2tf")
        cmd = [exe, "-i", "detector.onnx", "-o", "saved_model", "-osd", "-n"]
        with open(os.path.join(a.out, "onnx2tf.log"), "w") as log:
            subprocess.run(cmd, cwd=a.out, check=True, stdout=log, stderr=subprocess.STDOUT)
    loaded = tf.saved_model.load(sm_dir)
    f = loaded.signatures["serving_default"]
    in_name = list(f.structured_input_signature[1].keys())[0]
    K = a.max_detections

    class Det(tf.Module):
        def __init__(self):
            super().__init__()
            self.loaded = loaded

        @tf.function(input_signature=[tf.TensorSpec([1, S, S, 3], tf.float32, name="image")])
        def serve(self, image):
            out = f(**{in_name: image})
            boxes = out["boxes"] if "boxes" in out else list(out.values())[0]
            scores = out["scores"] if "scores" in out else list(out.values())[1]
            boxes = tf.reshape(boxes, [-1, 4])
            scores = tf.reshape(scores, [-1])
            idx, n = tf.raw_ops.NonMaxSuppressionV4(
                boxes=boxes, scores=scores, max_output_size=K, iou_threshold=a.iou,
                score_threshold=a.graph_score_floor, pad_to_max_output_size=True)
            valid = tf.cast(tf.range(K) < n, tf.float32)
            b = tf.gather(boxes, idx) * valid[:, None]
            s = tf.gather(scores, idx) * valid
            return (tf.reshape(b, [1, K, 4]), tf.reshape(s, [1, K]), tf.reshape(tf.cast(n, tf.float32), [1]))

    det = Det()
    conc = det.serve.get_concrete_function()
    variants = {}
    for v in ("fp32", "fp16", "int8", "dynint8"):
        if a.variant != "all" and v != a.variant:
            continue
        conv = tf.lite.TFLiteConverter.from_concrete_functions([conc], det)
        if v == "fp16":
            conv.optimizations = [tf.lite.Optimize.DEFAULT]
            conv.target_spec.supported_types = [tf.float16]
        elif v == "dynint8":  # int8 weights, float activations (dynamic-range quantisation)
            conv.optimizations = [tf.lite.Optimize.DEFAULT]
        elif v == "int8":
            calib = np.load(os.path.join(a.out, "calib.npy"))

            def rep():
                for i in range(len(calib)):
                    yield [calib[i:i + 1]]
            conv.optimizations = [tf.lite.Optimize.DEFAULT]
            conv.representative_dataset = rep
            conv.inference_input_type = tf.float32
            conv.inference_output_type = tf.float32
        path = os.path.join(a.out, f"food_detector_{v}.tflite")
        if v == "int8" and a.keep_float:
            # TensorFlow's quantization debugger: the --keep-float layers with the largest int8 error stay float
            dbg = tf.lite.experimental.QuantizationDebugger(converter=conv, debug_dataset=rep)
            dbg.run()
            stats = os.path.join(a.out, "quant_debug.csv")
            with open(stats, "w") as fh:
                dbg.layer_statistics_dump(fh)
            import pandas as pd
            st = pd.read_csv(stats)
            st["rmse/scale"] = np.sqrt(st["mean_squared_error"]) / st["scale"]
            deny = st.sort_values("rmse/scale", ascending=False)["tensor_name"].head(a.keep_float).tolist()
            print("kept in float:", deny)
            conv2 = tf.lite.TFLiteConverter.from_concrete_functions([conc], det)
            conv2.optimizations = [tf.lite.Optimize.DEFAULT]
            conv2.representative_dataset = rep
            conv2.inference_input_type = tf.float32
            conv2.inference_output_type = tf.float32
            opts = tf.lite.experimental.QuantizationDebugOptions(denylisted_nodes=deny)
            model = tf.lite.experimental.QuantizationDebugger(
                converter=conv2, debug_dataset=rep, debug_options=opts).get_nondebug_quantized_model()
        else:
            model = conv.convert()
        with open(path, "wb") as fh:
            fh.write(model)
        variants[v] = path
        print(v, os.path.getsize(path), "bytes")
    # check the tensor order the app relies on
    from ai_edge_litert.interpreter import Interpreter
    index = {}
    for v, path in variants.items():
        it = Interpreter(model_path=path)
        it.allocate_tensors()
        outs = it.get_output_details()
        print(v, "input", it.get_input_details()[0]["shape"], [(o["name"], list(o["shape"])) for o in outs])
        by_rank = {len(o["shape"]): i for i, o in enumerate(outs)}
        assert sorted(by_rank) == [1, 2, 3] and list(outs[by_rank[3]]["shape"]) == [1, K, 4]
        index[v] = {"boxes": by_rank[3], "scores": by_rank[2], "count": by_rank[1]}
    if a.assets and a.variant != "all":
        os.makedirs(a.assets, exist_ok=True)
        shutil.copyfile(variants[a.variant], os.path.join(a.assets, "food_detector.tflite"))
        if a.strip:  # tensor names the app never reads; the outputs keep their order (checked above by rank)
            from tensorflow.lite.tools import flatbuffer_utils as fu
            m = fu.read_model(os.path.join(a.assets, "food_detector.tflite"))
            fu.strip_strings(m)
            fu.write_model(m, os.path.join(a.assets, "food_detector.tflite"))
        spec = {
            "version": 1,
            "kind": "food_item_detector",
            "input": {"width": S, "height": S, "layout": "NHWC", "dtype": "float32", "channels": "RGB",
                      "range": "0..1", "mean": [0.0, 0.0, 0.0], "std": [1.0, 1.0, 1.0],
                      "resize": "stretch", "interpolation": "bilinear"},
            "outputs": {
                "boxes": {"index": index[a.variant]["boxes"], "shape": [1, K, 4], "dtype": "float32",
                          "format": "ymin,xmin,ymax,xmax normalised 0..1 to the photo"},
                "scores": {"index": index[a.variant]["scores"], "shape": [1, K], "dtype": "float32",
                           "order": "descending"},
                "count": {"index": index[a.variant]["count"], "shape": [1], "dtype": "float32"},
            },
            "max_detections": K,
            "nms_iou": a.iou,
            "graph_score_floor": a.graph_score_floor,
            "score_threshold": a.score_threshold,
            "max_items": a.max_items,
            "min_box_side": a.min_box_side,
            "crop": {"margin": a.crop_margin, "square": True},
            # optional (absent = no suppression): a kept box that holds >= min_inside other kept boxes, each with
            # >= inside of its area within it, is dropped and the boxes inside are kept (compare_group_rules.py)
            **({"group_suppression": {"rule": a.group_rule, "inside": a.group_inside,
                                      "min_inside": a.group_min_inside}} if a.group_rule != "none" else {}),
            "model": {"name": a.name, "architecture": a.arch, "quantization": a.variant,
                      "trained": datetime.date.today().isoformat(), "notes": a.notes},
        }
        with open(os.path.join(a.assets, "food_detector.json"), "w", encoding="utf-8", newline="\n") as fh:
            json.dump(spec, fh, ensure_ascii=False, indent=1)
            fh.write("\n")
        print("wrote", a.assets)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("step", choices=("onnx", "tflite"))
    ap.add_argument("--weights", default="")
    ap.add_argument("--out", required=True)
    ap.add_argument("--size", type=int, default=320)
    ap.add_argument("--score-classes", default="0")
    ap.add_argument("--calib-dir", default="")
    ap.add_argument("--calib", type=int, default=300)
    ap.add_argument("--variant", default="int8", choices=("fp32", "fp16", "int8", "dynint8", "all"))
    ap.add_argument("--max-detections", type=int, default=10)
    ap.add_argument("--iou", type=float, default=0.5)
    ap.add_argument("--graph-score-floor", type=float, default=0.1)
    ap.add_argument("--score-threshold", type=float, default=0.35)
    ap.add_argument("--max-items", type=int, default=6)
    ap.add_argument("--min-box-side", type=float, default=0.06)
    ap.add_argument("--crop-margin", type=float, default=0.1)
    ap.add_argument("--group-rule", default="drop_container", choices=("none", "drop_container"))
    ap.add_argument("--group-inside", type=float, default=0.8)
    ap.add_argument("--group-min-inside", type=int, default=2)
    ap.add_argument("--assets", default="")
    ap.add_argument("--name", default="fortia-food-detector")
    ap.add_argument("--arch", default="YOLO11n (Ultralytics, AGPL-3.0)")
    ap.add_argument("--notes", default="")
    ap.add_argument("--skip-convert", action="store_true")
    ap.add_argument("--keep-float", type=int, default=0, help="int8: keep the N worst layers in float")
    ap.add_argument("--strip", action="store_true", help="strip tensor names from the copied asset")
    a = ap.parse_args()
    step_onnx(a) if a.step == "onnx" else step_tflite(a)


if __name__ == "__main__":
    main()
