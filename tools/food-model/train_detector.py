# SPDX-License-Identifier: AGPL-3.0-or-later
"""Train the class-agnostic food-item detector: Ultralytics YOLO (AGPL-3.0) from its COCO checkpoint, one class.

The photos of build_detector_data.py are already stretched to a square (what the app does), so the usual YOLO
resizing keeps that geometry. Vertical flips are on (plates are photographed from above), mosaic until the last
10 epochs.

usage: python train_detector.py --data E:/FoodML_ssd/det/data.yaml --name det1 [--model yolo11n.pt]
       [--epochs 40] [--imgsz 320] [--batch 64]
       python train_detector.py --eval-only --weights <best.pt> --data ... (precision/recall per item count)
       python train_detector.py --eval-only --tflite food_detector.tflite --data ...  (the exported model,
           preprocessing as in detector_contract.md)
"""
import argparse
import json
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from food_items import group_rule  # noqa: E402


def match(pred, gt, iou_thr=0.5):
    """Greedy one-to-one matching of predicted (score-sorted) and true boxes (x0, y0, x1, y1) -> true positives."""
    if len(pred) == 0 or len(gt) == 0:
        return 0
    p, g = np.asarray(pred, float), np.asarray(gt, float)
    x0 = np.maximum(p[:, None, 0], g[None, :, 0])
    y0 = np.maximum(p[:, None, 1], g[None, :, 1])
    x1 = np.minimum(p[:, None, 2], g[None, :, 2])
    y1 = np.minimum(p[:, None, 3], g[None, :, 3])
    inter = np.clip(x1 - x0, 0, None) * np.clip(y1 - y0, 0, None)
    ap = (p[:, 2] - p[:, 0]) * (p[:, 3] - p[:, 1])
    ag = (g[:, 2] - g[:, 0]) * (g[:, 3] - g[:, 1])
    iou = inter / (ap[:, None] + ag[None, :] - inter + 1e-9)
    used, tp = set(), 0
    for i in range(len(p)):
        j = int(np.argmax(np.where([k in used for k in range(len(g))], -1, iou[i])))
        if iou[i, j] >= iou_thr and j not in used:
            used.add(j)
            tp += 1
    return tp


def read_labels(path):
    out = []
    if os.path.exists(path):
        for line in open(path):
            _, cx, cy, w, h = map(float, line.split())
            out.append((cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2))
    return out


def yolo_predictor(weights, imgsz, conf):
    from ultralytics import YOLO
    model = YOLO(weights)

    def predict(paths):
        res = model.predict(paths, imgsz=imgsz, conf=conf, iou=0.5, verbose=False, max_det=10)
        return [(r.boxes.xyxyn.cpu().numpy(), r.boxes.conf.cpu().numpy()) for r in res]
    return predict


def tflite_predictor(path, threads=4):
    """The exported detector, fed as the app does: the photo stretched to S x S, RGB 0..1."""
    from ai_edge_litert.interpreter import Interpreter
    from PIL import Image
    it = Interpreter(model_path=path, num_threads=threads)
    it.allocate_tensors()
    inp = it.get_input_details()[0]
    size = int(inp["shape"][1])
    outs = {len(o["shape"]): o["index"] for o in it.get_output_details()}

    def predict(paths):
        res = []
        for p in paths:
            x = np.asarray(Image.open(p).convert("RGB").resize((size, size), Image.BILINEAR), np.float32) / 255.0
            it.set_tensor(inp["index"], x[None])
            it.invoke()
            n = int(it.get_tensor(outs[1])[0])
            b = it.get_tensor(outs[3])[0][:n]
            s = it.get_tensor(outs[2])[0][:n]
            res.append((b[:, [1, 0, 3, 2]] if n else np.zeros((0, 4)), s))
        return res
    return predict


def evaluate(predict, split_dir, thresholds, max_items=6, min_side=0.06, prefix=None, rule=None):
    """App-style evaluation: NMS 0.5, keep score >= t, drop boxes shorter than min_side, the container rule
    (food_items.group_rule with the `rule` dict, if given), at most max_items."""
    img_dir = os.path.join(split_dir, "images")
    lab_dir = os.path.join(split_dir, "labels")
    files = sorted(f for f in os.listdir(img_dir) if prefix is None or f.startswith(prefix))
    preds, gts = [], []
    for k in range(0, len(files), 64):
        batch = [os.path.join(img_dir, f) for f in files[k:k + 64]]
        for f, r in zip(files[k:k + 64], predict(batch)):
            preds.append(r)
            gts.append(read_labels(os.path.join(lab_dir, os.path.splitext(f)[0] + ".txt")))
    out = {}
    for t in thresholds:
        rows = {}
        for (b, s), g in zip(preds, gts):
            keep = [(bb, ss) for bb, ss in zip(b, s) if ss >= t and min(bb[2] - bb[0], bb[3] - bb[1]) >= min_side]
            if rule:
                keep = [keep[i] for i in group_rule([k[0] for k in keep], **rule)]
            keep = keep[:max_items]
            n = len(g)
            key = "0" if n == 0 else "1" if n == 1 else "2-3" if n <= 3 else "4+"
            tp = match([k[0] for k in keep], g)
            r = rows.setdefault(key, {"photos": 0, "true": 0, "pred": 0, "tp": 0, "count_ok": 0, "multi_ok": 0})
            r["photos"] += 1
            r["true"] += n
            r["pred"] += len(keep)
            r["tp"] += tp
            r["count_ok"] += int(len(keep) == min(n, max_items))
            # what the app decides: multi-item flow when >= 2 boxes
            r["multi_ok"] += int((len(keep) >= 2) == (n >= 2))
        for r in rows.values():
            r["precision"] = r["tp"] / max(1, r["pred"])
            r["recall"] = r["tp"] / max(1, r["true"])
            r["f1"] = 2 * r["tp"] / max(1, r["pred"] + r["true"])
            r["count_acc"] = r["count_ok"] / max(1, r["photos"])
            r["flow_acc"] = r["multi_ok"] / max(1, r["photos"])
        out[str(t)] = rows
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", required=True)
    ap.add_argument("--name", default="det1")
    ap.add_argument("--project", default="D:/FoodML/runs/det")
    ap.add_argument("--model", default="yolo11n.pt")
    ap.add_argument("--epochs", type=int, default=40)
    ap.add_argument("--imgsz", type=int, default=320)
    ap.add_argument("--batch", type=int, default=64)
    ap.add_argument("--workers", type=int, default=8)
    ap.add_argument("--eval-only", action="store_true")
    ap.add_argument("--weights", default="")
    ap.add_argument("--resume", action="store_true")
    ap.add_argument("--tflite", default="", help="evaluate this exported model instead of --weights")
    ap.add_argument("--out", default="", help="evaluation json (default <run>/app_eval.json)")
    a = ap.parse_args()
    os.environ.setdefault("YOLO_OFFLINE", "1")
    root = os.path.dirname(os.path.abspath(a.data))
    if not a.eval_only:
        from ultralytics import YOLO
        if a.resume:
            model = YOLO(os.path.join(a.project, a.name, "weights", "last.pt"))
            model.train(resume=True)
        else:
            model = YOLO(a.model)
            model.train(data=a.data, epochs=a.epochs, imgsz=a.imgsz, batch=a.batch, workers=a.workers,
                        project=a.project, name=a.name, exist_ok=True, single_cls=True, rect=False,
                        flipud=0.5, fliplr=0.5, degrees=0.0, mosaic=1.0, close_mosaic=10, mixup=0.0,
                        scale=0.5, translate=0.1, hsv_h=0.015, hsv_s=0.6, hsv_v=0.4, cos_lr=True,
                        patience=100, plots=True, amp=True, seed=0, deterministic=False, cache=False)
        a.weights = os.path.join(a.project, a.name, "weights", "best.pt")
    thresholds = [0.2, 0.25, 0.3, 0.35, 0.4, 0.45, 0.5]
    predict = tflite_predictor(a.tflite) if a.tflite else yolo_predictor(a.weights, a.imgsz, min(thresholds))
    res = {}
    for split in ("val", "test"):
        for prefix in ("frb_", "oi_"):
            d = {"images": os.path.join(root, "images", split), "labels": os.path.join(root, "labels", split)}
            split_dir = os.path.join(root, "_eval_" + split)
            os.makedirs(split_dir, exist_ok=True)
            for k, v in d.items():  # evaluate() wants <dir>/images and <dir>/labels
                link = os.path.join(split_dir, k)
                if not os.path.exists(link):
                    os.symlink(v, link, target_is_directory=True) if os.name != "nt" else os.system(
                        f'mklink /J "{link}" "{v}" > NUL')
            res[f"{split}/{prefix[:-1]}"] = evaluate(predict, split_dir, thresholds, prefix=prefix)
    out = a.out or os.path.join(os.path.dirname(os.path.dirname(a.weights)), "app_eval.json")
    with open(out, "w") as f:
        json.dump(res, f, indent=1)
    for k, v in res.items():
        for t, rows in v.items():
            print(k, t, {n: (round(r["precision"], 3), round(r["recall"], 3), round(r["flow_acc"], 3), r["photos"])
                          for n, r in sorted(rows.items())})


if __name__ == "__main__":
    main()
