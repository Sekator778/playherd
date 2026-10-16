#!/usr/bin/env python3
"""Download 1-minute candles for the bots backtest into .local/bots-candles/ (git-ignored).

    python3 -I bots/scripts/download_candles.py [--days 14] [--out .local/bots-candles]

Python 3 standard library only. Public, key-less endpoints:
  * Coinbase Exchange   GET  https://api.exchange.coinbase.com/products/<SYM>-USD/candles?granularity=60   (BTC, ETH, SOL)
  * Hyperliquid info    POST https://api.hyperliquid.xyz/info  {"type":"candleSnapshot", ...}              (xyz:NVDA, xyz:GOLD)
The approach (300 candles per Coinbase call, flat candles for minutes without trades, Hyperliquid paging that moves its
start forward until the API answers) is that of tools/economy/recompute_caps.py.

Output: <out>/<MARKET>_1m.csv, one line per minute `open_time_ms,open,high,low,close`, first line a `# source=...` note.
An existing file is merged (new lines win), so repeated runs extend the history: Hyperliquid serves only its most recent
~5000 one-minute candles (about 3.5 days) per request, so a longer history there builds up over several days of runs.
"""
import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.request

MINUTE = 60_000
DAY = 86_400_000
UA = {"user-agent": "herd-bots-candles/1", "content-type": "application/json"}
CB_URL = "https://api.exchange.coinbase.com/products/{prod}/candles?granularity=60&start={s}&end={e}"
HL_URL = "https://api.hyperliquid.xyz/info"

COINBASE = {"BTC": "BTC-USD", "ETH": "ETH-USD", "SOL": "SOL-USD"}
HYPERLIQUID = {"NVDA": "xyz:NVDA", "GOLD": "xyz:GOLD"}


def get(url, body=None, retries=5):
    last = None
    for i in range(retries):
        try:
            req = urllib.request.Request(url, data=None if body is None else json.dumps(body).encode(), headers=UA)
            with urllib.request.urlopen(req, timeout=30) as r:
                return json.load(r)
        except urllib.error.HTTPError as e:
            last = e
            if e.code == 404:
                raise
            time.sleep(1.5 * (i + 1))
        except Exception as e:  # network hiccup
            last = e
            time.sleep(1.5 * (i + 1))
    raise RuntimeError("request failed: %s: %s" % (url, last))


def fill_flat_minutes(rows):
    """Coinbase omits minutes without trades: insert a flat candle at the previous close (the price did not move)."""
    if not rows:
        return []
    by = {r[0]: r for r in rows}
    t0, t1 = min(by), max(by)
    prev = None
    out = []
    for t in range(t0, t1 + MINUTE, MINUTE):
        r = by.get(t)
        if r is not None:
            prev = r[4]
            out.append(r)
        elif prev is not None:
            out.append([t, prev, prev, prev, prev])
    return out


def fetch_coinbase(product, start_ms, end_ms, pause=0.35):
    rows = {}
    cur = start_ms // MINUTE * MINUTE
    end_ms = end_ms // MINUTE * MINUTE
    fmt = lambda ms: time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(ms / 1000))
    while cur < end_ms:
        e = min(cur + 300 * MINUTE, end_ms)
        for t, lo, hi, op, cl, _v in get(CB_URL.format(prod=product, s=fmt(cur), e=fmt(e))):
            if t * 1000 < end_ms:  # drop the minute still forming
                rows[t * 1000] = [t * 1000, float(op), float(hi), float(lo), float(cl)]
        cur = e
        time.sleep(pause)
    return [rows[k] for k in sorted(rows)]


def fetch_hyperliquid(coin, start_ms, end_ms, now_ms):
    """1-minute candles of one coin; a full page continues from its last candle."""
    rows = {}
    cur = start_ms
    shifts = 0
    while cur < end_ms:
        d = get(HL_URL, {"type": "candleSnapshot", "req": {"coin": coin, "interval": "1m", "startTime": int(cur), "endTime": int(end_ms)}})
        if not d:
            if rows or shifts > 12:
                break
            cur += max((end_ms - cur) // 3, 3_600_000)
            shifts += 1
            continue
        for c in d:
            if c["T"] <= now_ms:  # finished candles only
                rows[c["t"]] = [c["t"], float(c["o"]), float(c["h"]), float(c["l"]), float(c["c"])]
        last = max(c["t"] for c in d)
        if len(d) < 4900:
            break
        cur = last + 1
        time.sleep(0.3)
    return [rows[k] for k in sorted(rows)]


def merge_and_save(path, source, rows):
    old = {}
    if os.path.exists(path):
        with open(path) as f:
            for line in f:
                if line.startswith("#") or not line.strip():
                    continue
                p = line.strip().split(",")
                old[int(p[0])] = [int(p[0])] + [float(x) for x in p[1:5]]
    for r in rows:
        old[r[0]] = r
    merged = fill_flat_minutes([old[k] for k in sorted(old)])
    tmp = path + ".tmp"
    with open(tmp, "w") as f:
        f.write("# source=%s fetched_at_ms=%d\n" % (source, int(time.time() * 1000)))
        for r in merged:
            f.write("%d,%r,%r,%r,%r\n" % (r[0], r[1], r[2], r[3], r[4]))
    os.replace(tmp, path)
    return merged


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--days", type=float, default=14, help="history to ask for (Hyperliquid may serve less)")
    ap.add_argument("--out", default=".local/bots-candles")
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)
    now_ms = int(time.time() * 1000)
    start_ms = now_ms - int(a.days * DAY)
    for market, product in COINBASE.items():
        rows = merge_and_save(os.path.join(a.out, market + "_1m.csv"), "Coinbase Exchange " + product,
                              fetch_coinbase(product, start_ms, now_ms))
        print("Coinbase %s: %d candles %s .. %s" % (market, len(rows), rows[0][0], rows[-1][0]), file=sys.stderr)
    for market, coin in HYPERLIQUID.items():
        rows = merge_and_save(os.path.join(a.out, market + "_1m.csv"), "Hyperliquid " + coin,
                              fetch_hyperliquid(coin, start_ms, now_ms, now_ms))
        print("Hyperliquid %s: %d candles %s .. %s (%.2f days)" % (
            market, len(rows), rows[0][0], rows[-1][0], (rows[-1][0] - rows[0][0]) / DAY), file=sys.stderr)


if __name__ == "__main__":
    main()
