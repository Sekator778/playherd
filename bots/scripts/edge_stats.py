#!/usr/bin/env python3
"""Edge statistics for the bots backtest: reads the bet trace of BacktestMain (--trace) and prints markdown.

    python3 -I bots/scripts/edge_stats.py trace.csv [--stake 100]

Python 3 standard library only. Every number is computed from the trace; the trace holds, per bet, the direction (+1/-1)
and the window's move in basis points. The leverage-free quantity "signed move" x = direction * move_bp is the gross
result of a bet in bp of notional; PnL = stake * L * x / 1e4 - fees (liquidation aside), so the same x serves any leverage.

Sections:
  1. cells (variant x market x horizon): n, hit rate against the null "random direction with the same share of UP", mean x
     with its t statistic; raw p-values, Holm and Benjamini-Hochberg adjustments over ALL cells.
  2. the three cryptos pooled per window (one observation per window, so the BTC/ETH/SOL co-movement cannot inflate n).
  3. correlations between BTC, ETH and SOL: raw window moves and the signed moves of each variant.
  4. out-of-sample: first half of the period vs the second half, by variant (pooled cryptos), rank correlation of the two
     halves and what the first half's top variants did in the second half.
  5. hit rate vs P&L: average win, average loss, payoff ratio, fee hurdle.
"""
import argparse
import csv
import math
import statistics
from collections import defaultdict


def phi_two_sided(z):
    return math.erfc(abs(z) / math.sqrt(2))


def load(path):
    rows = []
    with open(path) as f:
        for r in csv.DictReader(f):
            rows.append((r["catalogId"], r["market"], int(r["horizon"]), int(r["startMs"]), int(r["direction"]),
                         float(r["moveBp"]), int(r["leverage"]), int(r["liquidated"]), float(r["net"]), float(r["fees"])))
    return rows


def mean_t(xs):
    n = len(xs)
    if n < 3:
        return float("nan"), float("nan"), n
    m = sum(xs) / n
    sd = statistics.pstdev(xs) * math.sqrt(n / (n - 1))
    t = m / (sd / math.sqrt(n)) if sd > 0 else float("nan")
    return m, t, n


def hit_stats(bets):
    """hit rate on decided bets and z against the null 'direction independent of the move, same share of UP'."""
    dec = [(d, mv) for d, mv in bets if mv != 0]
    n = len(dec)
    if n == 0:
        return float("nan"), float("nan"), 0
    hits = sum(1 for d, mv in dec if (d > 0) == (mv > 0))
    ups_move = sum(1 for d, mv in dec if mv > 0) / n
    ups_bet = sum(1 for d, mv in dec if d > 0) / n
    p0 = ups_bet * ups_move + (1 - ups_bet) * (1 - ups_move)
    se = math.sqrt(p0 * (1 - p0) / n)
    return hits / n, (hits / n - p0) / se, n


def holm(ps):
    m = len(ps)
    order = sorted(range(m), key=lambda i: ps[i])
    adj = [0.0] * m
    run = 0.0
    for rank, i in enumerate(order):
        run = max(run, min(1.0, (m - rank) * ps[i]))
        adj[i] = run
    return adj


def bh(ps):
    m = len(ps)
    order = sorted(range(m), key=lambda i: ps[i], reverse=True)
    adj = [0.0] * m
    prev = 1.0
    for k, i in enumerate(order):
        rank = m - k
        prev = min(prev, ps[i] * m / rank)
        adj[i] = prev
    return adj


def pearson(a, b):
    n = len(a)
    if n < 3:
        return float("nan")
    ma, mb = sum(a) / n, sum(b) / n
    sa = math.sqrt(sum((x - ma) ** 2 for x in a))
    sb = math.sqrt(sum((y - mb) ** 2 for y in b))
    if sa == 0 or sb == 0:
        return float("nan")
    return sum((x - ma) * (y - mb) for x, y in zip(a, b)) / (sa * sb)


def ranks(xs):
    order = sorted(range(len(xs)), key=lambda i: xs[i])
    r = [0.0] * len(xs)
    for k, i in enumerate(order):
        r[i] = k + 1.0
    return r


def spearman(a, b):
    return pearson(ranks(a), ranks(b))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("trace")
    ap.add_argument("--stake", type=float, default=100.0)
    args = ap.parse_args()
    rows = load(args.trace)
    cryptos = ("BTC", "ETH", "SOL")
    variants = sorted({r[0] for r in rows})
    horizons = sorted({r[2] for r in rows})
    markets = sorted({r[1] for r in rows})
    cell = defaultdict(list)  # (variant, market, horizon) -> [(startMs, dir, moveBp)]
    for v, m, h, t, d, mv, lev, liq, net, fee in rows:
        cell[(v, m, h)].append((t, d, mv))
    lev = rows[0][6]
    fee_mean = statistics.mean(r[9] for r in rows)
    hurdle_bp = fee_mean / (args.stake * lev) * 1e4
    print("Trace: %d bets, leverage x%d, mean fees %.3f PLAY per bet = fee hurdle %.2f bp of notional (break-even mean signed move)\n"
          % (len(rows), lev, fee_mean, hurdle_bp))

    # 1. cells
    cells = []
    for h in horizons:
        for v in variants:
            for m in markets:
                bets = cell.get((v, m, h), [])
                if len(bets) < 30:
                    continue
                hr, zh, n = hit_stats([(d, mv) for _, d, mv in bets])
                mx, tx, _ = mean_t([d * mv for _, d, mv in bets])
                cells.append(dict(v=v, m=m, h=h, n=n, hr=hr, zh=zh, ph=phi_two_sided(zh), mx=mx, tx=tx, px=phi_two_sided(tx)))
    for key in ("ph", "px"):
        ps = [c[key] for c in cells]
        for c, a, b in zip(cells, holm(ps), bh(ps)):
            c[key + "_holm"] = a
            c[key + "_bh"] = b
    print("## 1. Cells: %d (variant x market x horizon, n >= 30)\n" % len(cells))
    for key, name in (("ph", "hit rate vs null"), ("px", "mean signed move (t test)")):
        raw = sum(1 for c in cells if c[key] < 0.05)
        print("- %s: raw p < 0.05 in %d of %d cells (chance alone: %.1f); Holm < 0.05: %d; BH q < 0.05: %d"
              % (name, raw, len(cells), 0.05 * len(cells), sum(1 for c in cells if c[key + "_holm"] < 0.05),
                 sum(1 for c in cells if c[key + "_bh"] < 0.05)))
    print("\nTen smallest raw p of the mean-signed-move test:\n")
    print("| variant | market | h | n | hit | z(hit) | mean x bp | t | p raw | p Holm | q BH |")
    print("|---|---|---|---|---|---|---|---|---|---|---|")
    for c in sorted(cells, key=lambda c: c["px"])[:10]:
        print("| %s | %s | %d | %d | %.1f%% | %.2f | %.2f | %.2f | %.4f | %.3f | %.3f |" % (
            c["v"], c["m"], c["h"], c["n"], c["hr"] * 100, c["zh"], c["mx"], c["tx"], c["px"], c["px_holm"], c["px_bh"]))
    print("\nTen smallest raw p of the hit-rate test:\n")
    print("| variant | market | h | n | hit | z(hit) | p raw | p Holm | q BH |")
    print("|---|---|---|---|---|---|---|---|---|")
    for c in sorted(cells, key=lambda c: c["ph"])[:10]:
        print("| %s | %s | %d | %d | %.1f%% | %.2f | %.4f | %.3f | %.3f |" % (
            c["v"], c["m"], c["h"], c["n"], c["hr"] * 100, c["zh"], c["ph"], c["ph_holm"], c["ph_bh"]))
    coin = [c for c in cells if c["v"].startswith("coin")]
    print("\nNull calibration, the two coin variants (random direction): %d cells, z(hit) mean %.2f sd %.2f, "
          "raw p<0.05 in %d, t(mean x) mean %.2f sd %.2f\n" % (
              len(coin), statistics.mean(c["zh"] for c in coin), statistics.pstdev(c["zh"] for c in coin),
              sum(1 for c in coin if c["ph"] < 0.05), statistics.mean(c["tx"] for c in coin),
              statistics.pstdev(c["tx"] for c in coin)))

    # 2. pooled cryptos per window
    print("## 2. BTC+ETH+SOL pooled per window (one observation per window: mean of the available signed moves)\n")
    print("| variant | h | windows | hit (pooled bets) | z(hit), bets as independent | z(hit), per window | mean x bp | t | p | p Holm (over this table) | net per bet after hurdle, bp |")
    print("|---|---|---|---|---|---|---|---|---|---|---|")
    pooled = []
    for h in horizons:
        for v in variants:
            by_t = defaultdict(list)
            allbets = []
            for m in cryptos:
                for t, d, mv in cell.get((v, m, h), []):
                    by_t[t].append(d * mv)
                    allbets.append((d, mv))
            if len(by_t) < 30:
                continue
            xs = [sum(l) / len(l) for l in by_t.values()]
            mx, tx, n = mean_t(xs)
            hr, zh, nb = hit_stats(allbets)
            # window-level hit test: per window the share of decided bets that hit, against the same null as the pooled z
            hw = defaultdict(list)
            for m in cryptos:
                for t, d, x in cell.get((v, m, h), []):
                    if x != 0:
                        hw[t].append(1.0 if (d > 0) == (x > 0) else 0.0)
            hs = [sum(l) / len(l) for l in hw.values()]
            null = (hr - zh * math.sqrt(hr * (1 - hr) / nb)) if nb else 0.5
            sdw = statistics.pstdev(hs) * math.sqrt(len(hs) / (len(hs) - 1))
            zw = (statistics.mean(hs) - null) / (sdw / math.sqrt(len(hs))) if sdw > 0 else float("nan")
            pooled.append(dict(v=v, h=h, n=n, hr=hr, zh=zh, zw=zw, mx=mx, tx=tx, p=phi_two_sided(tx)))
    for c, a in zip(pooled, holm([c["p"] for c in pooled])):
        c["p_holm"] = a
    for c in pooled:
        print("| %s | %d | %d | %.1f%% | %.2f | %.2f | %.2f | %.2f | %.4f | %.3f | %.2f |" % (
            c["v"], c["h"], c["n"], c["hr"] * 100, c["zh"], c["zw"], c["mx"], c["tx"], c["p"], c["p_holm"], c["mx"] - hurdle_bp))
    print()

    # 3. correlations
    print("## 3. Correlation between BTC, ETH and SOL\n")
    for h in horizons:
        mv = {}
        for v in variants:
            for m in cryptos:
                for t, d, x in cell.get((v, m, h), []):
                    mv.setdefault(m, {})[t] = x
        # raw window moves: any variant that bets every window (coin) carries the move; use the move column directly
        print("Horizon %d min, raw window moves (bp):" % h)
        for i, a in enumerate(cryptos):
            for b in cryptos[i + 1:]:
                ts = sorted(set(mv.get(a, {})) & set(mv.get(b, {})))
                print("- %s-%s: r = %.3f over %d windows" % (a, b, pearson([mv[a][t] for t in ts], [mv[b][t] for t in ts]), len(ts)))
        sig = []
        for v in variants:
            if v.startswith("coin"):
                continue
            for i, a in enumerate(cryptos):
                for b in cryptos[i + 1:]:
                    da = {t: d * x for t, d, x in cell.get((v, a, h), [])}
                    db = {t: d * x for t, d, x in cell.get((v, b, h), [])}
                    ts = sorted(set(da) & set(db))
                    if len(ts) >= 100:
                        sig.append(pearson([da[t] for t in ts], [db[t] for t in ts]))
        sig = [s for s in sig if not math.isnan(s)]
        if sig:
            rbar = statistics.mean(sig)
            print("- signed moves of the non-coin variants, pairwise across cryptos: mean r = %.3f (min %.3f, max %.3f, %d pairs); "
                  "design effect for 3 markets = 1 + 2r = %.2f, effective n = n_total / that\n" % (
                      rbar, min(sig), max(sig), len(sig), 1 + 2 * rbar))
    # 4. out of sample
    print("## 4. Out of sample: first half vs second half of the period (pooled cryptos, per-window mean)\n")
    for h in horizons:
        ts_all = sorted({t for v in variants for m in cryptos for t, _, _ in cell.get((v, m, h), [])})
        cut = ts_all[len(ts_all) // 2]
        res = []
        for v in variants:
            halves = []
            for lo in (True, False):
                by_t = defaultdict(list)
                for m in cryptos:
                    for t, d, x in cell.get((v, m, h), []):
                        if (t < cut) == lo:
                            by_t[t].append(d * x)
                xs = [sum(l) / len(l) for l in by_t.values()]
                halves.append(mean_t(xs) if len(xs) >= 30 else (float("nan"), float("nan"), len(xs)))
            res.append((v, halves[0], halves[1]))
        print("Horizon %d min, cut at %s UTC\n" % (h, __import__("datetime").datetime.fromtimestamp(cut / 1000, __import__("datetime").timezone.utc).strftime("%Y-%m-%d %H:%M")))
        print("| variant | n1 | mean x bp (1st) | t1 | n2 | mean x bp (2nd) | t2 |")
        print("|---|---|---|---|---|---|---|")
        for v, a, b in res:
            print("| %s | %d | %.2f | %.2f | %d | %.2f | %.2f |" % (v, a[2], a[0], a[1], b[2], b[0], b[1]))
        ok = [(a[1], b[1], a[0], b[0]) for v, a, b in res if not math.isnan(a[1]) and not math.isnan(b[1]) and not v.startswith("coin")]
        if len(ok) > 3:
            print("\nSpearman correlation of t1 and t2 across %d non-coin variants: %.2f; same for mean x: %.2f" % (
                len(ok), spearman([o[0] for o in ok], [o[1] for o in ok]), spearman([o[2] for o in ok], [o[3] for o in ok])))
            top = sorted(ok, key=lambda o: -o[0])[:5]
            print("Top-5 by first half: mean t1 = %.2f, their t2 = %s, mean x2 = %.2f bp (hurdle %.2f bp)\n" % (
                statistics.mean(o[0] for o in top), ", ".join("%.2f" % o[1] for o in top), statistics.mean(o[3] for o in top), hurdle_bp))
        else:
            print()
    # hyperliquid halves
    print("Hyperliquid markets (3.5 days), per variant: first half vs second half, all bets pooled over NVDA+GOLD, horizon 3 min\n")
    print("| variant | n1 | mean x1 | t1 | n2 | mean x2 | t2 |")
    print("|---|---|---|---|---|---|---|")
    for v in variants:
        bets = [(t, d * x) for m in ("NVDA", "GOLD") for t, d, x in cell.get((v, m, 3), [])]
        if len(bets) < 60:
            continue
        cut = sorted(t for t, _ in bets)[len(bets) // 2]
        a = mean_t([x for t, x in bets if t < cut])
        b = mean_t([x for t, x in bets if t >= cut])
        print("| %s | %d | %.2f | %.2f | %d | %.2f | %.2f |" % (v, a[2], a[0], a[1], b[2], b[0], b[1]))
    print()

    # 5. hit vs pnl
    print("## 5. Hit rate vs payoff (pooled cryptos, signed move x in bp; PnL gross = stake*L*x/1e4)\n")
    netd = defaultdict(list)
    for r in rows:
        if r[1] in cryptos:
            netd[(r[0], r[2])].append(r[8])
    print("| variant | h | n | hit (all bets, ties as miss) | mean win bp | mean loss bp | win/loss ratio | break-even hit for that ratio | mean x bp | median x bp | mean abs x bp | net PLAY per bet (trace) |")
    print("|---|---|---|---|---|---|---|---|---|---|---|---|")
    for h in horizons:
        for v in variants:
            xs = [d * x for m in cryptos for _, d, x in cell.get((v, m, h), [])]
            if len(xs) < 100:
                continue
            w = [x for x in xs if x > 0]
            l = [-x for x in xs if x < 0]
            if not w or not l:
                continue
            ratio = statistics.mean(w) / statistics.mean(l)
            be = 1 / (1 + ratio)
            print("| %s | %d | %d | %.1f%% | %.2f | %.2f | %.3f | %.1f%% | %.2f | %.2f | %.2f | %.3f |" % (
                v, h, len(xs), 100 * len(w) / len(xs), statistics.mean(w), statistics.mean(l), ratio, 100 * be,
                statistics.mean(xs), statistics.median(xs), statistics.mean(abs(x) for x in xs), statistics.mean(netd[(v, h)])))


if __name__ == "__main__":
    main()
