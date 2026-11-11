"""Simulation used to pick the smoothing and sweep parameters.
Mirrors app/src/main/java/.../engine/*.kt so numbers stay in sync."""
import math, random, statistics

random.seed(4)

# ---------- smoother (mirror of RssiSmoother.kt) ----------
class Smoother:
    def __init__(s, median_ms=2000, tau_ms=1500.0, look_ms=3000, thr=3.0):
        s.median_ms, s.tau, s.look, s.thr = median_ms, tau_ms, look_ms, thr
        s.raw, s.hist, s.val, s.last = [], [], None, 0

    def add(s, t, r):
        s.raw.append((t, r))
        s.raw = [x for x in s.raw if t - x[0] <= s.median_ms]
        med = statistics.median(x[1] for x in s.raw)
        if s.val is None:
            s.val = med
        else:
            a = 1 - math.exp(-max(0, t - s.last) / s.tau)
            s.val += a * (med - s.val)
        s.last = t
        s.hist.append((t, s.val))
        s.hist = [h for h in s.hist if t - h[0] <= 10_000]
        return s.val

    def trend(s, now):
        past = [h for h in s.hist if h[0] <= now - s.look]
        if not past or s.val is None:
            return "unknown"
        d = s.val - past[-1][1]
        return "warmer" if d > s.thr else "colder" if d < -s.thr else "steady"


def rssi_at(dist_m, behind_body=False):
    base = -59 - 25 * math.log10(max(dist_m, 0.2))      # path loss, n = 2.5
    if behind_body:
        base -= 9                                         # body blocks ~6-12 dB
    return round(base + random.gauss(0, 4) + (random.random() < 0.08) * random.choice([-12, 8]))


# Test 1: walk from 5 m to 0.5 m over 20 s, ads every ~150 ms with dropouts
print("Test 1: walking toward the bud")
sm, t, raw_err, sm_err, flips = Smoother(), 0, [], [], 0
prev_trend = None
while t < 20_000:
    t += random.randint(100, 250) if random.random() > 0.1 else random.randint(600, 1500)
    d = 5 - 4.5 * t / 20_000
    truth = -59 - 25 * math.log10(d)
    r = rssi_at(d)
    v = sm.add(t, r)
    raw_err.append(abs(r - truth)); sm_err.append(abs(v - truth))
    tr = sm.trend(t)
    if t > 4000:
        if tr == "colder":
            flips += 1
print(f"  mean abs error raw {statistics.mean(raw_err):.1f} dB -> smoothed {statistics.mean(sm_err):.1f} dB")
print(f"  wrong 'colder' readings while walking closer: {flips}")

# Test 2: standing still at 2 m for 30 s: how often does trend lie?
sm, t, lies, n = Smoother(), 0, 0, 0
while t < 30_000:
    t += random.randint(100, 250)
    sm.add(t, rssi_at(2.0))
    if t > 4000:
        n += 1; lies += sm.trend(t) != "steady"
print(f"Test 2: standing still, trend says warmer/colder {100*lies/n:.0f}% of the time")


# ---------- sweep (mirror of DirectionSweep.kt) ----------
class Sweep:
    def __init__(s, sectors=12):
        s.n = sectors; s.size = 360 / sectors; s.bins = [[] for _ in range(sectors)]

    def add(s, heading, r):
        i = int(((heading % 360) + s.size / 2) // s.size) % s.n
        s.bins[i].append(r)

    def coverage(s):
        return sum(1 for b in s.bins if b) / s.n

    def result(s):
        if s.coverage() < 0.75:
            return None
        med = {i: statistics.median(b) for i, b in enumerate(s.bins) if b}
        lo, hi = min(med.values()), max(med.values())
        x = y = 0.0
        for i, m in med.items():
            w = (m - lo) ** 2
            a = math.radians(i * s.size)
            x += w * math.sin(a); y += w * math.cos(a)
        heading = (math.degrees(math.atan2(x, y)) + 360) % 360
        return heading, hi - lo


def ang(a, b):
    return abs((a - b + 180) % 360 - 180)

print("Test 3: 360 degree sweep, bud at random bearing 2.5 m away, 200 trials")
errs, spreads = [], []
for _ in range(200):
    bud = random.uniform(0, 360)
    sw, t, heading = Sweep(), 0, random.uniform(0, 360)
    while t < 20_000:                                     # ~20 s per full turn
        t += random.randint(100, 250)
        heading = (heading + 360 * 0.175 / 20 * random.uniform(0.6, 1.4) * 1) % 360
        off = ang(heading, bud)
        block = max(0.0, (off - 90) / 90)                 # 0 when facing, 1 when bud directly behind
        r = rssi_at(2.5) - 10 * block + 0                 # extra body loss scales with angle
        sw.add(heading, r)
    h, spread = sw.result()
    errs.append(ang(h, bud)); spreads.append(spread)
errs.sort()
print(f"  median error {statistics.median(errs):.0f} deg, 80% within {errs[int(.8*len(errs))]:.0f} deg, "
      f"90% within {errs[int(.9*len(errs))]:.0f} deg; median spread {statistics.median(spreads):.1f} dB")
