"""Units (divisor q of pack, minimum stake and network fee), representability in 0.001 PLAY (PlayAmount.java:24) and RTP with the herd-fee rounding
(EconomyRules.java:83-91: ceil to 0.001, at least 0.001).  Plus the capacity identity N <= kappa*T/pack and the saturation table lambda_eff = kappa*L*rho/sum(W).
Usage: python -E -s m6_units.py > out_m6.txt
"""
import numpy as np
import mcommon as mc

print("== representability: pack, min stake, network fee, in PLAY, must be multiples of 0.001 and >= 0.001 ==")
for q in (1, 10, 100, 1000):
    vals = {"pack": mc.PACK / q, "min stake": mc.MIN_STAKE / q, "network fee": mc.NET_FEE / q, "stake tiers 250/500/1200": 250 / q}
    ok = {k: abs(v * 1000 - round(v * 1000)) < 1e-9 and v >= 0.001 for k, v in vals.items()}
    print(f"q={q}: " + "; ".join(f"{k}={v:g} {'ok' if ok[k] else 'NOT representable'}" for k, v in vals.items()))

print("\n== RTP cost of a bet (herd fee + network fee) in % of the stake, BTC (r_m = 0.92bp); stake given as % of the pack ==")
print("stake % of pack | L | q=1 | q=10 | q=100 | change vs q=1, pp (q=10 / q=100)")
for pct in (1.0, 2.0, 5.0, 10.0, 50.0):
    for L in (2, 10, 50, 240):
        costs = []
        for q in (1, 10, 100):
            s = mc.PACK / q * pct / 100.0
            s = max(s, mc.MIN_STAKE / q)
            herd = max(np.ceil(s * min(0.01, 0.92e-4 * L) * 1000 - 1e-9) / 1000, 0.001)
            costs.append((herd + mc.NET_FEE / q) / s * 100)
        print(f"{pct}% | {L} | {costs[0]:.4f} | {costs[1]:.4f} | {costs[2]:.4f} | {costs[1]-costs[0]:+.4f} / {costs[2]-costs[0]:+.4f}")

print("\n== capacity identity: N players need N*pack <= kappa*T (kappa = share of the treasury held by players), pack = 10000/q ==")
print("T | q | pack | kappa for N=1e6 | max N for kappa=1% | max N for kappa=10%")
for T in (1e8, 1e9, 1e10, 1e11, 1e12):
    for q in (1, 10, 100):
        pack = mc.PACK / q
        print(f"{T:g} | {q} | {pack:g} | {1e6*pack/T:.4g} | {0.01*T/pack:,.0f} | {0.10*T/pack:,.0f}")

SW = float(mc.W3.sum())
print(f"\n== saturation: net notional the limits allow per 3-min round = sum W = {SW:.3f} T (all 12 price markets, one side).  lambda_eff = kappa_active * L * rho / {SW:.2f} ==")
print("kappa_active | L=10 | L=50 | L=240   (rho = 1: every unit of active players' money is staked on the same side)")
for k in (0.0003, 0.001, 0.003, 0.01, 0.03, 0.1, 0.3):
    print(f"{k:g} | " + " | ".join(f"{min(1.0, k*L/SW):.4f}" for L in (10, 50, 240)))
