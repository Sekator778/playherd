"""Treasury loss distribution with the exposure sitting AT the limit in every one of the 12 priced markets, every round.
P&L of the treasury per round per market = - d * n * r, d = side of the heavier (net) crowd, n = net notional = c*T/s (c = limit share)."""
import numpy as np, sys
from scipy.ndimage import maximum_filter1d
from common import *

MK = CRYPTO + OTHER
s3 = np.array([STRESS[m] for m in MK]); s15 = s3 * LONG_FACTOR
c = np.array([BUCKET_SHARE / 6] * 6 + [MARKET_SHARE] * 6)   # crypto bucket 1% shared by 6 markets equally; others 0.25%
print("c (share of T at stress per market):", c, "sum =", c.sum())

# pools: joint (all 12 columns on the same time index, 3.5 d overlap) and independent (crypto 14 d, other 3.5 d)
P = np.load("pools.npz")
def joint(k):
    raw = {s: load(s) for s in ["BTC", "ETH", "SOL", "NVDA", "GOLD"]}
    t0 = min(r[0][0] for r in raw.values()); t1 = max(r[-1][0] for r in raw.values())
    cl = {s: grid(raw[s], t0, t1)[:, 3] for s in raw}
    rs = {s: ret(cl[s], k) for s in cl}
    X = np.vstack([rs[s] for s in ["BTC", "ETH", "SOL", "NVDA", "GOLD"]]).T
    X = X[~np.isnan(X).any(1)]
    b, e, so, nv, go = X.T
    f = lambda m: SIGMA_BP[m] / SIGMA_BP["SOL"]
    g = lambda m, base: SIGMA_BP[m] / SIGMA_BP[base]
    return np.column_stack([b, e, so, so * f("DOGE"), so * f("XRP"), so * f("HYPE"),
                            nv, nv * g("TSLA", "NVDA"), go * g("BRENTOIL", "GOLD"), go, nv * g("XYZ100", "NVDA"), nv * g("SP500", "NVDA")])
J3, J15 = joint(3), joint(15)
print("joint pools", J3.shape, J15.shape)

def draw(mode, k, n, rng):
    if mode == "joint":
        pool = J3 if k == 3 else J15
        return pool[rng.integers(0, len(pool), n)]
    cp, op = (P["c3"], P["o3"]) if k == 3 else (P["c15"], P["o15"])
    return np.hstack([cp[rng.integers(0, len(cp), n)], op[rng.integers(0, len(op), n)]])

def direction(scn, a, R, rng):
    n = R.shape[0]
    if scn == "S1":   # no information, each market's heavy side an independent coin
        return rng.choice([-1.0, 1.0], size=R.shape)
    if scn == "S2":   # herd: one coin for all crypto, one for the other group
        d = np.empty(R.shape); d[:, :6] = rng.choice([-1.0, 1.0], size=(n, 1)); d[:, 6:] = rng.choice([-1.0, 1.0], size=(n, 1)); return d
    if scn == "S3":   # informed independent: heavy side = sign(r) w.p. a
        return np.sign(R) * np.where(rng.random(R.shape) < a, 1.0, -1.0)
    if scn == "S4":   # informed herd: group direction = sign(r of the lead market) w.p. a
        d = np.empty(R.shape)
        d[:, :6] = (np.sign(R[:, 0]) * np.where(rng.random(n) < a, 1.0, -1.0))[:, None]
        d[:, 6:] = (np.sign(R[:, 6]) * np.where(rng.random(n) < a, 1.0, -1.0))[:, None]
        return d
    raise ValueError

def pnl_steps(mode, scn, a, nsteps, rng):
    R = draw(mode, 3, nsteps, rng)
    p = -(direction(scn, a, R, rng) * (c / s3) * R).sum(1)
    n15 = nsteps // 5
    R15 = draw(mode, 15, n15, rng)
    p15 = -(direction(scn, a, R15, rng) * (c / s15) * R15).sum(1)
    p[4::5][:n15] += p15
    return p

STEPS_DAY = 480           # 3-min rounds per day; 15-min rounds are every 5th step
W30 = 30 * STEPS_DAY
def breaker_hit(path_cum, thr=0.10):
    T = 1.0 + path_cum
    W = min(W30, len(T))
    M = maximum_filter1d(T, size=W, mode="nearest", origin=(W - 1) // 2)
    return ((M - T) / M).max()

scenarios = [("S1", 0.5), ("S2", 0.5), ("S3", 0.52), ("S3", 0.56), ("S4", 0.52)]
lams = [1.0, 0.5, 0.25]
rng = np.random.default_rng(20261016)
out = {}
for mode in ["joint", "indep"]:
    for scn, a in scenarios:
        # 30-day paths
        n30, n365 = 3000, 400
        dd30 = np.zeros(n30); day_losses = []; endpnl30 = np.zeros(n30)
        for i in range(n30):
            p = pnl_steps(mode, scn, a, W30, rng)
            cum = np.cumsum(p); dd30[i] = breaker_hit(cum); endpnl30[i] = cum[-1]
            day_losses.append(p.reshape(30, STEPS_DAY).sum(1))
            if i == 0: P30 = []
            P30.append(p)  # keep for lambda scaling below
        # lambda scaling: P&L is linear in exposure
        res = {}
        P30 = np.array(P30)
        for lam in lams:
            dd = np.array([breaker_hit(np.cumsum(lam * p)) for p in P30])
            res[lam] = dict(p30=(dd >= 0.10).mean(), p30_30=(dd >= 0.30).mean(), ddmed=np.median(dd), dd99=np.quantile(dd, .99))
        days = lam_days = np.concatenate(day_losses)
        q999 = np.quantile(days, 0.001); mean_day = days.mean(); sd_day = days.std()
        # 365-day paths
        h365 = {lam: 0 for lam in lams}; h365_30 = {lam: 0 for lam in lams}; final = []
        for i in range(n365):
            p = pnl_steps(mode, scn, a, 365 * STEPS_DAY, rng)
            cum = np.cumsum(p); final.append(cum[-1])
            for lam in lams:
                dd = breaker_hit(lam * cum)
                h365[lam] += dd >= 0.10; h365_30[lam] += dd >= 0.30
        print(f"[{mode}] {scn} a={a}: daily mean {mean_day*100:+.3f}% T, sd {sd_day*100:.3f}% T, 0.1% quantile of day P&L {q999*100:.3f}% T; "
              f"30d end P&L median {np.median(endpnl30)*100:+.2f}% T; 365d end median {np.median(final)*100:+.1f}% T")
        for lam in lams:
            r = res[lam]
            print(f"     lambda={lam}: P(breaker -10% in 30d)={r['p30']:.4f} ({int(r['p30']*n30)}/{n30}); dd99(30d)={r['dd99']*100:.2f}%; "
                  f"P(-30% in 30d)={r['p30_30']:.4f}; P(breaker in 365d)={h365[lam]/n365:.3f} ({h365[lam]}/{n365}); P(-30% in 365d)={h365_30[lam]/n365:.3f}; "
                  f"0.1%-day loss at lambda = {lam*q999*100:.3f}% T", flush=True)
