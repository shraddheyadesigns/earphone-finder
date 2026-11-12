"""Shared direction map: one live estimate of where the bud is, fed by walking, turning and spins.
Mirrors app/src/main/java/.../engine/DirectionMap.kt.

Grid of candidate bud spots around where the hunt started. For every reading we know where the
walker is (steps + compass) and which way they face. For each spot:
    expected = A - 10 n log10(d) - BODY * blocked(facing vs direction to spot)
A (the bud's loudness) is unknown, so each spot fits its own A: we keep running sums of the
residual r = rssi - shape(d, angle) and score the spot by how consistent r is (low variance = likely).
Old evidence fades (tau). Posterior -> bearing to the likely spot + how concentrated it is.
"""
import math, random, statistics

N_EXP, BODY, SIGMA = 2.5, 9.0, 7.0   # sigma 7: honest confidence when the room doesn't match the model
STEP_M = 0.7


class DirectionMap:
    def __init__(s, half_m=10.0, cell_m=0.5, tau_s=30.0):
        s.n = int(2 * half_m / cell_m) + 1
        s.cell, s.half, s.tau = cell_m, half_m, tau_s
        s.cx = [-half_m + i * cell_m for i in range(s.n)]
        size = s.n * s.n
        s.W = [0.0] * size; s.S1 = [0.0] * size; s.S2 = [0.0] * size
        s.last_t = None
        s.x = s.y = 0.0

    def step(s, heading):
        a = math.radians(heading)
        s.x += STEP_M * math.sin(a); s.y += STEP_M * math.cos(a)

    def add(s, t, rssi, heading, weight=1.0):
        if s.last_t is not None:
            k = math.exp(-(t - s.last_t) / s.tau)
            if k < 0.999:
                for i in range(len(s.W)):
                    s.W[i] *= k; s.S1[i] *= k; s.S2[i] *= k
        s.last_t = t
        h = math.radians(heading)
        for j, gy in enumerate(s.cx):
            for i, gx in enumerate(s.cx):
                dx, dy = gx - s.x, gy - s.y
                d = max(0.3, math.hypot(dx, dy))
                off = abs(((math.atan2(dx, dy) - h + math.pi) % (2 * math.pi)) - math.pi)
                blocked = max(0.0, (off - math.pi / 2) / (math.pi / 2))
                r = rssi - (-10 * N_EXP * math.log10(d) - BODY * blocked)
                idx = j * s.n + i
                s.W[idx] += weight; s.S1[idx] += weight * r; s.S2[idx] += weight * r * r

    def estimate(s):
        """bearing (deg, compass) from the walker to the likely spot, concentration 0..1, distance m."""
        best = None
        costs = []
        for idx in range(len(s.W)):
            w = s.W[idx]
            if w <= 0:
                return None
            c = (s.S2[idx] - s.S1[idx] ** 2 / w) / (2 * SIGMA * SIGMA)
            costs.append(c)
        m = min(costs)
        X = Y = Z = 0.0
        sx = sy = 0.0
        for idx, c in enumerate(costs):
            p = math.exp(-(c - m))
            if p < 1e-6:
                continue
            gx = s.cx[idx % s.n]; gy = s.cx[idx // s.n]
            dx, dy = gx - s.x, gy - s.y
            b = math.atan2(dx, dy)
            X += p * math.sin(b); Y += p * math.cos(b); Z += p
            sx += p * dx; sy += p * dy
        R = math.hypot(X, Y) / Z
        bearing = (math.degrees(math.atan2(X, Y)) + 360) % 360
        return bearing, R, math.hypot(sx / Z, sy / Z)


def ang(a, b):
    return abs((a - b + 180) % 360 - 180)


def run(trials=40, seed=3, mode="walk", label=""):
    random.seed(seed)
    errs_end, conf_end, flips = [], [], 0
    for _ in range(trials):
        bud = (random.uniform(-6, 6), random.uniform(-6, 6))
        while math.hypot(*bud) < 2.5:
            bud = (random.uniform(-6, 6), random.uniform(-6, 6))
        M = DirectionMap()
        t, heading = 0.0, random.uniform(0, 360)
        def reading(w=1.0):
            dx, dy = bud[0] - M.x, bud[1] - M.y
            d = max(.3, math.hypot(dx, dy))
            off = ang(math.degrees(math.atan2(dx, dy)), heading)
            blocked = max(0, (off - 90) / 90)
            r = -59 - 25 * math.log10(d) - 9 * blocked + random.gauss(0, 4)
            M.add(t, r, heading + random.gauss(0, 8), w)
        if mode in ("spin", "both"):
            for k in range(20):                      # one slow spin, ~1 reading a second
                t += 1.0; heading = (heading + 18) % 360; reading(1.5)
        if mode in ("walk", "both"):
            for leg in range(3):                     # three legs of 4 steps, turning between
                for st in range(4):
                    t += .6; M.step(heading + random.gauss(0, 8))
                    if random.random() < .9: reading()
                heading = (heading + random.choice([-1, 1]) * random.uniform(60, 120)) % 360
                for _ in range(2):
                    t += 1.0; reading()
        e = M.estimate()
        true_b = (math.degrees(math.atan2(bud[0] - M.x, bud[1] - M.y)) + 360) % 360
        err = ang(e[0], true_b)
        errs_end.append(err); conf_end.append(e[1])
        if err > 120: flips += 1
    errs_end.sort()
    print(f"{label:>18}: median error {statistics.median(errs_end):.0f} deg, 80% within {errs_end[int(.8*len(errs_end))]:.0f}, "
          f"flipped {100*flips/trials:.0f}%, median confidence {statistics.median(conf_end):.2f}")


if __name__ == "__main__":
    run(mode="spin", label="one spin")
    run(mode="walk", label="walking only")
    run(mode="both", label="spin then walk")
