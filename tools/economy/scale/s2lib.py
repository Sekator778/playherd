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

