import numpy as np
from s2lib import *
rng = np.random.default_rng(11)
def paths(mode, scn, a, n, nsteps):
    return np.array([pnl_steps(mode, scn, a, nsteps, rng) for _ in range(n)])
def hit(P, lam, F):
    d = F / STEPS_DAY
    return np.mean([breaker_hit(np.cumsum(lam * p + d)) >= 0.10 for p in P])
for scn, a in [("S3", .52), ("S4", .52), ("S3", .56)]:
    P30 = paths("joint", scn, a, 1500, W30); P365 = paths("joint", scn, a, 200, 365 * STEPS_DAY)
    print(f"--- {scn} a={a}: mean P&L/day at lambda=1: {P30.mean()*STEPS_DAY*100:.3f}% T")
    for lam in [0.1, 0.02, 0.005]:
        for F in [0.0, 1e-4, 1e-3, 1e-2]:
            print(f"  lambda={lam} fees={F*100:.2f}%T/day: P(breaker 30d)={hit(P30,lam,F):.3f}  P(breaker 365d)={hit(P365,lam,F):.3f}", flush=True)
