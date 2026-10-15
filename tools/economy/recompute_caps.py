#!/usr/bin/env python3
"""Weekly recompute of Herd leverage caps, fee rates and stress moves from real candles (economy stage D).

    python3 -I tools/economy/recompute_caps.py --weeks 4 --out <dir>
    python3 -I tools/economy/recompute_caps.py --from-cache <dir>/candles --out <dir2>

Python 3 standard library only. Reads two public, key-less candle APIs (Coinbase Exchange, Hyperliquid info) and writes
caps_proposal.json / caps_proposal.md. It never touches a database or a server; applying the proposal is the operator's
job (see README.md). Method: docs/research/game_economy_math.md sections 1, 2, 3.2 and 9.

Round lengths: the engine's HOURLY interval is the 3-minute round (markets.cap_hourly), DAILY the 15-minute round
(markets.cap_daily) -- see db/migration/V11__economy_stage_a.sql.

Liquidation exactly as the engine (LiquidationCalculator / PositionSimulator, economy stage A): with the maintenance rate
l = kappa / cap, a long is liquidated in a round iff low <= P0 * (1 - 1/L) / (1 - l), a short iff high >= P0 * (1 + 1/L) / (1 + l).
On liquidation the remainder max(0, stake * (1 + side * L * (P_extreme / P0 - 1))) is returned (P_extreme = low for a long,
high for a short). The round's low/high come from real candles of the finest interval available.
"""
import argparse
import bisect
import json
import math
import os
import sys
import time
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor

MINUTE = 60_000
WEEK = 7 * 86_400_000

KAPPA = 0.1                 # maintenance constant of price markets (V11)
TARGET_PLIQ = 0.35          # liquidation probability at the exact cap
SAFETY = 0.8                # conservative factor on the exact cap
R_M_MULT, R_M_MIN, R_M_MAX = 0.10, 0.2, 1.0     # r_m (bp of notional) = clip(0.10 * sigma3_bp, 0.2, 1.0)
HERD_FEE_MAX_SHARE = 0.01   # EconomyRules.HERD_FEE_MAX_SHARE
LEVERAGE_ABS_MAX = 10_000   # Leverage.MAX after stage A
LEVERAGE_MIN = 2
ROUND_MINUTES = {"cap_hourly": 3, "cap_daily": 15}      # column -> round length
CAP_STEPS = [1, 1.2, 1.5, 2, 2.5, 3, 4, 5, 6, 8, 10]    # the "round numbers" caps are rounded down to

CRYPTO = ["BTC", "ETH", "SOL", "DOGE", "XRP", "HYPE"]  # Coinbase <SYM>-USD, Hyperliquid <SYM>
XYZ = ["NVDA", "TSLA", "GOLD", "BRENTOIL", "SP500", "XYZ100"]  # Hyperliquid xyz:<SYM>
SYMBOLS = CRYPTO + XYZ

# Current values: V11__economy_stage_a.sql (cap_hourly, cap_daily, herd_fee_bp); stress_move is NULL until filled.
CURRENT_DEFAULTS = {
    "BTC": (1200, 500, 0.92), "ETH": (1000, 400, 1.0), "SOL": (600, 300, 1.0), "DOGE": (600, 300, 1.0),
    "XRP": (600, 300, 1.0), "HYPE": (600, 250, 1.0), "NVDA": (1500, 800, 0.75), "TSLA": (1500, 1000, 0.70),
    "BRENTOIL": (1000, 500, 0.83), "GOLD": (2000, 1200, 0.48), "XYZ100": (2000, 1500, 0.45), "SP500": (4000, 2000, 0.24),
}

BASE_MINUTES = {"1m": 1, "3m": 3, "15m": 15}
HL_URL = "https://api.hyperliquid.xyz/info"
CB_URL = "https://api.exchange.coinbase.com/products/{prod}/candles?granularity=60&start={s}&end={e}"
UA = {"user-agent": "herd-economy-recompute/1", "content-type": "application/json"}


# ---------------------------------------------------------------- candles -> windows

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


class Windows:
    """Round windows aligned to the epoch grid (start % (T*60000) == 0): parallel lists of ratios to the open price."""

    def __init__(self, minutes, starts, lo, hi, close):
        self.minutes = minutes
        self.starts = starts
        self.lo = lo          # low / open
        self.hi = hi          # high / open
        self.close = close    # close / open
        self.n = len(starts)
        self._lo_sorted = sorted(lo)
        self._hi_sorted = sorted(hi)

    @property
    def span_days(self):
        return self.n * self.minutes / 1440.0

    def subset(self, idx_from, idx_to):
        sl = slice(idx_from, idx_to)
        return Windows(self.minutes, self.starts[sl], self.lo[sl], self.hi[sl], self.close[sl])

    def log_returns(self):
        return [math.log(c) for c in self.close]


def build_windows(rows, base_min, round_min):
    """rows: [[t_ms, open, high, low, close], ...] of `base_min`-minute candles (finished ones only) -> Windows of
    `round_min` minutes. A window needs all of its sub-candles; incomplete ones are dropped."""
    if round_min % base_min:
        raise ValueError("round length %d is not a multiple of the candle interval %d" % (round_min, base_min))
    per = round_min // base_min
    step = base_min * MINUTE
    span = round_min * MINUTE
    groups = {}
    for r in rows:
        groups.setdefault(r[0] // span * span, {})[r[0]] = r
    starts, lo, hi, cl = [], [], [], []
    for s in sorted(groups):
        g = groups[s]
        if len(g) != per:
            continue
        subs = [g.get(s + k * step) for k in range(per)]
        if any(x is None for x in subs):
            continue
        o = subs[0][1]
        if o <= 0:
            continue
        starts.append(s)
        hi.append(max(x[2] for x in subs) / o)
        lo.append(min(x[3] for x in subs) / o)
        cl.append(subs[-1][4] / o)
    return Windows(round_min, starts, lo, hi, cl)


# ---------------------------------------------------------------- math

def liq_levels(L, cap, kappa=KAPPA):
    """(long liquidation ratio, short liquidation ratio) of price/open, with l = kappa / cap."""
    l = kappa / cap
    return (1 - 1 / L) / (1 - l), (1 + 1 / L) / (1 + l)


def p_liq(w, L, cap=None, kappa=KAPPA):
    """P(liquidation) averaged over a long and a short at leverage L (cap defaults to L: the exact-cap definition)."""
    if w.n == 0:
        return 0.0
    dl, us = liq_levels(L, L if cap is None else cap, kappa)
    a = bisect.bisect_right(w._lo_sorted, dl)
    b = w.n - bisect.bisect_left(w._hi_sorted, us)
    return (a + b) / (2 * w.n)


def exact_cap(w, target=TARGET_PLIQ, kappa=KAPPA, hi_limit=100_000):
    """Smallest integer L (= the cap itself, l = kappa/L) at which P(liq) reaches `target`; hi_limit if never."""
    lo, hi = LEVERAGE_MIN, hi_limit
    if p_liq(w, hi, kappa=kappa) < target:
        return hi_limit
    while hi - lo > 1:
        mid = (lo + hi) // 2
        if p_liq(w, mid, kappa=kappa) < target:
            lo = mid
        else:
            hi = mid
    return hi


def nice_down(m):
    """Round down to 1 / 1.2 / 1.5 / 2 / 2.5 / 3 / 4 / 5 / 6 / 8 x 10^k (below 10: plain int)."""
    if m < 10:
        return int(m)
    e = 10 ** int(math.floor(math.log10(m)))
    return int(max(s for s in CAP_STEPS if round(s * e) <= m) * e) if any(round(s * e) <= m for s in CAP_STEPS) else int(m)


def proposed_cap(exact):
    return max(LEVERAGE_MIN, min(LEVERAGE_ABS_MAX, nice_down(SAFETY * exact)))


def mean_sd_kurt(x):
    n = len(x)
    if n < 2:
        return 0.0, 0.0
    m = sum(x) / n
    s2 = sum((v - m) ** 2 for v in x) / n
    s = math.sqrt(s2)
    return s, (sum(((v - m) / s) ** 4 for v in x) / n - 3 if s > 0 else 0.0)


def r_m_bp(sigma3):
    """Herd fee rate, bp of notional: clip(0.10 * sigma3 in bp, 0.2, 1.0), 2 decimals."""
    return round(min(R_M_MAX, max(R_M_MIN, R_M_MULT * sigma3 * 1e4)), 2)


def stress_move(w):
    """(stress, p99.9, 5 sigma) as fractions of price for one round length: stress = max(p99.9 of |log return|, 5 sigma),
    the mathematician's treasury stress move (game_economy_math.md section 4)."""
    r = w.log_returns()
    s, _ = mean_sd_kurt(r)
    x = sorted(abs(v) for v in r)
    p999 = x[min(len(x) - 1, int(0.999 * len(x)))] if x else 0.0
    return max(p999, 5 * s), p999, 5 * s


def evaluate(w, L, cap, kappa=KAPPA, fee_bp=0.0):
    """Outcome at leverage L with maintenance rate kappa/cap over all windows, long and short averaged, no skill.
    Returns mean payout/stake, fee share, RTP = 1 - fee - remainder gap (= mean payout - fee), remainder gap, P(liq),
    P(stake lost) = share of bets paying less than the stake."""
    dl, us = liq_levels(L, cap, kappa)
    tot = 0.0
    liq = lose = 0
    for lo, hi, cl in zip(w.lo, w.hi, w.close):
        if lo <= dl:
            p = max(0.0, 1 + L * (lo - 1))
            liq += 1
        else:
            p = max(0.0, 1 + L * (cl - 1))
        tot += p
        lose += p < 1
        if hi >= us:
            p = max(0.0, 1 - L * (hi - 1))
            liq += 1
        else:
            p = max(0.0, 1 - L * (cl - 1))
        tot += p
        lose += p < 1
    n2 = 2 * w.n
    mean = tot / n2
    fee = min(HERD_FEE_MAX_SHARE, fee_bp / 1e4 * L)
    return {"mean_payout": mean, "fee": fee, "gap": 1 - mean, "rtp": mean - fee, "p_liq": liq / n2, "p_lose": lose / n2}


def bands(cap):
    """The plan's leverage bands: 2-10, 11-100, 101-cap/4, cap/4-cap (empty ones dropped, clipped to the cap)."""
    q = cap // 4
    raw = [("2-10", 2, 10), ("11-100", 11, 100), ("101-cap/4", 101, q), ("cap/4-cap", max(q + 1, 101), cap)]
    return [(lab, lo, min(hi, cap)) for lab, lo, hi in raw if lo <= min(hi, cap)]


def band_points(lo, hi, k=6):
    pts = {lo, hi}
    if hi > lo:
        for i in range(1, k - 1):
            pts.add(int(round(lo * (hi / lo) ** (i / (k - 1)))))
    return sorted(p for p in pts if lo <= p <= hi)


def rtp_table(w, cap, fee_bp, kappa=KAPPA):
    out = []
    for lab, lo, hi in bands(cap):
        ev = [evaluate(w, L, cap, kappa, fee_bp) for L in band_points(lo, hi)]
        top = ev[-1]
        out.append({
            "band": lab, "lev_from": lo, "lev_to": hi, "points": len(ev),
            "rtp": sum(e["rtp"] for e in ev) / len(ev), "rtp_at_top": top["rtp"],
            "remainder_gap": sum(e["gap"] for e in ev) / len(ev),
            "liq_share": sum(e["p_liq"] for e in ev) / len(ev), "liq_share_at_top": top["p_liq"],
            "lose_share": sum(e["p_lose"] for e in ev) / len(ev), "lose_share_at_top": top["p_lose"],
        })
    return out


def week_blocks(w):
    """Split windows into 7-day blocks counted from the first window; keep blocks that are at least 80 % full."""
    if w.n == 0:
        return []
    per_week = WEEK / (w.minutes * MINUTE)
    t0 = w.starts[0]
    idx = {}
    for i, s in enumerate(w.starts):
        idx.setdefault((s - t0) // WEEK, []).append(i)
    return [w.subset(v[0], v[-1] + 1) for k, v in sorted(idx.items()) if len(v) >= 0.8 * per_week]


# ---------------------------------------------------------------- analysis of one (market, round length)

def analyse(w, cur_cap, fee_bp_for_rtp, source):
    """Everything for one (market, round length) from its windows; fee_bp_for_rtp is the proposed r_m of the market."""
    sigma, kurt = mean_sd_kurt(w.log_returns())
    ex = exact_cap(w)
    prop = proposed_cap(ex)
    blocks = week_blocks(w)
    wk_exact = [exact_cap(b) for b in blocks]
    wk_pliq = [p_liq(b, prop) for b in blocks]
    st, p999, s5 = stress_move(w)
    res = {
        "source": source, "windows": w.n, "depth_days": round(w.span_days, 2),
        "from_ms": w.starts[0] if w.n else None, "to_ms": (w.starts[-1] + w.minutes * MINUTE) if w.n else None,
        "sigma": sigma, "sigma_bp": sigma * 1e4, "excess_kurtosis": kurt,
        "stress_move": st, "stress_p999": p999, "stress_5sigma": s5,
        "exact_cap": ex, "proposed_cap": prop, "current_cap": cur_cap,
        "p_liq_at_proposed": p_liq(w, prop), "p_liq_at_exact": p_liq(w, ex),
        "weekly_blocks": len(blocks), "weekly_exact_caps": wk_exact,
        "weekly_exact_ratio": (max(wk_exact) / min(wk_exact)) if len(wk_exact) >= 2 else None,
        "weekly_p_liq_at_proposed": wk_pliq,
        "needs_operator_approval": cur_cap is not None and prop > cur_cap,
        "rtp_by_band": rtp_table(w, prop, fee_bp_for_rtp),
    }
    return res


# ---------------------------------------------------------------- network

def _get(url, body=None, retries=5):
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


def fetch_coinbase(symbol, start_ms, end_ms, pause=0.35):
    """1-minute candles [[t_ms, o, h, l, c]] of <SYM>-USD, 300 per call; flat minutes filled."""
    rows = {}
    cur = start_ms // MINUTE * MINUTE
    end_ms = end_ms // MINUTE * MINUTE
    fmt = lambda ms: time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(ms / 1000))
    while cur < end_ms:
        e = min(cur + 300 * MINUTE, end_ms)
        data = _get(CB_URL.format(prod=symbol + "-USD", s=fmt(cur), e=fmt(e)))
        for t, lo, hi, op, cl, _v in data:
            if t * 1000 < end_ms:                     # drop the minute still forming
                rows[t * 1000] = [t * 1000, float(op), float(hi), float(lo), float(cl)]
        cur = e
        time.sleep(pause)
    return fill_flat_minutes([rows[k] for k in sorted(rows)])


def fetch_hyperliquid(coin, interval, start_ms, end_ms, now_ms):
    """Candles [[t_ms, o, h, l, c]] of one interval; the API serves only the most recent ~5000 candles, so the start
    moves forward until it answers, and a full page continues from its last candle."""
    rows = {}
    cur = start_ms
    shifts = 0
    while cur < end_ms:
        d = _get(HL_URL, {"type": "candleSnapshot", "req": {"coin": coin, "interval": interval, "startTime": int(cur), "endTime": int(end_ms)}})
        if not d:
            if rows or shifts > 12:
                break
            cur += max((end_ms - cur) // 3, 3_600_000)
            shifts += 1
            continue
        for c in d:
            if c["T"] <= now_ms:                      # finished candles only
                rows[c["t"]] = [c["t"], float(c["o"]), float(c["h"]), float(c["l"]), float(c["c"])]
        last = max(c["t"] for c in d)
        if len(d) < 4900:
            break
        cur = last + 1
        time.sleep(0.3)
    return [rows[k] for k in sorted(rows)]


def fetch_all(weeks, cache_dir, symbols):
    now_ms = int(time.time() * 1000)
    start_ms = now_ms - int(weeks * WEEK)
    os.makedirs(cache_dir, exist_ok=True)

    def save(name, rows):
        with open(os.path.join(cache_dir, name), "w") as f:
            json.dump(rows, f)

    def job(sym):
        msgs = []
        try:
            if sym in CRYPTO:
                try:
                    rows = fetch_coinbase(sym, start_ms, now_ms)
                    save("cb_%s_1m.json" % sym, rows)
                    msgs.append("CB %s 1m %d" % (sym, len(rows)))
                except Exception as e:
                    msgs.append("CB %s FAILED %s" % (sym, e))
            coin = sym if sym in CRYPTO else "xyz:" + sym
            for iv in ("1m", "3m", "15m"):
                try:
                    rows = fetch_hyperliquid(coin, iv, start_ms, now_ms, now_ms)
                    save("hl_%s_%s.json" % (sym, iv), rows)
                    msgs.append("HL %s %s %d" % (sym, iv, len(rows)))
                except Exception as e:
                    msgs.append("HL %s %s FAILED %s" % (sym, iv, e))
                time.sleep(0.3)
        except Exception as e:
            msgs.append("%s FAILED %s" % (sym, e))
        return msgs

    with ThreadPoolExecutor(max_workers=3) as ex:
        for msgs in ex.map(job, symbols):
            for m in msgs:
                print(m, file=sys.stderr)
    meta = {"fetched_at_ms": now_ms, "weeks": weeks}
    with open(os.path.join(cache_dir, "meta.json"), "w") as f:
        json.dump(meta, f)
    return meta


# ---------------------------------------------------------------- source selection

def load_sources(cache_dir, symbol):
    """[(label, base_minutes, rows)] available in the cache for one symbol."""
    out = []
    p = os.path.join(cache_dir, "cb_%s_1m.json" % symbol)
    if os.path.exists(p):
        rows = json.load(open(p))
        if rows:
            out.append(("Coinbase 1m", 1, rows))
    for iv, base in BASE_MINUTES.items():
        p = os.path.join(cache_dir, "hl_%s_%s.json" % (symbol, iv))
        if os.path.exists(p):
            rows = json.load(open(p))
            if rows:
                out.append(("Hyperliquid " + iv, base, rows))
    return out


def pick_source(sources, round_min):
    """Longest history wins; among sources within 10 % of the longest the finest candle interval (Coinbase on a tie).
    Returns (label, Windows) or (None, None)."""
    cands = []
    for label, base, rows in sources:
        if round_min % base:
            continue
        w = build_windows(rows, base, round_min)
        if w.n:
            cands.append((label, base, w))
    if not cands:
        return None, None
    best = max(c[2].span_days for c in cands)
    ok = [c for c in cands if c[2].span_days >= 0.9 * best]
    label, _b, w = min(ok, key=lambda c: (c[1], 0 if c[0].startswith("Coinbase") else 1))
    return label, w


# ---------------------------------------------------------------- proposal

def fmt_change(old, new):
    return "n/a" if old is None else ("same" if new == old else ("UP" if new > old else "down"))


def make_proposal(cache_dir, symbols, current, generated_at_ms, weeks):
    markets, safe, review, approvals, notes = {}, {}, {}, [], []
    for sym in symbols:
        sources = load_sources(cache_dir, sym)
        cur = current.get(sym, {})
        per = {}
        for col, T in ROUND_MINUTES.items():
            label, w = pick_source(sources, T)
            if w is None:
                notes.append("%s %d-min: no candles in the cache, market skipped for this round length" % (sym, T))
            per[col] = (label, w)
        # sigma3 -> r_m (3-minute windows; if only 15-minute data exists r_m cannot be computed)
        w3 = per["cap_hourly"][1]
        sigma3 = mean_sd_kurt(w3.log_returns())[0] if w3 else None
        fee = r_m_bp(sigma3) if sigma3 is not None else cur.get("herd_fee_bp")
        entry, details = {}, {"round": {}}
        stresses = []
        for col, T in ROUND_MINUTES.items():
            label, w = per[col]
            if w is None:
                continue
            a = analyse(w, cur.get(col), fee if fee is not None else 0.0, label)
            details["round"]["%dm" % T] = a
            entry[col] = a["proposed_cap"]
            stresses.append(a["stress_move"])
            if a["depth_days"] < weeks * 7 * 0.9:
                notes.append("%s %d-min: only %.1f of %d days (%s)" % (sym, T, a["depth_days"], weeks * 7, label))
            if a["weekly_blocks"] < 2:
                notes.append("%s %d-min: %d full week block(s): weekly regime spread not measurable" % (sym, T, a["weekly_blocks"]))
        if not entry:
            continue
        entry["kappa"] = KAPPA
        entry["herd_fee_bp"] = fee
        entry["stress_move"] = round(max(stresses), 6) if stresses else None
        markets[sym] = entry
        details["sigma3_bp"] = sigma3 * 1e4 if sigma3 is not None else None
        details["current"] = cur
        review[sym] = details
        s = dict(entry)
        for col in ROUND_MINUTES:
            if col in entry:
                c = cur.get(col)
                if c is not None and entry[col] > c:
                    approvals.append({"symbol": sym, "column": col, "current": c, "proposed": entry[col], "reason": "cap above the current value"})
                    s[col] = c
        c = cur.get("herd_fee_bp")
        if c is not None and fee is not None and fee > c:
            approvals.append({"symbol": sym, "column": "herd_fee_bp", "current": c, "proposed": fee, "reason": "fee above the current value"})
            s["herd_fee_bp"] = c
        safe[sym] = s
    return {
        "generated_at_ms": generated_at_ms,
        "generated_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(generated_at_ms / 1000)),
        "weeks_requested": weeks,
        "params": {"kappa": KAPPA, "target_p_liq": TARGET_PLIQ, "safety": SAFETY, "r_m_mult": R_M_MULT,
                   "r_m_min_bp": R_M_MIN, "r_m_max_bp": R_M_MAX, "leverage_abs_max": LEVERAGE_ABS_MAX},
        "markets": markets,
        "markets_apply_without_approval": safe,
        "needs_operator_approval": approvals,
        "notes": notes,
        "details": review,
    }


# ---------------------------------------------------------------- markdown

def pct(x, d=1):
    return "-" if x is None else "%.*f %%" % (d, 100 * x)


def render_md(p):
    L = []
    L.append("# Weekly recompute of caps, fees and stress moves (economy stage D)")
    L.append("")
    L.append("Generated %s from the last %s week(s) of real candles. Method: `docs/research/game_economy_math.md` sections 1-3.2, 9. "
             "Proposed cap = %.1f x the exact leverage at which P(liquidation) = %d %% (kappa = %s, remainder returned at the candle extreme), "
             "rounded down to 1 / 1.2 / 1.5 / 2 / 2.5 / 3 / 4 / 5 / 6 / 8 x 10^k. Nothing here has been applied." %
             (p["generated_at"], p["weeks_requested"], SAFETY, round(TARGET_PLIQ * 100), KAPPA))
    L.append("")
    L.append("## Caps: current vs proposed")
    L.append("")
    L.append("| Market | Round | Current | Exact (35 %) | Proposed | Change | Data (source, days) | sigma, bp | Excess kurt. | Weekly exact cap min-max (blocks, max/min) | P(liq) at proposed: all / weekly min-max | Approval |")
    L.append("|---|---|---|---|---|---|---|---|---|---|---|---|")
    for sym, d in p["details"].items():
        for key in ("3m", "15m"):
            a = d["round"].get(key)
            if not a:
                continue
            wk = a["weekly_exact_caps"]
            wks = "%d-%d (%d, %.2f)" % (min(wk), max(wk), len(wk), a["weekly_exact_ratio"]) if len(wk) >= 2 else ("%d (1 block)" % wk[0] if wk else "-")
            wp = a["weekly_p_liq_at_proposed"]
            wps = "%s / %s" % (pct(a["p_liq_at_proposed"], 0), "%s-%s" % (pct(min(wp), 0), pct(max(wp), 0)) if wp else "-")
            L.append("| %s | %s | %s | %d | %d | %s | %s, %.1f | %.1f | %.1f | %s | %s | %s |" % (
                sym, key, a["current_cap"] if a["current_cap"] is not None else "-", a["exact_cap"], a["proposed_cap"],
                fmt_change(a["current_cap"], a["proposed_cap"]), a["source"], a["depth_days"], a["sigma_bp"],
                a["excess_kurtosis"], wks, wps, "**needs operator approval**" if a["needs_operator_approval"] else "-"))
    L.append("")
    L.append("## Fee rate and stress move")
    L.append("")
    L.append("r_m = clip(0.10 x sigma3 in bp, 0.2 bp, 1.0 bp). Stress move = max(99.9th percentile of the round's |log return|, 5 sigma) per round length; the `markets.stress_move` column takes the larger of the two lengths.")
    L.append("")
    L.append("| Market | sigma3, bp | r_m current, bp | r_m proposed, bp | Stress 3 min | Stress 15 min | stress_move (column) |")
    L.append("|---|---|---|---|---|---|---|")
    for sym, d in p["details"].items():
        r3, r15 = d["round"].get("3m"), d["round"].get("15m")
        m = p["markets"][sym]
        L.append("| %s | %s | %s | %s | %s | %s | %s |" % (
            sym, "%.2f" % d["sigma3_bp"] if d["sigma3_bp"] is not None else "-", d["current"].get("herd_fee_bp", "-"), m["herd_fee_bp"],
            pct(r3["stress_move"], 3) if r3 else "-", pct(r15["stress_move"], 3) if r15 else "-", "%.6f" % m["stress_move"] if m["stress_move"] is not None else "-"))
    L.append("")
    L.append("## RTP and liquidation share by leverage band (at the proposed caps, no skill)")
    L.append("")
    L.append("RTP = 1 - herd fee - remainder gap (network fee of 0.1 PLAY is stake-dependent and excluded). Liquidated = share of rounds in which the bet is "
             "liquidated (the plan's stake-is-lost figure); losing bet = share of rounds in which it pays less than the stake (about half of all rounds, liquidated or not). Averages over up to 6 "
             "leverages per band (geometric), plus the value at the band's top leverage.")
    for key in ("3m", "15m"):
        L.append("")
        L.append("### %s rounds" % key.replace("m", " min"))
        L.append("")
        L.append("| Market | Band | RTP avg | RTP at top | Remainder gap avg | Liquidated avg / top | Losing bet avg / top |")
        L.append("|---|---|---|---|---|---|---|")
        for sym, d in p["details"].items():
            a = d["round"].get(key)
            if not a:
                continue
            for b in a["rtp_by_band"]:
                L.append("| %s | x%d-%d | %s | %s | %s | %s / %s | %s / %s |" % (
                    sym, b["lev_from"], b["lev_to"], pct(b["rtp"], 2), pct(b["rtp_at_top"], 2), pct(b["remainder_gap"], 2),
                    pct(b["liq_share"]), pct(b["liq_share_at_top"]), pct(b["lose_share"]), pct(b["lose_share_at_top"])))
    L.append("")
    L.append("## Needs operator approval")
    L.append("")
    if p["needs_operator_approval"]:
        L.append("The tool does not raise anything above the current value by itself. These proposed increases are listed in `markets` but are held back in `markets_apply_without_approval`:")
        L.append("")
        L.append("| Market | Column | Current | Proposed |")
        L.append("|---|---|---|---|")
        for x in p["needs_operator_approval"]:
            L.append("| %s | %s | %s | %s |" % (x["symbol"], x["column"], x["current"], x["proposed"]))
    else:
        L.append("None: no proposed value is above the current one.")
    L.append("")
    L.append("## Data notes")
    L.append("")
    for n in p["notes"] or ["none"]:
        L.append("- " + n)
    L.append("")
    return "\n".join(L)


# ---------------------------------------------------------------- main

def load_current(path):
    cur = {s: {"cap_hourly": v[0], "cap_daily": v[1], "herd_fee_bp": v[2], "kappa": KAPPA, "stress_move": None} for s, v in CURRENT_DEFAULTS.items()}
    if path:
        for s, v in json.load(open(path)).items():
            cur.setdefault(s, {}).update(v)
    return cur


def run(out_dir, weeks, from_cache=None, current_path=None, symbols=None):
    symbols = symbols or SYMBOLS
    os.makedirs(out_dir, exist_ok=True)
    if from_cache:
        cache_dir = from_cache
        meta = json.load(open(os.path.join(cache_dir, "meta.json"))) if os.path.exists(os.path.join(cache_dir, "meta.json")) else {}
        weeks = meta.get("weeks", weeks)
        generated = meta.get("fetched_at_ms", int(time.time() * 1000))
    else:
        cache_dir = os.path.join(out_dir, "candles")
        generated = fetch_all(weeks, cache_dir, symbols)["fetched_at_ms"]
    p = make_proposal(cache_dir, symbols, load_current(current_path), generated, weeks)
    with open(os.path.join(out_dir, "caps_proposal.json"), "w") as f:
        json.dump(p, f, indent=1)
    with open(os.path.join(out_dir, "caps_proposal.md"), "w") as f:
        f.write(render_md(p))
    return p


def main(argv=None):
    ap = argparse.ArgumentParser(description="Weekly recompute of Herd leverage caps, fee rates and stress moves from real candles.")
    ap.add_argument("--weeks", type=float, default=4, help="weeks of candles to fetch (default 4)")
    ap.add_argument("--out", required=True, help="output directory (outside the repo, or gitignored); candles are cached in <out>/candles")
    ap.add_argument("--from-cache", metavar="DIR", help="candle cache directory of an earlier run (<out>/candles): no network")
    ap.add_argument("--current", metavar="JSON", help="current market values {SYMBOL: {cap_hourly, cap_daily, herd_fee_bp}}; default = V11 seeds")
    ap.add_argument("--markets", help="comma-separated symbols (default: all 12)")
    a = ap.parse_args(argv)
    syms = [s.strip().upper() for s in a.markets.split(",")] if a.markets else None
    p = run(a.out, a.weeks, a.from_cache, a.current, syms)
    print("wrote %s and %s (%d markets, %d needing operator approval)" % (
        os.path.join(a.out, "caps_proposal.json"), os.path.join(a.out, "caps_proposal.md"), len(p["markets"]), len(p["needs_operator_approval"])))


if __name__ == "__main__":
    main()
