"""Informed crowd (Glosten-Milgrom 1985 spread logic; Shin 1991/92 bookmaker vs insiders; Kyle 1985 depth) on real 3-minute windows.
A player with accuracy a bets the side sign(r) with probability a (r = the realised 3-min return of the round), the rest a coin-flip against it.
Payout rules as in the engine: equity at close = stake*(1 + side*L*r), capped at 20*stake; on liquidation the remainder at the candle extreme (stage-A rule, LiquidationCalculator.java:129-137);
liquidation when the window extreme crosses l = kappa/cap (same threshold as tools/economy/scale/s4_pool_skew.py).
Fees: herd fee = stake*min(1%, r_m*L) rounded up to 0.001, network fee 0.1 PLAY (EconomyRules.java:83-91), stake 1000 PLAY.
Break-even accuracy a*(L): EV of a unit stake = 0 including fees.   Leak: what the treasury loses per round on gross stakes = gross edge, fees go to HERD or to TREASURY.
Usage: python -E -s m5_informed.py > out_m5.txt
"""
import os
import numpy as np
import mcommon as mc


def load_ohlc(sym):
    d = mc.candles_dir()
    rows = []
    with open(os.path.join(d, f"{sym}_1m.csv")) as f:
        for line in f:
            if line.startswith("#") or not line.strip():
                continue
            p = line.split(",")
            rows.append((int(p[0]), float(p[2]), float(p[3]), float(p[4])))
    a = np.array(rows)
    t0 = int(a[0, 0]); n = (int(a[-1, 0]) - t0) // 60000 + 1
    g = np.full((n, 3), np.nan)
    g[((a[:, 0] - t0) // 60000).astype(int)] = a[:, 1:]
    for j in range(3):
        idx = np.where(~np.isnan(g[:, j]), np.arange(n), 0); np.maximum.accumulate(idx, out=idx); g[:, j] = g[idx, j]
    return g[:, 0], g[:, 1], g[:, 2]   # high, low, close


def windows3(sym):
    h, l, c = load_ohlc(sym)
    n = (len(c) - 1) // 3
    base = np.arange(n) * 3
    P0 = c[base]
    r = c[base + 3] / P0 - 1
    hi = np.maximum.reduce([h[base + 1], h[base + 2], h[base + 3]]) / P0 - 1
    lo = np.minimum.reduce([l[base + 1], l[base + 2], l[base + 3]]) / P0 - 1
    return r, lo, hi


def payoff(side, r, lo, hi, L, cap):
    """Payout per unit stake for side=+1 (long) / -1 (short)."""
    l = 0.1 / cap
    xL = (l - 1 / L) / (1 - l); xS = (1 / L - l) / (1 + l)
    liq = np.where(side > 0, lo <= xL, hi >= xS)
    eq = 1.0 + side * L * r
    ext = np.where(side > 0, lo, hi)                      # candle extreme at which the liquidation triggered
    remainder = np.maximum(0.0, 1.0 + side * L * ext)     # stage-A rule: LiquidationCalculator.java:129-137 (payoutAtCandleExtreme); 0 on a gap through the margin
    return np.where(liq, remainder, np.clip(eq, 0.0, 20.0))


def fee_share(L, rmk, stake=1000.0):
    herd = np.ceil(stake * min(mc.FEE_CAP, rmk * L) * 1000 - 1e-9) / 1000
    return (max(herd, mc.UNIT) + mc.NET_FEE) / stake


def ev_unit(a, r, lo, hi, L, cap, rmk, stake=1000.0):
    sgn = np.where(r >= 0, 1.0, -1.0)
    good = payoff(sgn, r, lo, hi, L, cap) - 1.0       # bet with the move
    bad = payoff(-sgn, r, lo, hi, L, cap) - 1.0       # bet against it
    return a * good.mean() + (1 - a) * bad.mean(), good.mean(), bad.mean()


if __name__ == "__main__":
    print("candles:", mc.candles_dir())
    print("\n== break-even accuracy a*(L): EV per unit stake of a player with accuracy a equals fees (stake 1000 PLAY), by market and leverage ==")
    for sym, cap, rmk in (("BTC", 1200, 0.92e-4), ("ETH", 1000, 1e-4), ("SOL", 600, 1e-4)):
        r, lo, hi = windows3(sym)
        print(f"-- {sym}: windows {len(r)}, E|r|={np.abs(r).mean()*1e4:.2f}bp, sigma={r.std()*1e4:.2f}bp (table sigma {mc.SIGMA_BP[sym]}bp, r_m={rmk*1e4:.2f}bp)")
        print("   L | fee % of stake | EV(a=0.5) %  | a* (fees to HERD or treasury: player pays them either way) | a* ignoring fees (gross edge only: the treasury's break-even if fees leave to HERD)")
        for L in (2, 10, 50, 100, 240, cap // 10, cap // 2, cap):
            if L < 2 or L > cap:
                continue
            fee = fee_share(L, rmk)
            e5, g, b = ev_unit(0.5, r, lo, hi, L, cap, rmk)
            # EV(a) = a*g + (1-a)*b - fee = 0
            astar = (fee - b) / (g - b)
            astar0 = (0 - b) / (g - b)
            print(f"   {L} | {fee*100:.3f} | {e5*100 - fee*100:+.3f} | {astar*100:.2f} | {astar0*100:.2f}")
    print("\nReading: a* above 50% = the fee protects the treasury; for L above the point where the herd fee reaches the 1% cap a* falls towards 50%+.")

    print("\n== leak per day of a skilled share, treasury view (BTC, L=10, 1000 PLAY stakes, 10 bets/day each) ==")
    r, lo, hi = windows3("BTC")
    for a in (0.52, 0.56, 0.60, 0.65):
        e, g, b = ev_unit(a, r, lo, hi, 10, 1200, 0.92e-4)
        fee = fee_share(10, 0.92e-4)
        print(f"a={a}: gross edge {e*100:+.4f}% of stake (treasury loses this per bet), fee {fee*100:.4f}% of stake, net for the player {100*(e-fee):+.4f}%")
    print("If fees go to HERD the treasury bears the gross edge of every player with a>0.5 (and also of fee-losing players with a in (0.5, a*): they lose net but still take money out of T).")

    print("\n== treasury-level leak per day with the exposure AT the limit (lambda=1), informed independent crowd (S3), 12 markets (real + proxy returns) ==")
    R3, R15, info = mc.build_rows()
    e3 = np.abs(R3).mean((0, 1)); e15 = np.abs(R15).mean(0)
    per_day_unit = 480 * float((mc.W3 * e3).sum()) + 96 * float((mc.W15 * e15).sum())
    prem_day = 480 * mc.PREM3 + 96 * mc.PREM15
    print(f"E|r|-weighted exposure per day = {per_day_unit*100:.3f}% T per unit (2a-1); fee premium per day at b=1: {prem_day*100:.3f}% T (fees to TREASURY) or 0 (fees to HERD)")
    for a in (0.52, 0.56):
        leak = (2 * a - 1) * per_day_unit
        print(f"a={a}: leak {leak*100:.3f}% T/day at lambda=1 (gross, before fees); fee/leak = {prem_day/leak:.2f} at b=1 -> fees cover it iff b <= {prem_day/leak:.2f}")
