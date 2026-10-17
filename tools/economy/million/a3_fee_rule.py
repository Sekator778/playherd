"""Variant A, question 2: the herd fee at high leverage and in the 15-minute round.
Break-even accuracy a*(L) of a player on REAL 730-day windows (BTC, ETH, SOL; 3-minute and 15-minute rounds, non-overlapping), payoffs as in the engine
(m5_informed.payoff: equity = stake*(1 + side*L*r) capped at 20*stake, liquidation at l = kappa/cap with the remainder at the candle extreme).
Fee rules compared: R0 = today stake*min(1%, r_m*L) rounded up to 0.001 (+0.1 PLAY network, stake 1000 PLAY);
R1 = stake*min(20%, k_i*r_m*L) with k_3 = 1, k_15 = 2.2 (MarketEconomy.LONG_ROUND_STRESS_FACTOR), cap 20% = guard only; R2 = R1 with the new r_m (RNEW).
Glosten-Milgrom reading: the break-even accuracy of an uncapped, notional-proportional fee is a* = 1/2 + r/(2 E|r_round|) (gross edge per bet (2a-1) L E|r|, fee r L).
Usage: python -E -s a3_fee_rule.py > out_a3.txt
"""
import os
import numpy as np
import mcommon as mc
import m5_informed as m5


def windows(sym, k):
    """non-overlapping windows of k minutes: return r, lo, hi relative to the open"""
    h, l, c = m5.load_ohlc(sym)
    n = (len(c) - 1) // k
    base = np.arange(n) * k
    P0 = c[base]
    r = c[base + k] / P0 - 1
    hi = np.max([h[base + j] for j in range(1, k + 1)], axis=0) / P0 - 1
    lo = np.min([l[base + j] for j in range(1, k + 1)], axis=0) / P0 - 1
    return r, lo, hi


def fee_share(L, rm, k, rule, stake=1000.0):
    share = min(0.01, rm * L) if rule == "R0" else min(0.20, k * rm * L)
    herd = max(np.ceil(stake * share * 1000 - 1e-9) / 1000, 0.001)
    return (herd + mc.NET_FEE) / stake


def astar(r, lo, hi, L, cap, fee):
    e5, g, b = m5.ev_unit(0.5, r, lo, hi, L, cap, 0)
    return (fee - b) / (g - b), e5 - fee      # break-even accuracy, EV of an unskilled player (a = 0.5) incl. fee


# R2 = the proposed rule: r_m raised where 0.1*E|r3| (a* = 55% by the GM closed form) is above today's r_m; k_15 = 2.2; guard cap 20%.
RNEW = {"BTC": 0.92e-4, "ETH": 1.05e-4, "SOL": 1.25e-4}
CAPS = {"BTC": (1200, 500, 0.92e-4), "ETH": (1000, 400, 1.0e-4), "SOL": (600, 300, 1.0e-4)}   # MarketEconomy.java:45-47 (cap3, cap15, r_m)
if __name__ == "__main__":
    print("candles:", mc.candles_dir())
    ratio = {}
    for sym in CAPS:
        r3, _, _ = windows(sym, 3); r15, _, _ = windows(sym, 15)
        ratio[sym] = np.abs(r15).mean() / np.abs(r3).mean()
        print(f"{sym}: E|r3|={np.abs(r3).mean()*1e4:.2f}bp  E|r15|={np.abs(r15).mean()*1e4:.2f}bp  ratio E|r15|/E|r3| = {ratio[sym]:.3f}  (code LONG_ROUND_STRESS_FACTOR = 2.2)")
    for sym, (cap3, cap15, rm) in CAPS.items():
        for rnd, k, cap in (("3-min", 3, cap3), ("15-min", 15, cap15)):
            r, lo, hi = windows(sym, k)
            Er = np.abs(r).mean()
            print(f"\n== {sym} {rnd} round, cap {cap}, r_m {rm*1e4:.2f}bp, E|r|={Er*1e4:.2f}bp; GM closed form for fee=r_m*L (k=1): a* = {0.5 + rm/(2*Er):.4f}; with k=2.2 in the 15-min round: {0.5 + 2.2*rm/(2*Er):.4f}")
            print("   L | R0 fee% | R0 a*% | R0 EV(a=.5)% || R1 fee% | R1 a*% | R1 EV(a=.5)% | R1-R0 EV (pp) || R2 (new r_m) fee% | a*% | EV(a=.5)% | R2-R0 EV (pp)")
            for L in sorted({2, 10, 50, 100, 240, 600, cap}):
                if L > cap:
                    continue
                kk = 1.0 if k == 3 else 2.2
                f0 = fee_share(L, rm, 1.0, "R0"); f1 = fee_share(L, rm, kk, "R1")
                a0, e0 = astar(r, lo, hi, L, cap, f0); a1, e1 = astar(r, lo, hi, L, cap, f1)
                f2 = fee_share(L, RNEW[sym], kk, "R1"); a2, e2 = astar(r, lo, hi, L, cap, f2)
                print(f"  {L:5d} | {f0*100:6.3f} | {a0*100:6.2f} | {e0*100:+7.3f} || {f1*100:6.3f} | {a1*100:6.2f} | {e1*100:+7.3f} | {100*(e1-e0):+.3f} || {f2*100:6.3f} | {a2*100:6.2f} | {e2*100:+7.3f} | {100*(e2-e0):+.3f}")

    print("\n== closed form a* = 1/2 + r/(2 E|r|) (fee r*L, no liquidation/cap effects) for all 12 markets: today r_m (R0 below L_c = 1%/r_m) and the proposal ==")
    print("market | table sigma3 bp | E|r3| bp (real for BTC/ETH/SOL; others 0.714*sigma_table = SOL ratio, inference) | r_m today bp | r_m new bp | a* today (3-min, L<=L_c) | a* new 3-min | a* new 15-min (k=2.2) | L_c today")
    TAB = {"BTC": (9.2, .92, .92), "ETH": (13.4, 1.0, 1.05), "SOL": (17.3, 1.0, 1.25), "DOGE": (17.2, 1.0, 1.25), "XRP": (15.3, 1.0, 1.10), "HYPE": (15.7, 1.0, 1.15),
           "NVDA": (7.5, .75, .75), "TSLA": (7.0, .70, .70), "BRENTOIL": (8.3, .83, .83), "GOLD": (4.8, .48, .48), "XYZ100": (4.5, .45, .45), "SP500": (2.4, .24, .24)}
    REAL = {"BTC": 6.95, "ETH": 10.14, "SOL": 12.35}
    for m, (sg, r0, r1) in TAB.items():
        er = REAL.get(m, 0.714 * sg)
        print(f"{m:9s} | {sg:5.1f} | {er:6.2f}{'' if m in REAL else '*'} | {r0:.2f} | {r1:.2f} | {100*(0.5 + r0/(2*er)):.1f} | {100*(0.5 + r1/(2*er)):.1f} | {100*(0.5 + 2.2*r1/(2*2.2*er)):.1f} | {0.01/(r0*1e-4):.0f}")
    print("max fee share r_m*k*cap (guard-cap check, cap 20%):")
    CAP15 = {"BTC": 500, "ETH": 400, "SOL": 300, "DOGE": 300, "XRP": 300, "HYPE": 250, "NVDA": 800, "TSLA": 1000, "BRENTOIL": 500, "GOLD": 1200, "XYZ100": 1500, "SP500": 2000}
    for m, (sg, r0, r1) in TAB.items():
        print(f"  {m:9s} 3-min {100*r1*1e-4*mc.CAP3[m]:5.2f}%   15-min {100*2.2*r1*1e-4*CAP15[m]:5.2f}%")
