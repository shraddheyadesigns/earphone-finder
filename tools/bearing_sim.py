"""Walk-to-calibrate: can steps + compass + noisy RSSI find the bud's direction?
Mirrors engine/BearingEstimator.kt.

Model: user walks a few steps in some directions. Each step moves them ~0.7 m along the
compass heading. RSSI samples arrive ~8/s, path loss n=2.5 plus 4 dB noise and dropouts.
We fit  rssi = a + gx*x + gy*y  (weighted least squares, recent samples weigh more)
over the walked positions. The gradient (gx, gy) points toward the bud.
"""
import math, random, statistics

STEP_M = 0.7


class Estimator:
    def __init__(s, window_s=20.0, tau_s=8.0, min_span_m=1.2):
        s.window, s.tau, s.min_span = window_s, tau_s, min_span_m
        s.x = s.y = 0.0
        s.samples = []  # (t, x, y, rssi)

    def on_step(s, heading_deg):
        a = math.radians(heading_deg)
        s.x += STEP_M * math.sin(a)   # east
        s.y += STEP_M * math.cos(a)   # north

    def on_rssi(s, t, rssi):
        s.samples.append((t, s.x, s.y, rssi))
        s.samples = [p for p in s.samples if t - p[0] <= s.window]

    def estimate(s, t):
        pts = s.samples
        if len(pts) < 15:
            return None
        xs = [p[1] for p in pts]; ys = [p[2] for p in pts]
        # how much ground was covered, in the weakest direction (needs 2D spread)
        mx, my = statistics.fmean(xs), statistics.fmean(ys)
        sxx = sum((x - mx) ** 2 for x in xs) / len(xs)
        syy = sum((y - my) ** 2 for y in ys) / len(ys)
        sxy = sum((x - mx) * (y - my) for x, y in zip(xs, ys)) / len(xs)
        tr, det = sxx + syy, sxx * syy - sxy * sxy
        lam_max = tr / 2 + math.sqrt(max(tr * tr / 4 - det, 0))
        span = 2 * math.sqrt(lam_max)            # spread along main walking line
        if span < s.min_span:
            return None
        # weighted least squares on centred coords
        W = [math.exp(-(t - p[0]) / s.tau) for p in pts]
        sw = sum(W)
        cx = sum(w * p[1] for w, p in zip(W, pts)) / sw
        cy = sum(w * p[2] for w, p in zip(W, pts)) / sw
        cr = sum(w * p[3] for w, p in zip(W, pts)) / sw
        a11 = a12 = a22 = b1 = b2 = 0.0
        for w, p in zip(W, pts):
            dx, dy, dr = p[1] - cx, p[2] - cy, p[3] - cr
            a11 += w * dx * dx; a12 += w * dx * dy; a22 += w * dy * dy
            b1 += w * dx * dr; b2 += w * dy * dr
        lam = 1e-3 * sw                           # tiny ridge so a 1-D walk doesn't explode
        a11 += lam; a22 += lam
        D = a11 * a22 - a12 * a12
        gx = (a22 * b1 - a12 * b2) / D
        gy = (a11 * b2 - a12 * b1) / D
        # residual noise -> standard error of the gradient
        res = [p[3] - (cr + gx * (p[1] - cx) + gy * (p[2] - cy)) for p in pts]
        sig2 = sum(w * r * r for w, r in zip(W, res)) / sw
        sw2 = sum(w * w for w in W)
        se = math.sqrt(sig2 * (a11 + a22) / D * sw2 / sw) if D > 0 else 9
        g = math.hypot(gx, gy)
        conf = g / (g + 2.5 * se + 1e-9)
        bearing = (math.degrees(math.atan2(gx, gy)) + 360) % 360
        return bearing, conf, g, se


def ang(a, b):
    return abs((a - b + 180) % 360 - 180)


def run(trials=300, seed=1, bud_dist=(3, 8)):
    random.seed(seed)
    errs, found_t, confs_wrong = [], [], 0
    never = 0
    for _ in range(trials):
        d0 = random.uniform(*bud_dist)
        bb = random.uniform(0, 360)
        bud = (d0 * math.sin(math.radians(bb)), d0 * math.cos(math.radians(bb)))
        est = Estimator()
        t, next_step, heading = 0.0, 0.6, random.uniform(0, 360)
        legs = 0; steps_in_leg = 0; leg_len = random.randint(3, 5)
        got = None
        while t < 30:
            t += random.uniform(.08, .2)
            if t >= next_step:
                heading_noise = random.gauss(0, 10)       # compass is never perfect
                est.on_step(heading + heading_noise)
                next_step = t + random.uniform(.5, .7)
                steps_in_leg += 1
                if steps_in_leg >= leg_len:              # turn and walk another way
                    heading = (heading + random.uniform(70, 140) * random.choice([-1, 1])) % 360
                    steps_in_leg = 0; leg_len = random.randint(3, 5); legs += 1
            # true position = estimator position (dead reckoning error handled via heading noise)
            dist = max(.3, math.hypot(bud[0] - est.x, bud[1] - est.y))
            if random.random() < .1:
                continue
            r = -59 - 25 * math.log10(dist) + random.gauss(0, 4)
            est.on_rssi(t, r)
            e = est.estimate(t)
            if e and e[1] > .55 and got is None:
                true_b = (math.degrees(math.atan2(bud[0] - est.x, bud[1] - est.y)) + 360) % 360
                got = (t, ang(e[0], true_b), e[1])
        if got is None:
            never += 1
        else:
            found_t.append(got[0]); errs.append(got[1])
            if got[1] > 60: confs_wrong += 1
    errs.sort()
    n = len(errs)
    print(f"dist {bud_dist}: locked in {n}/{trials}; median time {statistics.median(found_t):.1f}s; "
          f"error median {statistics.median(errs):.0f} deg, 80% < {errs[int(.8*n)]:.0f}, 90% < {errs[int(.9*n)]:.0f}; "
          f"badly wrong (>60) {100*confs_wrong/max(n,1):.0f}%")


if __name__ == "__main__":
    run(bud_dist=(3, 6))
    run(bud_dist=(6, 10))
    run(bud_dist=(1.5, 3))
