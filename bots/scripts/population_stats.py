#!/usr/bin/env python3
"""Summary of a PopulationMain run: treasury flows per day and bot lifetimes, as markdown.

    python3 -I bots/scripts/population_stats.py <prefix> [--skip-days 1]

Reads <prefix>_days.csv and <prefix>_lives.csv (written by crowdvsmarket.bots.backtest.PopulationMain). Python 3 standard
library only. A "day-sample" is one (replicate, day) pair; the replicates share the price path (they differ in the seeded
decisions and the shift of the round grid), so day-samples of the same calendar day are correlated: quantiles below
describe decisions-and-days jointly, not independent days.
"""
import argparse
import csv
import math
import statistics
from collections import defaultdict

DAY_MS = 86_400_000


def quantile(xs, q):
    xs = sorted(xs)
    if not xs:
        return float("nan")
    k = (len(xs) - 1) * q
    lo, hi = int(math.floor(k)), int(math.ceil(k))
    return xs[lo] + (xs[hi] - xs[lo]) * (k - lo)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("prefix")
    ap.add_argument("--skip-days", type=int, default=1, help="leading days left out of the day statistics (start-up transient)")
    args = ap.parse_args()
    days = list(csv.DictReader(open(args.prefix + "_days.csv")))
    lives = list(csv.DictReader(open(args.prefix + "_lives.csv")))
    reps = sorted({int(r["rep"]) for r in days})
    last_day = defaultdict(int)
    for r in days:
        last_day[int(r["rep"])] = max(last_day[int(r["rep"])], int(r["day"]))
    # drop the last (partial) day too
    use = [r for r in days if args.skip_days <= int(r["day"]) < last_day[int(r["rep"])]]
    f = lambda r, k: float(r[k])
    cols = ["bets", "stakes", "fees", "payouts", "packs", "remainders", "retirements", "liquidations"]
    mean = {c: statistics.mean(f(r, c) for r in use) for c in cols}
    trading = [f(r, "stakes") + f(r, "fees") - f(r, "payouts") for r in use]
    change = [t - f(r, "packs") + f(r, "remainders") for t, r in zip(trading, use)]
    print("Day-samples: %d (%d replicates, days %d..last-1)\n" % (len(use), len(reps), args.skip_days))
    print("| per day, whole population | PLAY or count |")
    print("|---|---|")
    print("| bets | %.0f |" % mean["bets"])
    print("| stakes wagered | %.0f |" % mean["stakes"])
    print("| fees paid (swept to the treasury daily) | %.0f |" % mean["fees"])
    print("| payouts, treasury -> bots | %.0f |" % mean["payouts"])
    print("| stakes + fees, bots -> treasury | %.0f |" % (mean["stakes"] + mean["fees"]))
    print("| trading gain of the treasury (stakes + fees - payouts) | %.0f |" % statistics.mean(trading))
    print("| starter packs, treasury -> bots | %.0f |" % mean["packs"])
    print("| remainders of retired bots, bots -> treasury | %.1f |" % mean["remainders"])
    print("| **treasury change** (trading gain - packs + remainders) | **%.0f** |" % statistics.mean(change))
    print("| retirements | %.2f |" % mean["retirements"])
    print("| liquidated bets | %.0f (%.1f%% of bets) |" % (mean["liquidations"], 100 * mean["liquidations"] / mean["bets"]))
    se = statistics.pstdev(change) / math.sqrt(len(change))
    print("\nTreasury change per day-sample: mean %.0f, sd %.0f, naive s.e. of the mean %.0f" % (statistics.mean(change), statistics.pstdev(change), se))
    for name, xs in (("trading gain", trading), ("treasury change", change)):
        print("%s quantiles: min %.0f | 0.1%% %.0f | 1%% %.0f | 5%% %.0f | median %.0f | 95%% %.0f | 99%% %.0f | 99.9%% %.0f | max %.0f" % (
            name, min(xs), quantile(xs, 0.001), quantile(xs, 0.01), quantile(xs, 0.05), quantile(xs, 0.5), quantile(xs, 0.95),
            quantile(xs, 0.99), quantile(xs, 0.999), max(xs)))
    # by half of the run: is the first half's start-up transient visible?
    for lo, hi, name in ((args.skip_days, 28, "days 1..27"), (28, 10_000, "days 28..end")):
        sub = [t - f(r, "packs") + f(r, "remainders") for t, r in zip(trading, use) if lo <= int(r["day"]) < hi]
        if sub:
            print("treasury change, %s: mean %.0f over %d day-samples" % (name, statistics.mean(sub), len(sub)))
    # lives
    t0 = {}
    for l in lives:
        t0[int(l["rep"])] = min(t0.get(int(l["rep"]), 1 << 62), int(l["startMs"]))
    end_run = {r: t0[r] + (last_day[r] + 1) * DAY_MS for r in reps if r in t0}
    by = defaultdict(lambda: dict(n=0, ended=0, exposure=0.0, bets=0, net=0.0, hours=[]))
    for l in lives:
        r = int(l["rep"])
        d = by[l["catalogId"]]
        s, e = int(l["startMs"]), int(l["endMs"])
        d["n"] += 1
        d["bets"] += int(l["bets"])
        d["net"] += float(l["net"])
        if e >= 0:
            d["ended"] += 1
            d["hours"].append((e - s) / 3_600_000)
            d["exposure"] += (e - s) / DAY_MS
        else:
            d["exposure"] += (end_run[r] - s) / DAY_MS
    print("\n| bot (entry) | lives | ended | bot-days alive | retirements per bot-day | mean life, h (exposure / retirements) | median life of ended, h | bets per bot-day | net PLAY per bot-day (bot view) |")
    print("|---|---|---|---|---|---|---|---|---|")
    for k in sorted(by):
        d = by[k]
        rate = d["ended"] / d["exposure"] if d["exposure"] else float("nan")
        life = 24 / rate if rate > 0 else float("inf")
        med = statistics.median(d["hours"]) if d["hours"] else float("nan")
        print("| %s | %d | %d | %.0f | %.3f | %s | %.1f | %.0f | %.1f |" % (
            k, d["n"], d["ended"], d["exposure"], rate, ("%.0f" % life) if life != float("inf") else "inf", med,
            d["bets"] / d["exposure"], d["net"] / d["exposure"]))


if __name__ == "__main__":
    main()
