"""Variant A, question 3: how deep and how fast are the real Polymarket markets behind Herd's odds markets?
Read-only GET requests to the public Polymarket APIs (Gamma /markets/keyset for the catalogue, CLOB /prices-history for 1-minute prices over the last day).
Outputs: the distribution of Gamma `liquidityNum` (USD) of the top-by-volume markets, and the distribution of the 3-minute relative price move |dp|/p
(the engine's return of an odds market is the price ratio) pooled and per liquidity bucket. Raw responses go to .local/a1-cache/.
Usage: python -E -s a1_odds_depth.py > out_a1.txt
"""
import json, os, time, urllib.request
import numpy as np

ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", ".."))
CACHE = os.path.join(ROOT, ".local", "a1-cache"); os.makedirs(CACHE, exist_ok=True)


def get(url, name):
    path = os.path.join(CACHE, name)
    if os.path.exists(path):
        return json.load(open(path))
    with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "herd-research-readonly"}), timeout=30) as r:
        d = json.load(r)
    json.dump(d, open(path, "w")); time.sleep(0.5)
    return d


rows = []
for tag, nm in ((2, "politics"), (1, "sports")):
    ms = get(f"https://gamma-api.polymarket.com/markets/keyset?tag_id={tag}&closed=false&order=volume24hr&ascending=false&limit=60", f"top_{nm}.json")["markets"]
    for m in ms:
        try:
            p = float(json.loads(m["outcomePrices"])[0]); tok = json.loads(m["clobTokenIds"])[0]
        except Exception:
            continue
        rows.append(dict(tag=nm, id=m["id"], liq=float(m.get("liquidityNum") or 0), v24=float(m.get("volume24hr") or 0), p=p, tok=tok))
print(f"markets fetched: {len(rows)} (top-60 by 24h volume of politics and sports, {time.strftime('%Y-%m-%d')})")
liq = np.array([r["liq"] for r in rows])
print("Gamma liquidityNum (USD), all top markets: min %.0f p10 %.0f p25 %.0f median %.0f p75 %.0f p90 %.0f max %.0f" % ((liq.min(),) + tuple(np.percentile(liq, [10, 25, 50, 75, 90])) + (liq.max(),)))
for nm in ("politics", "sports"):
    l = np.array([r["liq"] for r in rows if r["tag"] == nm]); print(f"  {nm}: n={len(l)} median {np.median(l):.0f}, p10 {np.percentile(l,10):.0f}, share < 50k: {np.mean(l<50000):.2f}, share < 100k: {np.mean(l<100000):.2f}")

mid = [r for r in rows if 0.1 <= r["p"] <= 0.9]
print(f"\nmarkets with 0.10 <= p <= 0.90 (not a tail price): {len(mid)}")
moves = []   # (liq, rel move) per 3-min window
moves15 = []
for r in mid:
    h = get(f"https://clob.polymarket.com/prices-history?market={r['tok']}&interval=1d&fidelity=1", f"h_{r['id']}.json").get("history", [])
    if len(h) < 100:
        continue
    t = np.array([x["t"] for x in h]); p = np.array([x["p"] for x in h])
    grid = np.arange(t[0] // 60 * 60, t[-1], 60)
    pg = p[np.searchsorted(t, grid, side="right") - 1]
    for i in range(0, len(pg) - 3, 3):
        if pg[i] > 0:
            moves.append((r["liq"], abs(pg[i + 3] / pg[i] - 1)))
    for i in range(0, len(pg) - 15, 15):
        if pg[i] > 0:
            moves15.append((r["liq"], abs(pg[i + 15] / pg[i] - 1)))
mv = np.array(moves)
print(f"3-minute windows: {len(mv)} from {len(mid)} markets (1 day each)")
def q(x): return "mean %.4f  p99 %.4f  p99.9 %.4f  max %.4f" % (x.mean(), np.percentile(x, 99), np.percentile(x, 99.9), x.max())
print("all:", q(mv[:, 1]))
for lo, hi in ((0, 50e3), (50e3, 200e3), (200e3, 1e9)):
    s = (mv[:, 0] >= lo) & (mv[:, 0] < hi)
    if s.sum() > 50:
        print(f"liquidity {lo/1e3:.0f}k-{hi/1e3:.0f}k USD: windows {s.sum()}  " + q(mv[s, 1]))
print("share of windows with a move >= 1 tick-equivalent (>=1%):", np.mean(mv[:, 1] >= 0.01).round(3), " >= 5%:", np.mean(mv[:, 1] >= 0.05).round(4), " >= 20%:", np.mean(mv[:, 1] >= 0.20).round(5))

mv15 = np.array(moves15)
print(f"\n15-minute windows: {len(mv15)} (same markets)")
print("all:", q(mv15[:, 1]))
for lo, hi in ((0, 50e3), (50e3, 200e3), (200e3, 1e9)):
    s_ = (mv15[:, 0] >= lo) & (mv15[:, 0] < hi)
    if s_.sum() > 50:
        print(f"liquidity {lo/1e3:.0f}k-{hi/1e3:.0f}k USD: windows {s_.sum()}  " + q(mv15[s_, 1]))
print("share of 3-minute windows with NO price change:", np.mean(mv[:, 1] == 0).round(3))
