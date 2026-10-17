import numpy as np
from s2lib import *
rng = np.random.default_rng(7)
sig = np.array([SIGMA_BP[m] for m in MK]) * 1e-4
y = FEE_R * sig                       # herd fee per unit notional (game_economy_proposal.md 6.3)
# fee income per step per unit lambda, units of T, for gross volume G = n/b  (n = c*T/s net notional)
f3 = (y * c / s3).sum()               # per 3-min round per 1/b
f15 = (y * c / s15).sum()
steps_day = 480
per_day = steps_day * f3 + (steps_day // 5) * f15
print(f"fee income per day at lambda=1 for b=1: {per_day*100:.4f}% T ; per 1/b")
for b in [1, 0.6, 0.2, 0.05, 0.01]:
    G3 = (c / s3).sum() / b   # gross notional per 3-min round, in units of T (sum over markets of n/b)
    G15 = (c / s15).sum() / b
    print(f"  b={b}: fee/day={per_day/b*100:.3f}% T; gross notional per 3-min round = {G3:.2f} T ({G3*1e8/1e6:.0f}M at T=1e8), 15-min {G15:.2f} T; "
          f"stakes at L=10: {G3/10*100:.1f}% T + {G15/10*100:.1f}% T = K0 needed {100*(G3+G15)/10:.1f}% of T")
# 'ordinary' leak per day for informed a: (2a-1)*E|r| * n summed
Eabs = np.abs(J3).mean(0)
leak3 = ((c / s3) * Eabs).sum(); leak15 = ((c / s15) * np.abs(J15).mean(0)).sum()
for a in [0.52, 0.56]:
    d = (2 * a - 1) * (steps_day * leak3 + steps_day // 5 * leak15)
    print(f"  informed independent a={a}: leak {d*100:.3f}% T/day at lambda=1 (net at limit)  -> fee covers when 1/b >= {d/per_day:.2f}, i.e. b <= {per_day/d:.3f}")

# safe lambda: bisect so that P(dd>=10% in 30 d) <= target
def paths(mode, scn, a, n, nsteps):
    return np.array([pnl_steps(mode, scn, a, nsteps, rng) for _ in range(n)])
def p_hit(P, lam, drift=0.0):
    return np.mean([breaker_hit(np.cumsum(lam * p + drift)) >= 0.10 for p in P])
for mode in ["joint", "indep"]:
    for scn in ["S1", "S2"]:
        P30 = paths(mode, scn, .5, 3000, W30)
        P365 = paths(mode, scn, .5, 300, 365 * STEPS_DAY)
        for lam in [0.2, 0.15, 0.1, 0.05, 0.03]:
            print(f"[{mode}] {scn} lambda={lam}: P(breaker 30d)={p_hit(P30, lam):.4f}  P(breaker 365d)={p_hit(P365, lam):.3f}", flush=True)
