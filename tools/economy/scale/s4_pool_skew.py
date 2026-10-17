import numpy as np
from common import *
LAM, K = 0.1, 100
def windows(sym):
    rows = load(sym); t0, t1 = rows[0][0], rows[-1][0]
    g = grid(rows, t0, t1); o, h, l, c = g.T
    n = len(c) - 3
    P0 = c[:n]; r = c[3:] / P0 - 1
    lo = np.minimum.reduce([l[1:n+1], l[2:n+2], l[3:n+3]]) / P0 - 1
    hi = np.maximum.reduce([h[1:n+1], h[2:n+2], h[3:n+3]]) / P0 - 1
    ok = ~(np.isnan(r) | np.isnan(lo) | np.isnan(hi))
    return r[ok], lo[ok], hi[ok]
def payoffs(r, lo, hi, L, cap):
    l = KAPPA / cap
    xL = (l - 1 / L) / (1 - l); xS = (1 / L - l) / (1 + l)
    XL = np.where(lo <= xL, l * L, np.clip(1 + L * r, 0, 20))
    XS = np.where(hi >= xS, l * L, np.clip(1 - L * r, 0, 20))
    return XL, XS, (lo <= xL).mean(), (hi >= xS).mean()
for sym, cap in [("BTC", 1200), ("SOL", 600)]:
    r, lo, hi = windows(sym)
    print(f"==== {sym} cap {cap}, windows {len(r)}, E|r|={np.abs(r).mean()*1e4:.2f}bp")
    for L in [10, 100, cap // 5]:
        XL, XS, pl, ps = payoffs(r, lo, hi, L, cap)
        print(f"-- L={L}: P(liq long)={pl:.4f}, P(liq short)={ps:.4f}; today's EV long {np.mean(XL-1)*100:+.3f}%, short {np.mean(XS-1)*100:+.3f}% of stake; half-width 1/2*E|r|*L = {0.5*np.abs(r).mean()*L*100:.3f}%")
        for b in [0.05, 0.2, 0.6]:
            pU, pD = (1 + b) / 2, (1 - b) / 2
            eta = LAM * (1 + b) / (2 * K)
            B = eta * L * np.abs(r)
            funds = pU * np.maximum(1 - XL, 0) + pD * np.maximum(1 - XS, 0)
            claims = pU * np.maximum(XL - 1, 0) + pD * np.maximum(XS - 1, 0)
            gainL, gainS = np.maximum(XL - 1, 0), np.maximum(XS - 1, 0)
            lossL, lossS = np.maximum(1 - XL, 0), np.maximum(1 - XS, 0)
            with np.errstate(divide="ignore", invalid="ignore"):
                h_d = np.where(claims > 0, funds / claims, 1.0)
                h_band = np.where(claims > 0, (funds - np.clip(funds - claims, -B, B)) / claims, 1.0)
                h_e = np.where(claims > 0, np.minimum(1.0, (funds + B) / claims), 1.0)
            res = {}
            for name, h in [("pool(d)", h_d), ("band(e')", h_band), ("one-sided(e)", h_e)]:
                evU = np.mean(-lossL + gainL * h); evD = np.mean(-lossS + gainS * h)
                hp = pU * (gainL * h - lossL) + pD * (gainS * h - lossS)   # players' total (stake units)
                hp0 = pU * (gainL - lossL) + pD * (gainS - lossS)
                house = hp0 - hp   # change in house P&L vs today's rule: positive = house gains
                res[name] = (evU - np.mean(-lossL + gainL), evD - np.mean(-lossS + gainS), np.mean(h[claims > 0] < 0.999), np.mean(h[claims > 0]), np.mean(funds - claims + house), (funds - claims + house).std(), (funds - claims + house).min())
            house_today = funds - claims
            print(f"   b={b}: today house P&L mean {house_today.mean()*100:+.4f}% sd {house_today.std()*100:.3f}% worst {house_today.min()*100:.2f}% of total stake")
            for name, v in res.items():
                print(f"      {name}: dEV heavy(UP) {v[0]*100:+.4f}%, dEV light {v[1]*100:+.4f}% of stake; P(haircut)={v[2]:.3f}, mean h={v[3]:.3f}; crowd-avg cost {-(pU*v[0]+pD*v[1])*100:+.4f}%; house mean {v[4]*100:+.4f}%; house P&L sd {v[5]*100:.4f}% worst {v[6]*100:.3f}%")
