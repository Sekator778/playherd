import numpy as np
from common import *

T = 1e8  # arithmetic upper bound used by the architecture review 6.2
# ---------- 1. capacity ceiling, re-derived ----------
print("== 1. CAPACITY (T = 1e8), 3-min round ==")
E = {m: MARKET_SHARE * T / STRESS[m] for m in STRESS}
Eo = MARKET_SHARE * T / ODDS_STRESS
# crypto bucket: sum(notional*stress) <= 1% T per side; fill ascending stress (max notional)
cs = sorted(CRYPTO, key=lambda m: STRESS[m])
risk_left = BUCKET_SHARE * T
bucket_notional = 0.0
alloc = {}
for m in cs:
    n = min(E[m], risk_left / STRESS[m])
    alloc[m] = n
    risk_left -= n * STRESS[m]
    bucket_notional += n
other_notional = sum(E[m] for m in OTHER)
odds_notional = N_ODDS * Eo
side = bucket_notional + other_notional + odds_notional
for m in CRYPTO:
    print(f"  {m}: per-side limit {E[m]/1e6:.2f}M, bucket alloc {alloc[m]/1e6:.2f}M")
print(f"  crypto bucket one side {bucket_notional/1e6:.1f}M; other 6 {other_notional/1e6:.1f}M; odds 6 {odds_notional/1e6:.1f}M")
print(f"  all 18 markets one side {side/1e6:.1f}M, both sides {2*side/1e6:.1f}M")
for name, S, L in [("min 100 x2", 100, 2), ("ordinary 1000 x10", 1000, 10), ("1000 x240 (BTC tier cap/5)", 1000, 240)]:
    N = S * L
    bets = 2 * side / N
    print(f"  {name}: notional {N}, bets/round {bets:,.0f}, per s (180 s) {bets/180:,.0f}, stakes needed {bets*S/1e6:,.0f}M PLAY")
# 15-min round: stress*2.2
side15 = side / LONG_FACTOR
print(f"  15-min round: one side {side15/1e6:.1f}M, both {2*side15/1e6:.1f}M; min bets {2*side15/200:,.0f}, per s (900 s) {2*side15/200/900:,.0f}")
print(f"  total avg rate min bets (3 min + 15 min) = {(2*side/200/180 + 2*side15/200/900):,.0f}/s")

# ---------- candles ----------
print("\n== CANDLE STATS ==")
raw = {s: load(s) for s in ["BTC", "ETH", "SOL", "NVDA", "GOLD"]}
t0c = min(r[0][0] for r in raw.values()); t1c = max(r[-1][0] for r in raw.values())
G = {s: grid(raw[s], t0c, t1c) for s in raw}
def cl(s): return G[s][:, 3]
r3 = {s: ret(cl(s), 3) for s in G}
r15 = {s: ret(cl(s), 15) for s in G}
for s in G:
    x = r3[s][~np.isnan(r3[s])]
    xd = ret(cl(s), 3, 3); xd = xd[~np.isnan(xd)]
    q = np.quantile(np.abs(xd), 0.999)
    print(f"  {s}: n3={len(x)} (disjoint {len(xd)}), sigma3={x.std()*1e4:.2f}bp, E|r|={np.abs(x).mean()*1e4:.2f}bp, E|r|/sigma={np.abs(x).mean()/x.std():.3f}, "
          f"q99.9|r| disjoint={q:.5f} (code stress {STRESS.get(s,float('nan'))}), max|r|={np.abs(x).max():.4f}")
    y = r15[s][~np.isnan(r15[s])]
    print(f"      sigma15/sigma3={y.std()/x.std():.2f}, q99.9|r15|/q99.9|r3|={np.quantile(np.abs(y),0.999)/np.quantile(np.abs(x),0.999):.2f}")
# correlations on disjoint 3-min returns (crypto 14 d; and all five on the 3.5 d overlap)
def disj(s): return ret(cl(s), 3, 3)
C = np.vstack([disj(s) for s in ["BTC", "ETH", "SOL"]]).T
C = C[~np.isnan(C).any(1)]
print("  crypto corr (3-min disjoint, 14 d, n=%d):" % len(C)); print(np.round(np.corrcoef(C.T), 3))
A = np.vstack([disj(s) for s in ["BTC", "ETH", "SOL", "NVDA", "GOLD"]]).T
A = A[~np.isnan(A).any(1)]
print("  five-series corr (3-min disjoint, overlap, n=%d):" % len(A)); print(np.round(np.corrcoef(A.T), 3))
A15 = np.vstack([ret(cl(s), 15, 15) for s in ["BTC", "ETH", "SOL", "NVDA", "GOLD"]]).T
A15 = A15[~np.isnan(A15).any(1)]
print("  five-series corr (15-min disjoint, n=%d):" % len(A15)); print(np.round(np.corrcoef(A15.T), 3))

# ---------- pools for simulation (overlapping windows at every minute offset) ----------
def pool(k):
    rs = {s: ret(cl(s), k) for s in G}
    cr = np.vstack([rs["BTC"], rs["ETH"], rs["SOL"]]).T
    ok = ~np.isnan(cr).any(1); cr = cr[ok]
    sol = cr[:, 2]
    f = lambda m: SIGMA_BP[m] / SIGMA_BP["SOL"]
    crypto = np.column_stack([cr[:, 0], cr[:, 1], sol, sol * f("DOGE"), sol * f("XRP"), sol * f("HYPE")])
    eq = np.vstack([rs["NVDA"], rs["GOLD"]]).T
    ok = ~np.isnan(eq).any(1); eq = eq[ok]
    nv, go = eq[:, 0], eq[:, 1]
    other = np.column_stack([nv, nv * SIGMA_BP["TSLA"] / SIGMA_BP["NVDA"], go * SIGMA_BP["BRENTOIL"] / SIGMA_BP["GOLD"], go,
                             nv * SIGMA_BP["XYZ100"] / SIGMA_BP["NVDA"], nv * SIGMA_BP["SP500"] / SIGMA_BP["NVDA"]])
    return crypto, other
c3, o3 = pool(3); c15, o15 = pool(15)
print("  pool sizes:", c3.shape, o3.shape, c15.shape, o15.shape)
np.savez("pools.npz", c3=c3, o3=o3, c15=c15, o15=o15)
# empirical stress vs code stress per pool column
for name, P, ms in [("crypto3", c3, CRYPTO), ("other3", o3, OTHER)]:
    print(" ", name, {m: round(float(np.quantile(np.abs(P[:, i]), .999)) / STRESS[m], 2) for i, m in enumerate(ms)})
