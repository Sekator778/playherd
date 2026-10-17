"""Variant A, question 3 (effect): limit of an odds market at T = 1e12, lambda = 0.05, today's stress 0.20 vs the proposed stress 1.00,
and the share of the normal 1M crowd's bets rejected. Demand = max m9 cell (out_m9.txt: lam_dem = 2.42e-5 of the summed 12-market limits, T = 1e12, q = 1);
how much of it goes to the six odds markets is UNKNOWN: scenarios. Also the fee check of the odds markets from out_a1.txt numbers.
Usage: python -E -s a4_thin_markets.py > out_a4.txt
"""
import mcommon as mc

T = 1e12; LAM = 0.05; DEM = 2.42e-5; SUMW = float(mc.W3.sum())
net_demand = DEM * SUMW * T            # net notional per side per round, whole crowd, all markets
print(f"normal-crowd peak net notional per side per round (all markets): {net_demand:.3g} PLAY  (= {DEM} x {SUMW:.3f} x T)")
for stress in (0.20, 1.00):
    lim = LAM * mc.MARKET_SHARE * T / stress
    print(f"\nodds market, stress {stress:.2f}: limit per side per round = {LAM} x 0.0025 x T / {stress} = {lim:.3g} PLAY of notional (risk at stress {LAM*mc.MARKET_SHARE*100:.4f}% of T)")
    print("  share of crowd on odds | markets sharing it | demand x | demand per market | reject share")
    for s in (0.1, 0.5, 1.0):
        for n in (6, 1):
            for mult in (1, 10, 100):
                d = net_demand * s / n * mult
                print(f"  {s:4.1f} | {n} | x{mult:<3d} | {d:9.3g} | {max(0.0, 1 - lim/d):.3f}")
    print(f"  demand multiplier (on one market, whole crowd) at which the reject share reaches 1%: x{lim/0.99/net_demand:.2f}")

print("\nodds fee (closed form a* = 1/2 + r/(2 E|dp/p|); E|dp/p| per round from out_a1.txt: 3-min 0.0024, 15-min 0.0074; 94% of 3-min windows have no price change):")
for r_bp, k, nm in ((1.0, 1.0, "today r_m 1.0, 15-min = same r"), (2.5, 1.0, "proposal 2.5 bp, 3-min"), (2.5 * 3, 1.0, "proposal 7.5 bp (k = 3), 15-min")):
    e = 0.0024 if "15" not in nm else 0.0074
    if "today" in nm:
        for e_, rn in ((0.0024, "3-min"), (0.0074, "15-min")):
            print(f"  {nm} {rn}: a* = {100*(0.5 + r_bp*1e-4/(2*e_)):.1f}%")
    else:
        print(f"  {nm}: a* = {100*(0.5 + r_bp*1e-4/(2*e)):.1f}%")
