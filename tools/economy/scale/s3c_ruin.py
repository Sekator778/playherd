import numpy as np
from s2lib import *
rng = np.random.default_rng(13)
def paths(mode, scn, a, n, nsteps):
    return np.array([pnl_steps(mode, scn, a, nsteps, rng) for _ in range(n)])
def stats(P, lam, F):
    d = F / STEPS_DAY
    br = 0; m70 = 0; m50 = 0; m0 = 0; fin = []
    for p in P:
        cum = np.cumsum(lam * p + d)
        br += breaker_hit(cum) >= 0.10
        mn = 1 + cum.min(); m70 += mn <= 0.7; m50 += mn <= 0.5; m0 += mn <= 0.0; fin.append(cum[-1])
    n = len(P)
    return br / n, m70 / n, m50 / n, m0 / n, np.median(fin)
print("365-day paths, 250 each; columns: P(breaker -10% vs 30d max), P(T<=70%), P(T<=50%), P(T<=0), median end P&L %T")
for scn, a in [("S1", .5), ("S2", .5), ("S3", .52), ("S4", .52)]:
    P = paths("joint", scn, a, 250, 365 * STEPS_DAY)
    for lam, F in [(1, 0), (0.25, 0), (0.1, 0), (0.05, 0), (0.02, 0), (0.1, 1e-3), (0.02, 1e-3), (0.005, 1e-4), (0.02, 1e-4), (0.01,1e-4)]:
        r = stats(P, lam, F)
        print(f"{scn} a={a} lambda={lam} fees={F*100:.2f}%T/day: " + " ".join(f"{x:.3f}" for x in r[:4]) + f" median {r[4]*100:+.1f}%", flush=True)
