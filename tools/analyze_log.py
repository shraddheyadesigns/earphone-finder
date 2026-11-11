"""Plot a recorded hunt: raw signal, smoothed signal, and the sweep as a polar plot.

Pull logs off the phone first:
    adb pull /sdcard/Android/data/com.shady.earphonefinder/files/logs ./logs

Then:
    pip install matplotlib
    python tools/analyze_log.py logs/track-20261111-153000-Buds.csv
"""
import csv
import math
import statistics
import sys

import matplotlib.pyplot as plt

path = sys.argv[1]
rows = list(csv.DictReader(open(path)))
t0 = int(rows[0]["t_ms"])
t = [(int(r["t_ms"]) - t0) / 1000 for r in rows]
raw = [int(r["rssi"]) for r in rows]
sm = [float(r["smoothed"]) for r in rows]

gaps = [b - a for a, b in zip(t, t[1:])]
print(f"{len(rows)} readings over {t[-1]:.0f} s, {len(rows) / max(t[-1], 1):.1f} per second")
print(f"median gap {statistics.median(gaps) * 1000:.0f} ms, longest gap {max(gaps):.1f} s")
print(f"raw range {min(raw)} to {max(raw)} dBm")

sweep = [(float(r["heading"]), int(r["rssi"])) for r in rows if r["sweeping"] == "1" and r["heading"]]

fig = plt.figure(figsize=(11, 4.5))
ax = fig.add_subplot(1, 2 if sweep else 1, 1)
ax.plot(t, raw, ".", alpha=0.35, label="raw")
ax.plot(t, sm, "-", lw=2, label="smoothed")
ax.set_xlabel("seconds")
ax.set_ylabel("dBm")
ax.legend()

if sweep:
    bins = {}
    for h, r in sweep:
        bins.setdefault(int(((h + 15) % 360) // 30), []).append(r)
    ax2 = fig.add_subplot(1, 2, 2, projection="polar")
    ax2.set_theta_zero_location("N")
    ax2.set_theta_direction(-1)
    ax2.scatter([math.radians(h) for h, _ in sweep], [r for _, r in sweep], s=6, alpha=0.4)
    keys = sorted(bins)
    ax2.plot([math.radians(k * 30) for k in keys] + [math.radians(keys[0] * 30)],
             [statistics.median(bins[k]) for k in keys] + [statistics.median(bins[keys[0]])], lw=2)
    ax2.set_title("sweep: signal by compass heading")

plt.tight_layout()
plt.show()
