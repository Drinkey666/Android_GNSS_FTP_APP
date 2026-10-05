"""Compare Android JNI PPP OBS CSV with PC gnss_replay --obs-dump.

Use files from the *same* GnssMeasurements capture. No RINEX OBS is involved.
"""

import argparse
import csv
import json
import math
from pathlib import Path


KEY = ("week", "tow", "sat", "signal", "slot")
NUMERIC = ("P_m", "L_cycle", "D_Hz", "CN0_dBHz")


def read(path):
    records = {}
    with path.open(newline="", encoding="utf-8") as stream:
        for row in csv.DictReader(stream):
            key = (int(row["week"]), round(float(row["tow"]), 3),
                   row["sat"], row["signal"], int(row["slot"]))
            if key in records:
                raise ValueError(f"Duplicate epoch/satellite/signal: {key}")
            records[key] = row
    return records


def stats(values):
    if not values:
        return {"n": 0}
    return {"n": len(values), "mean": sum(values) / len(values),
            "rms": math.sqrt(sum(v * v for v in values) / len(values)),
            "max_abs": max(abs(v) for v in values)}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("pc_obs_dump", type=Path)
    parser.add_argument("android_live_obs_dump", type=Path)
    args = parser.parse_args()
    pc, phone = read(args.pc_obs_dump), read(args.android_live_obs_dump)
    common = pc.keys() & phone.keys()
    differences = {name: [] for name in NUMERIC}
    lli_mismatch = code_mismatch = 0
    for key in common:
        a, b = pc[key], phone[key]
        for name in NUMERIC:
            differences[name].append(float(b[name]) - float(a[name]))
        lli_mismatch += a["LLI"] != b["LLI"]
        code_mismatch += a["code"] != b["code"]
    report = {
        "pc_signals": len(pc), "android_signals": len(phone),
        "matched_sat_signal_epoch": len(common),
        "pc_only": len(pc.keys() - phone.keys()),
        "android_only": len(phone.keys() - pc.keys()),
        "android_minus_pc": {name: stats(v) for name, v in differences.items()},
        "lli_mismatch": lli_mismatch, "code_numeric_mismatch": code_mismatch,
    }
    print(json.dumps(report, indent=2))
    if not common:
        raise SystemExit("No common epochs: use the same capture on phone and PC")


if __name__ == "__main__":
    main()
