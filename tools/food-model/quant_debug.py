# SPDX-License-Identifier: AGPL-3.0-or-later
"""Selective int8 quantization of the classifier when plain full-int8 loses accuracy.

onnx2tf's int8 MobileNetV4 lost 17 points of top-1 (a few layers with outlier activations break per-tensor int8
activations). This runs TensorFlow's quantization debugger on the SavedModel that onnx2tf wrote, ranks the
layers by quantization error (RMSE of the int8 output over the scale), keeps the --keep-float worst of them in
float32 and writes that model (int8 elsewhere, float32 input/outputs).

usage (export venv): python quant_debug.py --export D:/FoodML/runs/cls2/export --keep-float 12 \
    --out D:/FoodML/runs/cls2/export/tflite/model_selective_int8.tflite
"""
import argparse
import json
import os

import numpy as np
import tensorflow as tf


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--export", required=True, help="export.py's <run>/export folder")
    ap.add_argument("--keep-float", type=int, default=12)
    ap.add_argument("--calib", type=int, default=200)
    ap.add_argument("--out", required=True)
    ap.add_argument("--dynamic", action="store_true",
                    help="write the dynamic-range model instead (int8 weights, float activations; no debugger)")
    a = ap.parse_args()
    if a.dynamic:
        c = tf.lite.TFLiteConverter.from_saved_model(os.path.join(a.export, "tflite"))
        c.optimizations = [tf.lite.Optimize.DEFAULT]
        model = c.convert()
        with open(a.out, "wb") as f:
            f.write(model)
        print("written", a.out, len(model), "bytes")
        return
    meta = json.load(open(os.path.join(a.export, "export_meta.json")))
    mean = np.array(meta["mean"], np.float32)
    std = np.array(meta["std"], np.float32)
    calib = np.load(os.path.join(a.export, "calib.npy"))[:a.calib]
    calib = (calib - mean) / std

    def rep():
        for i in range(len(calib)):
            yield [calib[i:i + 1].astype(np.float32)]

    def converter():
        c = tf.lite.TFLiteConverter.from_saved_model(os.path.join(a.export, "tflite"))
        c.optimizations = [tf.lite.Optimize.DEFAULT]
        c.representative_dataset = rep
        c.inference_input_type = tf.float32
        c.inference_output_type = tf.float32
        return c

    debugger = tf.lite.experimental.QuantizationDebugger(converter=converter(), debug_dataset=rep)
    debugger.run()
    stats_path = os.path.join(a.export, "quant_debug.csv")
    with open(stats_path, "w") as f:
        debugger.layer_statistics_dump(f)
    import pandas as pd
    df = pd.read_csv(stats_path)
    df["rmse/scale"] = np.sqrt(df["mean_squared_error"]) / df["scale"]
    worst = df.sort_values("rmse/scale", ascending=False)
    print(worst[["op_name", "tensor_name", "max_abs_error", "scale", "rmse/scale"]].head(25).to_string())
    deny = worst["tensor_name"].head(a.keep_float).tolist()
    opts = tf.lite.experimental.QuantizationDebugOptions(denylisted_nodes=deny)
    dbg2 = tf.lite.experimental.QuantizationDebugger(converter=converter(), debug_dataset=rep, debug_options=opts)
    model = dbg2.get_nondebug_quantized_model()
    with open(a.out, "wb") as f:
        f.write(model)
    print("kept in float:", deny)
    print("written", a.out, len(model), "bytes")


if __name__ == "__main__":
    main()
