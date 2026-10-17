"""Variant A, question 1: is plain scaling of today's limits by lambda = 0.05 (no pool) enough at T = 1e12, 1M players?
Reads the cells of out_m9.txt (T = 1e12, q = 1, lambda 1 / 0.05) and out_m12.txt, and adds a concentration check: the m9 demand lam_dem is the normal crowd's
net notional spread over all 12 markets in proportion to their limits; here ALL of it is put on one market (BTC, or the 6-market crypto bucket) and the
reject share is recomputed against the scaled limit of that market. Demand parameters (heavy-side share 0.5, peak factor 5) are m9_grid.py's (UNVERIFIED).
Usage: python -E -s a2_lambda_scaling.py > out_a2.txt
"""
import re
import mcommon as mc

SUMW = float(mc.W3.sum())
W_BTC = mc.c_share[0] / mc.s3[0]            # net notional of BTC's own 0.25%... (bucket share 1%/6) in units of T at lambda = 1
W_BTC_mkt = mc.MARKET_SHARE / mc.s3[0]      # BTC's market limit as if it were a non-crypto market (looser than the bucket-bound one)
W_CRY = float(mc.W3[:6].sum())
print(f"sum of 3-min limits over 12 markets SUMW = {SUMW:.3f} T;  BTC bucket-share limit {W_BTC:.3f} T (market limit {W_BTC_mkt:.3f} T); 6 crypto markets together {W_CRY:.3f} T")
rows = []
for line in open("out_m9.txt"):
    m = re.search(r"ret=(\S+) reg=(\S+) fees->(\S+) q=1 T=1e\+12 lam=(\S+): .*Pbr365=(\S+) Pbr730=(\S+) P\(T<=50%\)=(\S+) lam_eff_peak=(\S+) lam_dem=(\S+) reject=(\S+)", line)
    if m:
        rows.append(m.groups())
print("\nm9 cells, T = 1e12, q = 1 (ret, reg, fees->, lam | Pbr365 Pbr730 P(T<=50%) lam_eff_peak lam_dem reject):")
for r in rows:
    print("  ", " ".join(r[:4]), "|", " ".join(r[4:]))
dem = max(float(r[8]) for r in rows)
print(f"\nmax normal-crowd demand over the cells: lam_dem = {dem:.3g} (share of the summed limits).  Policy lambda 0.05 -> headroom {0.05/dem:.0f}x before 1% of bets are rejected (C4).")
print("\nConcentration: all normal-crowd notional on ONE place; reject share = max(0, 1 - lambda*W_place/(dem*SUMW)) with lambda = 0.05")
for name, w in (("BTC (bucket-bound)", W_BTC), ("6 crypto markets together", W_CRY)):
    for mult in (1, 10):
        d = dem * SUMW * mult
        print(f"  {name:28s} demand x{mult:<3d}: demand {d:.3g} T per side vs limit {0.05*w:.3g} T -> reject {max(0.0, 1 - 0.05*w/d):.4f}")
print("\nm12 (breaker probability of the stress adversary, lambda 1 / 0.05, T = 1e12 and 1e11):")
for line in open("out_m12.txt"):
    if line.strip():
        print("  ", line.rstrip()[:230])
