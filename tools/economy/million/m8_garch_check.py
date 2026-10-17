"""Cross-check of the block bootstrap with GARCH(1,1) with Student-t innovations (Bollerslev 1986; arch package).
Scalar test series: y_t = sum over the 12 markets of W15*R15 per 15-min row = P&L of the treasury per row if every market's heavy crowd is on the same side
(sign flips at random each row, so only the volatility process matters).  Same breaker rule, same multiplicative treasury, lam as the argument.
Usage: python -E -s m8_garch_check.py > out_m8.txt
"""
import numpy as np
from arch import arch_model
import mcommon as mc

R3, R15, info = mc.build_rows()
y = (R15 * mc.W15).sum(1) + (R3 * mc.W3).sum((1, 2))      # per row, units of T at lambda=1 (one side everywhere)
print("rows", len(y), "days", round(info["days"], 1), "sd per row (T)", y.std(), "daily sd", y.std() * np.sqrt(96))
am = arch_model(y * 100, mean="Zero", vol="GARCH", p=1, q=1, dist="t")
res = am.fit(disp="off")
print(res.params.round(5).to_string())
persist = res.params["alpha[1]"] + res.params["beta[1]"]
print(f"persistence alpha+beta = {persist:.4f}")


def breaker_prob(paths, lam, days, flip_rng):
    """paths: [P, days*96] row P&L at lambda=1 (signed arbitrarily); returns P(breaker), P(T<=0.5), day loss q99.9"""
    P = paths.shape[0]
    sgn = np.where(flip_rng.random(paths.shape) < .5, -1.0, 1.0)
    f = 1.0 + lam * sgn * paths                    # treasury multiplier per row
    br = np.zeros(P, bool); lo50 = np.zeros(P, bool)
    T = np.ones(P); hist = np.ones((P, 30)); dl = []
    for d in range(days):
        seg = f[:, d * 96:(d + 1) * 96]
        Tp = T[:, None] * np.cumprod(seg, 1)
        run = np.maximum.accumulate(Tp, 1)
        Mx = np.maximum(hist[:, -29:].max(1)[:, None], run)
        br |= (Tp * 10 < Mx * 9).any(1); lo50 |= (Tp.min(1) <= .5)
        dl.append(Tp[:, -1] / T - 1)
        T = Tp[:, -1]; hist = np.concatenate([hist[:, 1:], run[:, -1:]], 1)
    return br.mean(), lo50.mean(), np.quantile(np.concatenate(dl), 0.001)


days = 365
P = 600
rng = np.random.default_rng(11)
block = float(np.load(f"{mc.CACHE}/block_len.npy")[0]) if __import__("os").path.exists(f"{mc.CACHE}/block_len.npy") else 20.0
idx = mc.stationary_paths(len(y), days * 96, block, P, rng)
boot = y[idx]
sim = np.empty((P, days * 96))
for i in range(P):
    s = am.simulate(res.params, days * 96, burn=500)["data"].values / 100.0
    sim[i] = s
iid = y[rng.integers(0, len(y), (P, days * 96))]
print(f"\nP(breaker within {days} d), P(T<=50%), 99.9% one-day loss  [single scalar series, all markets same side every row]; block length {block}")
print("lam | stationary bootstrap | GARCH(1,1)-t simulated | iid resampling")
for lam in (0.2, 0.1, 0.05, 0.03):
    a = breaker_prob(boot, lam, days, rng); b = breaker_prob(sim, lam, days, rng); c = breaker_prob(iid, lam, days, rng)
    print(f"{lam} | {a[0]:.3f} / {a[1]:.3f} / {a[2]*100:.2f}% | {b[0]:.3f} / {b[1]:.3f} / {b[2]*100:.2f}% | {c[0]:.3f} / {c[1]:.3f} / {c[2]*100:.2f}%")
print("sd of daily sum: bootstrap %.5f, GARCH %.5f, iid %.5f" % tuple(x.reshape(P, days, 96).sum(2).std() for x in (boot, sim, iid)))
