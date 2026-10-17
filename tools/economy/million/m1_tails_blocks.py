"""Block length (Politis-White / Patton-Politis-White, arch.bootstrap.optimal_block_length) and POT/GPD tails (McNeil 1999, scipy.stats.genpareto).
Usage: python -E -s m1_tails_blocks.py > out_m1.txt
"""
import numpy as np
from scipy.stats import genpareto
from arch.bootstrap import optimal_block_length
import mcommon as mc

R3, R15, info = mc.build_rows()
print("candles dir:", mc.candles_dir(), "| days of data:", round(info["days"], 1), "| 15-min rows:", len(R15))

# ---- block length (units: 15-minute rows) ----
ser = {}
ser["log per-step variance proxy sum_m (W15*R15)^2"] = np.log(((R15 * mc.W15) ** 2).sum(1) + 1e-18)
ser["log |R15| BTC"] = np.log(np.abs(R15[:, 0]) + 1e-9)
ser["log |R15| ETH"] = np.log(np.abs(R15[:, 1]) + 1e-9)
ser["log |R15| SOL"] = np.log(np.abs(R15[:, 2]) + 1e-9)
ser["herd P&L crypto sum_crypto W15*R15"] = (R15[:, :6] * mc.W15[:6]).sum(1)
ser["herd P&L other sum_other W15*R15"] = (R15[:, 6:] * mc.W15[6:]).sum(1)
print("\n== optimal block length, stationary bootstrap (rows of 15 min) ==")
bl = {}
for k, v in ser.items():
    o = optimal_block_length(v)
    bl[k] = float(o["stationary"].iloc[0])
    print(f"  {k}: stationary {bl[k]:.2f} rows ({bl[k]*15/60:.1f} h), circular {float(o['circular'].iloc[0]):.2f}")
chosen = max(2.0, round(max(bl.values())))
print(f"CHOSEN mean block length (max over the series above, >=2): {chosen} rows = {chosen*15/60:.2f} h")
np.save(f"{mc.CACHE}/block_len.npy", np.array([chosen]))

# ---- POT / GPD for the round move ----
print("\n== POT/GPD on |3-min return| (non-overlapping 3-min rounds) and |15-min return| ==")
print("market | n | u(97.5%) | xi | beta | q99.9 GPD | q99.9 empirical | stress in code | q99.99 GPD | max |r| in sample")
for j, m in enumerate(mc.MK[:3]):
    for lab, x, st in (("3m", np.abs(R3[:, :, j]).ravel(), mc.s3[j]), ("15m", np.abs(R15[:, j]), mc.s15[j])):
        u = np.quantile(x, 0.975)
        ex = x[x > u] - u
        xi, loc, beta = genpareto.fit(ex, floc=0)
        n, nu = len(x), len(ex)
        def q(p):  # McNeil 1999 eq. for POT quantile
            return u + beta / xi * (((1 - p) * n / nu) ** (-xi) - 1) if abs(xi) > 1e-9 else u - beta * np.log((1 - p) * n / nu)
        print(f"{m} {lab} | {n} | {u*1e4:.2f}bp | {xi:.3f} | {beta*1e4:.2f}bp | {q(.999)*1e4:.1f}bp | {np.quantile(x,.999)*1e4:.1f}bp | {st*1e4:.1f}bp | {q(.9999)*1e4:.1f}bp | {x.max()*1e4:.1f}bp")
