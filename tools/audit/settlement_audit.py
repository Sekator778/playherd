#!/usr/bin/env python3
"""Settlement audit of the Herd stand: re-computes every settled position from the stored facts and compares.

Read-only. Python 3 standard library only. Connection settings come from .local/vps.env (HERD_VPS_HOST, HERD_VPS_KEY,
HERD_VPS_SSH_PORT), as in tools/vps; the database is queried over ssh inside a READ ONLY transaction.

What is checked for every settled position (bots included), mirroring the engine's rules
(engine/.../economy/EconomyRules.java, margin/LiquidationCalculator.java, round/PositionSimulator.java, game/GameEngine.java):

  size, initial margin, liquidation price  - position size = round8(S*L/P0); M = round2(size*P0/L);
                                             liq = round2((size*P0 - s*M) / (size*(1 - l*s))), l = kappa/cap
  liquidation flag                         - the stored RoundResolved path against the stored liquidation price
                                             (long: low <= liq, short: high >= liq)
  payout                                   - not liquidated: max(0, round2(M + s*size*(close-P0)));
                                             liquidated: the remainder at the extreme of the triggering candle (a legacy bet: 0);
                                             then capped at min(20*S, 0.1 % of the treasury) (not for legacy bets)
  fees                                     - herd = ceil3(S * min(guard, k*r_m*L)) (at least 0.001), network = 0.1, with the
                                             rate history of the herd fee (before / after the switch of 2026-10-18 ~07:53 UTC)
  fixed-payout bets (BetPlaced "mode":"FIXED", Р-106)
                                           - no size / liquidation / tier; payout 2*S when the close is on the bet's side of the
                                             open, 0 on the other side, S when close = open; fee ceil3(3 % of S) + 0.1 PLAY
  bet                                      - leverage inside the stake tier (cap, cap/2, cap/5, cap/10), placed before the round
  round                                    - entry price = round open, path first open / last close = round open / close
  coinbase (spot_* with venue coinbase)    - round open/close against Coinbase public 1-minute candles, engine path range
                                             against the Coinbase range

Hard mismatches make the exit status 1. Warnings (checks that need market parameters the bet did not store, e.g. a bet
without liqCap) are listed but do not change the exit status.
"""
import argparse
import json
import math
import os
import subprocess
import sys
import time
import urllib.request
from collections import defaultdict
from datetime import datetime, timezone
from decimal import ROUND_CEILING, ROUND_DOWN, ROUND_HALF_UP, Context, Decimal, getcontext

getcontext().prec = 200
D = Decimal
DECIMAL128 = Context(prec=34)  # java.math.MathContext.DECIMAL128: 34 digits, HALF_EVEN
CENT = D("0.01")
ONE = D(1)

REPO = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
# V13 / Р-102: the migration ran at 07:50:47 UTC, but the server kept charging the old fees until its restart: in the stored bets
# the last old-rule fee is at 07:53:00.009 and the first new-rule fee at 07:53:27.000. A bet inside that gap may carry either set.
FEE_DEPLOY_MS = int(datetime(2026, 10, 18, 7, 53, 27, tzinfo=timezone.utc).timestamp() * 1000)
FEE_OLD_UNTIL_MS = int(datetime(2026, 10, 18, 7, 53, 0, tzinfo=timezone.utc).timestamp() * 1000)
RATE_CHANGED_SYMBOLS = {"ETH", "SOL", "DOGE", "XRP", "HYPE"}  # V13 moved these PERP markets and all ODDS markets from 1.0 bp
OLD_RATE_BP = D("1.0")
OLD_GUARD = D("0.01")
NEW_GUARD = D("0.20")
LONG_K = {"PERP": D("2.2"), "ODDS": D("3.0")}
NETWORK_FEE_MOJOS = 100
TREASURY_FALLBACK_PLAY = D(99_998_100)
TIER_UPPER = [(D(250), 1), (D(500), 2), (D(1200), 5)]
INTERVALS = {"HOURLY": "3 min", "DAILY": "15 min"}


def r2(x):
    return x.quantize(CENT, rounding=ROUND_HALF_UP)


def div(a, b):
    return DECIMAL128.divide(a, b)


# ----------------------------------------------------------------------------------------------------------------- database

def load_env():
    env = {}
    path = os.path.join(REPO, ".local", "vps.env")
    try:
        with open(path) as fh:
            for line in fh:
                line = line.strip()
                if line and not line.startswith("#") and "=" in line:
                    k, v = line.split("=", 1)
                    env[k.strip()] = v.strip().strip("'\"")
    except OSError:
        sys.exit("cannot read %s (HERD_VPS_HOST, HERD_VPS_KEY, HERD_VPS_SSH_PORT)" % path)
    for k in ("HERD_VPS_HOST",):
        if not env.get(k):
            sys.exit("%s missing in .local/vps.env" % k)
    return env


def psql(sql, env):
    key = os.path.expanduser(env.get("HERD_VPS_KEY") or "~/.ssh/herd_vps")
    known = os.path.expanduser(os.environ.get("HERD_KNOWN_HOSTS", "~/.ssh/known_hosts.herd-vps"))
    container = os.environ.get("HERD_DB_CONTAINER", "herd-db-1")
    remote = ('docker exec -i %s sh -c "psql -U \\$POSTGRES_USER -d \\$POSTGRES_DB -X -q -A -t -v ON_ERROR_STOP=1"' % container)
    cmd = ["ssh", "-o", "UserKnownHostsFile=" + known, "-o", "BatchMode=yes", "-i", key,
           "-p", env.get("HERD_VPS_SSH_PORT") or "22", "deploy@" + env["HERD_VPS_HOST"], remote]
    res = subprocess.run(cmd, input="begin read only;\n" + sql + "\ncommit;\n", capture_output=True, text=True)
    if res.returncode != 0:
        sys.exit("database query failed: " + res.stderr.strip()[:500])
    return [line for line in res.stdout.splitlines() if line.strip()]


def fetch(env, since_ms, until_ms):
    markets = {}
    for line in psql("select jsonb_build_object('id',id,'kind',kind,'symbol',symbol,'maxLeverage',max_leverage,"
                     "'capHourly',cap_hourly,'capDaily',cap_daily,'kappa',kappa::text,'feeBp',herd_fee_bp::text)::text from markets;", env):
        m = json.loads(line)
        markets[m["id"]] = m
    window = "r.window_start_ms >= %d and r.window_start_ms < %d" % (since_ms, until_ms)
    positions = [json.loads(x) for x in psql("""
with bp as (
  select distinct on (g.payload->>'market', g.payload->>'interval', g.payload->>'windowStart', g.payload->>'playerId')
         g.payload->>'market' m, g.payload->>'interval' iv, (g.payload->>'windowStart')::bigint ws, g.payload->>'playerId' pid, g.payload p
  from game_events g
  where g.event_type = 'BetPlaced' and (g.payload->>'windowStart')::bigint >= %d and (g.payload->>'windowStart')::bigint < %d
  order by g.payload->>'market', g.payload->>'interval', g.payload->>'windowStart', g.payload->>'playerId', g.id desc)
select jsonb_build_object('round', r.round_id, 'market', r.market, 'interval', r.interval, 'ws', r.window_start_ms,
       'we', r.window_end_ms, 'player', e->>'playerId', 'bot', coalesce((e->'player'->>'isBot')::boolean, false),
       'dir', e->>'direction', 'lev', e->'leverage', 'stake', e->>'stake', 'payout', e->>'payout',
       'liquidated', e->'liquidated', 'entry', e->>'entryPrice', 'liqPrice', e->>'liquidationPrice', 'size', e->>'size',
       'dirCorrect', e->'directionCorrect', 'mode', e->>'mode', 'bet', bp.p)::text
from round_results r cross join lateral jsonb_array_elements(r.positions_json) e
left join bp on bp.m = r.market and bp.iv = r.interval and bp.ws = r.window_start_ms and bp.pid = e->>'playerId'
where r.status = 'SETTLED' and %s;""" % (since_ms, until_ms, window), env)]
    rounds = {}
    for line in psql("""
select jsonb_build_object('market', x.market, 'interval', x.interval, 'ws', x.ws, 'we', x.we, 'open', x.open, 'close', x.close,
       'venue', x.venue, 'path', x.path, 'bets', x.bets)::text
from (select distinct on (g.payload->>'market', g.payload->>'interval', g.payload->>'windowStart')
             g.payload->>'market' market, g.payload->>'interval' interval, (g.payload->>'windowStart')::bigint ws,
             (g.payload->>'windowEnd')::bigint we, g.payload->>'openPrice' open, g.payload->>'closePrice' close,
             g.payload->>'venue' venue, g.payload->'path' path,
             coalesce((select jsonb_array_length(r.positions_json) from round_results r
                       where r.market = g.payload->>'market' and r.window_start_ms = (g.payload->>'windowStart')::bigint
                         and r.interval = g.payload->>'interval'), 0) bets
      from game_events g
      where g.event_type = 'RoundResolved' and (g.payload->>'windowStart')::bigint >= %d and (g.payload->>'windowStart')::bigint < %d
      order by g.payload->>'market', g.payload->>'interval', g.payload->>'windowStart', g.id desc) x
where x.bets > 0 or x.venue = 'coinbase';""" % (since_ms, until_ms), env):
        rr = json.loads(line)
        rounds[(rr["market"], rr["interval"], rr["ws"])] = rr
    return markets, positions, rounds


# ----------------------------------------------------------------------------------------------------------------- rules

def tier_divisor(stake):
    for upper, divisor in TIER_UPPER:
        if stake <= upper:
            return divisor
    return 10


def herd_rate_bp(market, bet_at_ms):
    if bet_at_ms < FEE_DEPLOY_MS and (market["symbol"] in RATE_CHANGED_SYMBOLS and market["kind"] == "PERP" or market["kind"] == "ODDS"):
        return OLD_RATE_BP
    return D(market["feeBp"])


def expected_fees(market, interval, lev, stake_play, bet_at_ms):
    old = bet_at_ms < FEE_DEPLOY_MS
    k = ONE if (old or interval == "HOURLY") else LONG_K[market["kind"]]
    guard = OLD_GUARD if old else NEW_GUARD
    share = min(guard, k * herd_rate_bp(market, bet_at_ms) / D(10000) * lev)
    herd = (stake_play * share).quantize(D("0.001"), rounding=ROUND_CEILING)
    herd = max(herd, D("0.001"))
    return int(herd * 1000), NETWORK_FEE_MOJOS


FIXED_FEE_RATE = D("0.03")   # Р-106: the platform fee of a fixed-payout bet, 3 % of the stake
FIXED_LEVERAGE = 2           # the placeholder leverage of a fixed-payout bet (Leverage.MIN)


def expected_fixed_fees(stake_play):
    """Platform fee = 3 % of the stake rounded UP to 0.001 PLAY, network fee 0.1 PLAY (mojos)."""
    herd = (stake_play * FIXED_FEE_RATE).quantize(D("0.001"), rounding=ROUND_CEILING)
    return int(herd * 1000), NETWORK_FEE_MOJOS


def expected_fixed_payout(stake_play, side, lot_open, lot_close):
    """Р-106: 2 x stake for the right direction, 0 for the wrong one, the stake back when the close equals the open."""
    if lot_close == lot_open:
        return stake_play
    return stake_play * 2 if (lot_close > lot_open) == (side == 1) else D(0)


def audit_fixed_position(p, bet, rnd, side, stake, lot_open, F):
    """The checks of a fixed-payout position (no size, no liquidation price, no tier; the leverage in the data is the placeholder)."""
    rid, pid = p["round"], p["player"]
    if p.get("mode") != "FIXED":
        F.add(True, "fixed-mode", rid, pid, "BetPlaced says FIXED, the settled position says %s" % p.get("mode"))
    if int(p["lev"]) != FIXED_LEVERAGE or int(bet["leverage"]) != FIXED_LEVERAGE:
        F.add(True, "fixed-leverage", rid, pid, "leverage %s / %s, the placeholder is %d" % (p["lev"], bet["leverage"], FIXED_LEVERAGE))
    if "liqCap" in bet or "liqKappa" in bet:
        F.add(True, "fixed-liquidation-params", rid, pid, "a fixed-payout bet stores no liquidation parameters")
    if bool(p["liquidated"]):
        F.add(True, "fixed-liquidated", rid, pid, "a fixed-payout position cannot be liquidated")
    if "feeHerdMojos" not in bet:
        F.add(True, "fixed-fees-missing", rid, pid, "a fixed-payout bet carries its fees")
        return None
    expected = expected_fixed_payout(stake, side, lot_open, D(rnd["close"]))
    if expected != D(p["payout"]):
        F.add(True, "payout", rid, pid, "expected %s stored %s (fixed, stake %s %s, open %s close %s)" % (
            expected, p["payout"], p["stake"], p["dir"], rnd["open"], rnd["close"]))
    herd, net = int(bet["feeHerdMojos"]), int(bet["feeNetMojos"])
    eh, en = expected_fixed_fees(stake)
    if herd != eh or net != en:
        F.add(True, "fee", rid, pid, "fixed: platform fee expected %d stored %d mojos, network expected %d stored %d" % (eh, herd, en, net))
    return D(herd + net) / 1000


def open_position(stake_play, lev, side, price, rate):
    """Mirror of Position.openFromMargin (+ LiquidationCalculator): returns size, initial margin, liquidation price (lot scale)."""
    size = div(stake_play * lev, price).quantize(D("1E-8"), rounding=ROUND_HALF_UP)
    margin = r2(div(size * price, D(lev)))
    liq = r2(div(size * price - side * margin, size * (ONE - rate * side)))
    return size, margin, liq


def pnl_at(size, price0, side, price):
    return r2(side * size * (price - price0))


def payout_cap(stake_play, treasury_play):
    by_stake = stake_play * 20
    by_treasury = D(int(treasury_play * 1000) // 1000) / 1000  # mojos // 1000 * 1 per mille, back in PLAY
    return min(by_stake, by_treasury).quantize(CENT, rounding=ROUND_DOWN)


def path_outcome(path, side, liq):
    """PositionSimulator: first candle that reaches the liquidation price; returns (liquidated, extreme price)."""
    for c in path:
        if side == 1 and D(c["low"]) <= liq:
            return True, D(c["low"])
        if side == -1 and D(c["high"]) >= liq:
            return True, D(c["high"])
    return False, None


# ----------------------------------------------------------------------------------------------------------------- coinbase

def coinbase_candles(product, start_ms, end_ms):
    out = {}
    t = start_ms // 60000 * 60
    end_s = end_ms // 1000
    while t < end_s:
        last = min(t + 299 * 60, end_s)
        url = "https://api.exchange.coinbase.com/products/%s/candles?granularity=60&start=%d&end=%d" % (product, t, last)
        for attempt in range(5):
            try:
                req = urllib.request.Request(url, headers={"User-Agent": "herd-settlement-audit"})
                rows = json.loads(urllib.request.urlopen(req, timeout=30).read().decode(), parse_float=Decimal)
                break
            except Exception as exc:  # noqa: BLE001 - network is best effort, retried
                if attempt == 4:
                    sys.exit("coinbase %s failed: %s" % (product, exc))
                time.sleep(1.5 * (attempt + 1))
        for tm, low, high, opn, close, _vol in rows:
            out[int(tm) * 1000] = (D(opn), D(high), D(low), D(close))
        t = last + 60
        time.sleep(0.15)
    return out


def bp_diff(a, b):
    return float((a - b) / b * 10000) if b else float("nan")


# ----------------------------------------------------------------------------------------------------------------- audit

class Findings:
    def __init__(self):
        self.hard, self.warn = [], []

    def add(self, hard, code, rnd, player, detail):
        (self.hard if hard else self.warn).append((code, rnd, player, detail))


def audit_positions(markets, positions, rounds, treasury, F):
    rows = []
    for p in positions:
        market = markets.get(p["market"])
        bet = p["bet"]
        rid, pid = p["round"], p["player"]
        if market is None:
            F.add(True, "market-unknown", rid, pid, p["market"])
            continue
        rnd = rounds.get((p["market"], p["interval"], p["ws"]))
        if rnd is None or not rnd["path"]:
            F.add(True, "no-path", rid, pid, "no RoundResolved event or empty path for a round with bets")
            continue
        if bet is None:
            F.add(True, "no-bet-event", rid, pid, "settled position without BetPlaced event")
            continue
        path = rnd["path"]
        side = 1 if p["dir"] == "UP" else -1
        lev = int(p["lev"])
        stake = D(p["stake"])
        legacy = "feeHerdMojos" not in bet
        lot_open = D(rnd["open"])
        entry = D(p["entry"])
        k = round(math.log10(lot_open / entry)) if entry > 0 and lot_open > 0 else 0
        scale = D(10) ** k
        lot_entry = entry * scale
        if lot_entry != lot_open:
            F.add(True, "entry-vs-open", rid, pid, "entry %s x10^%d != round open %s" % (p["entry"], k, rnd["open"]))
        if bet["direction"] != p["dir"] or int(bet["leverage"]) != lev or D(int(bet["stakeMojos"])) / 1000 != stake:
            F.add(True, "bet-vs-position", rid, pid, "BetPlaced %s x%s %s mojos vs position %s x%s %s" % (
                bet["direction"], bet["leverage"], bet["stakeMojos"], p["dir"], lev, p["stake"]))
        if bet["atMs"] >= p["ws"]:
            F.add(True, "bet-after-start", rid, pid, "bet at %d, round starts %d" % (bet["atMs"], p["ws"]))
        if bet.get("mode") == "FIXED" or p.get("mode") == "FIXED":
            fees = audit_fixed_position(p, bet, rnd, side, stake, lot_open, F) if bet.get("mode") == "FIXED" else None
            if bet.get("mode") != "FIXED":
                F.add(True, "fixed-mode", rid, pid, "the settled position says FIXED, BetPlaced says %s" % bet.get("mode"))
            rows.append(dict(market=p["market"], interval=p["interval"], bot=p["bot"], lev=lev, cap=lev, legacy=False, fixed=True,
                             stake=stake, payout=D(p["payout"]), liquidated=False, side=side, fees=fees or D(0), tier=lev,
                             correct=p["dirCorrect"]))
            continue
        # parameters of the liquidation
        has_params = "liqCap" in bet
        if legacy:
            cap, rate = market["maxLeverage"], div(ONE, D(2 * market["maxLeverage"]))
        elif has_params:
            cap, rate = int(bet["liqCap"]), div(D(bet["liqKappa"]), D(int(bet["liqCap"])))
        else:
            cap = market["capHourly"] if p["interval"] == "HOURLY" else market["capDaily"]
            rate = div(D(market["kappa"]), D(cap))
        margin_in = stake  # Position.openFromMargin gets stake.toMoney() (3 dp)
        size, margin, liq = open_position(margin_in, lev, side, lot_open, rate)
        if size != D(p["size"]):
            F.add(True, "size", rid, pid, "expected %s stored %s" % (size, p["size"]))
        liq_stored = D(p["liqPrice"]) * scale
        if liq != liq_stored:
            F.add(not (legacy or not has_params), "liq-price", rid, pid, "expected %s (l=%s/%d) stored %s (lot scale)" % (
                liq, bet.get("liqKappa", market["kappa"]), cap, liq_stored))
        # tier
        if has_params and not legacy:
            allowed = min(cap, max(2, cap // tier_divisor(stake)))
            if lev > allowed or lev < 2:
                F.add(True, "leverage-tier", rid, pid, "leverage %d, stake %s allows %d (cap %d)" % (lev, p["stake"], allowed, cap))
        # simulation from the STORED liquidation price, then payout
        liquidated, extreme = path_outcome(path, side, liq_stored)
        if liquidated != bool(p["liquidated"]):
            F.add(True, "liquidation-flag", rid, pid, "path says %s, stored %s (liq %s, path low %s high %s)" % (
                liquidated, p["liquidated"], liq_stored, min(D(c["low"]) for c in path), max(D(c["high"]) for c in path)))
        close_lot = D(path[-1]["close"])
        if liquidated:
            raw = D(0) if legacy else max(D(0), r2(margin + pnl_at(size, lot_open, side, extreme)))
        else:
            raw = max(D(0), r2(margin + pnl_at(size, lot_open, side, close_lot)))
        expected_payout = raw if legacy else min(raw, payout_cap(stake, treasury))
        if expected_payout != D(p["payout"]):
            F.add(True, "payout", rid, pid, "expected %s stored %s (stake %s x%d %s, %s)" % (
                expected_payout, p["payout"], p["stake"], lev, p["dir"], "liquidated" if liquidated else "closed"))
        # fees
        herd = net = 0
        if not legacy:
            herd, net = int(bet["feeHerdMojos"]), int(bet["feeNetMojos"])
            eh, en = expected_fees(market, p["interval"], lev, stake, bet["atMs"])
            if FEE_OLD_UNTIL_MS <= bet["atMs"] < FEE_DEPLOY_MS and herd == expected_fees(market, p["interval"], lev, stake, 0)[0]:
                eh = herd  # inside the gap between the migration and the server restart: the old rule is as valid as the new
            if herd != eh or net != en:
                F.add(True, "fee", rid, pid, "herd expected %d stored %d mojos, network expected %d stored %d (L=%d %s %s)" % (
                    eh, herd, en, net, lev, p["market"], p["interval"]))
        rows.append(dict(market=p["market"], interval=p["interval"], bot=p["bot"], lev=lev, cap=cap, legacy=legacy,
                         stake=stake, payout=D(p["payout"]), liquidated=bool(p["liquidated"]), side=side,
                         fees=D(herd + net) / 1000, tier=min(cap, max(2, cap // tier_divisor(stake))) if not legacy else cap,
                         correct=p["dirCorrect"]))
    return rows


def audit_rounds(rounds, F):
    for (market, _iv, ws), r in sorted(rounds.items()):
        path = r["path"]
        rid = "%s-%s-%d" % (market, "1h" if r["interval"] == "HOURLY" else "1d", ws)
        if r["bets"] and not path:
            continue  # reported per position
        if not path:
            continue
        if D(path[0]["open"]) != D(r["open"]) or D(path[-1]["close"]) != D(r["close"]):
            F.add(True, "path-vs-round", rid, "-", "path open/close %s/%s, round %s/%s" % (
                path[0]["open"], path[-1]["close"], r["open"], r["close"]))
        for c in path:
            lo, hi, o, cl = D(c["low"]), D(c["high"]), D(c["open"]), D(c["close"])
            if lo > min(o, cl) or hi < max(o, cl):
                F.add(True, "candle-shape", rid, "-", "candle %s: low %s high %s open %s close %s" % (c["openTimeMs"], lo, hi, o, cl))
                break


def band_distance_bp(price, candles):
    """Distance in bp of `price` from the [min low, max high] band of the given Coinbase candles (0 when inside)."""
    lo, hi = min(c[2] for c in candles), max(c[1] for c in candles)
    if price > hi:
        return bp_diff(price, hi)
    if price < lo:
        return bp_diff(lo, price)
    return 0.0


def audit_coinbase(rounds, tol_bp, range_tol_bp, F):
    """The engine price is the mid of the best bid and ask at the round boundary, Coinbase's candle is made of trades, so the
    comparison is: the round open lies in the band of the candle that ends and the one that starts at the boundary (low..high),
    likewise the close; the engine path range does not stick out of the Coinbase range of the whole round by more than range_tol_bp.
    The median distance to the candle open / close is reported as well."""
    cb_rounds = [r for r in rounds.values() if r["venue"] == "coinbase" and r["path"]]
    stats = defaultdict(lambda: {"n": 0, "open": [], "close": [], "band": [], "range_over": 0, "skipped": 0})
    by_market = defaultdict(list)
    for r in cb_rounds:
        by_market[r["market"]].append(r)
    for market, rs in sorted(by_market.items()):
        product = market.split("_", 1)[1] + "-USD"
        candles = coinbase_candles(product, min(r["ws"] for r in rs) - 60000, max(r["we"] for r in rs) + 60000)
        for r in rs:
            st = stats[market]
            minutes = [r["ws"] + 60000 * i for i in range((r["we"] - r["ws"]) // 60000)]
            cs = [candles.get(m) for m in minutes]
            have = [c for c in cs if c]
            rid = "%s-%s-%d" % (market, "1h" if r["interval"] == "HOURLY" else "1d", r["ws"])
            if not have or cs[0] is None or cs[-1] is None:
                st["skipped"] += 1
                continue
            st["n"] += 1
            # the engine prices are "lot prices" (the real price x 10^k, k per market): bring them to the Coinbase scale
            scale = D(10) ** round(math.log10(D(r["open"]) / cs[0][0]))
            p_open, p_close = D(r["open"]) / scale, D(r["close"]) / scale
            around_open = [c for c in (candles.get(r["ws"] - 60000), cs[0]) if c]
            around_close = [c for c in (cs[-1], candles.get(r["we"])) if c]
            d_open, d_close = band_distance_bp(p_open, around_open), band_distance_bp(p_close, around_close)
            st["open"].append(abs(bp_diff(p_open, cs[0][0])))
            st["close"].append(abs(bp_diff(p_close, cs[-1][3])))
            st["band"] += [d_open, d_close]
            if d_open > tol_bp or d_close > tol_bp:
                F.add(True, "coinbase-price", rid, "-", "open %s (%.1f bp outside the candles %s), close %s (%.1f bp outside %s)" % (
                    p_open, d_open, [(str(c[2]), str(c[1])) for c in around_open], p_close, d_close,
                    [(str(c[2]), str(c[1])) for c in around_close]))
            e_lo, e_hi = min(D(c["low"]) for c in r["path"]) / scale, max(D(c["high"]) for c in r["path"]) / scale
            c_lo, c_hi = min(c[2] for c in have), max(c[1] for c in have)
            over_hi, over_lo = bp_diff(e_hi, c_hi), bp_diff(c_lo, e_lo)
            if over_hi > range_tol_bp or over_lo > range_tol_bp:
                st["range_over"] += 1
                F.add(True, "coinbase-range", rid, "-", "engine range %s..%s, coinbase %s..%s (sticks out %.1f bp up, %.1f bp down)" % (
                    e_lo, e_hi, c_lo, c_hi, over_hi, over_lo))
    return stats


# ----------------------------------------------------------------------------------------------------------------- report

def pct(a, b):
    return "%5.1f%%" % (100.0 * a / b) if b else "    - "


def median(xs):
    xs = sorted(xs)
    return xs[len(xs) // 2] if xs else float("nan")


def summary(rows, cb_stats, out):
    out.append("")
    out.append("## Liquidation rate by market, round length and leverage")
    out.append("")
    out.append("bucket: `cap` = leverage equal to the tier maximum of its stake, `near` = 80-99 % of it, `low` = below 80 %.")
    out.append("")
    out.append("| market | round | bucket | positions | liquidated | win (payout > stake) | net return (payout / (stake+fees)) |")
    out.append("|---|---|---|---:|---:|---:|---:|")
    groups = defaultdict(list)
    for r in rows:
        if r.get("fixed"):
            continue  # no leverage: the fixed-payout positions have their own table below
        ratio = r["lev"] / r["tier"] if r["tier"] else 0
        bucket = "cap" if r["lev"] >= r["tier"] else ("near" if ratio >= 0.8 else "low")
        groups[(r["market"], r["interval"], bucket)].append(r)
    order = {"cap": 0, "near": 1, "low": 2}
    for key in sorted(groups, key=lambda k: (k[0], k[1], order[k[2]])):
        g = groups[key]
        n = len(g)
        stake = sum(x["stake"] + x["fees"] for x in g)
        out.append("| %s | %s | %s | %d | %s | %s | %s |" % (
            key[0], INTERVALS[key[1]], key[2], n, pct(sum(x["liquidated"] for x in g), n),
            pct(sum(x["payout"] > x["stake"] for x in g), n),
            "%.4f" % (sum(x["payout"] for x in g) / stake) if stake else "-"))
    out.append("")
    out.append("## Win rate: people against bots")
    out.append("")
    out.append("| group | positions | liquidated | win (payout > stake) | stake back (payout = stake) | loss | direction right | net return |")
    out.append("|---|---:|---:|---:|---:|---:|---:|---:|")
    for name, flt in (("people", lambda r: not r["bot"]), ("bots", lambda r: r["bot"]),
                      ("people at the tier cap", lambda r: not r["bot"] and not r.get("fixed") and r["lev"] >= r["tier"]),
                      ("bots at the tier cap", lambda r: r["bot"] and not r.get("fixed") and r["lev"] >= r["tier"])):
        g = [r for r in rows if flt(r)]
        n = len(g)
        decided = [r for r in g if r["correct"] is not None]
        stake = sum(x["stake"] + x["fees"] for x in g)
        out.append("| %s | %d | %s | %s | %s | %s | %s | %s |" % (
            name, n, pct(sum(x["liquidated"] for x in g), n), pct(sum(x["payout"] > x["stake"] for x in g), n),
            pct(sum(x["payout"] == x["stake"] for x in g), n), pct(sum(x["payout"] < x["stake"] for x in g), n),
            pct(sum(1 for x in decided if x["correct"]), len(decided)),
            "%.4f" % (sum(x["payout"] for x in g) / stake) if stake else "-"))
    fixed_rows = [r for r in rows if r.get("fixed")]
    if fixed_rows:
        out.append("")
        out.append("## Fixed-payout (Win or lose) positions")
        out.append("")
        out.append("| market | round | positions | win (2 x stake) | tie (stake back) | loss (0) | fees kept | net return (payout / (stake+fees)) |")
        out.append("|---|---|---:|---:|---:|---:|---:|---:|")
        by = defaultdict(list)
        for r in fixed_rows:
            by[(r["market"], r["interval"])].append(r)
        for key in sorted(by):
            g = by[key]
            n = len(g)
            stake = sum(x["stake"] + x["fees"] for x in g)
            out.append("| %s | %s | %d | %s | %s | %s | %s | %s |" % (
                key[0], INTERVALS[key[1]], n, pct(sum(x["payout"] > x["stake"] for x in g), n),
                pct(sum(x["payout"] == x["stake"] for x in g), n), pct(sum(x["payout"] < x["stake"] for x in g), n),
                "%.3f" % sum(x["fees"] for x in g), "%.4f" % (sum(x["payout"] for x in g) / stake) if stake else "-"))
    if cb_stats:
        out.append("")
        out.append("## Round prices against Coinbase 1-minute candles (basis points)")
        out.append("")
        out.append("| market | rounds compared | skipped (missing minute) | distance from candle open: median / max | from candle close: median / max | outside the candle bands: max | engine range sticks out |")
        out.append("|---|---:|---:|---:|---:|---:|---:|")
        for m, s in sorted(cb_stats.items()):
            out.append("| %s | %d | %d | %.2f / %.2f | %.2f / %.2f | %.2f | %d |" % (
                m, s["n"], s["skipped"], median(s["open"]), max(s["open"] or [0]), median(s["close"]), max(s["close"] or [0]),
                max(s["band"] or [0]), s["range_over"]))


def parse_time(text):
    text = text.strip()
    now = int(time.time() * 1000)
    if text[-1] in "hdm" and text[:-1].isdigit():
        return now - int(text[:-1]) * {"h": 3600_000, "d": 86400_000, "m": 60_000}[text[-1]]
    if text.isdigit():
        return int(text)
    dt = datetime.fromisoformat(text.replace("Z", "+00:00"))
    if dt.tzinfo is None:
        dt = dt.replace(tzinfo=timezone.utc)
    return int(dt.timestamp() * 1000)


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--since", default="24h", help="start of the range: 24h, 72h, 7d, an ISO time (UTC) or epoch ms (default 24h)")
    ap.add_argument("--until", default=None, help="end of the range (default now)")
    ap.add_argument("--no-coinbase", action="store_true", help="skip the comparison with Coinbase candles")
    ap.add_argument("--tol-bp", type=float, default=10.0, help="Coinbase price tolerance in basis points (default 10)")
    ap.add_argument("--range-tol-bp", type=float, default=25.0,
                    help="how far the engine path range may stick out of the Coinbase range, bp (default 25: the engine uses the "
                         "bid/ask mid, Coinbase candles are trades, which differ most on DOGE-USD; observed max 13 bp)")
    ap.add_argument("--treasury", type=int, default=int(TREASURY_FALLBACK_PLAY),
                    help="treasury in whole PLAY for the 0.1 %% payout cap (default: the engine fallback 99 998 100)")
    ap.add_argument("--max-list", type=int, default=60, help="mismatches listed per kind (default 60)")
    ap.add_argument("--save-data", help="write the fetched data to this json file")
    ap.add_argument("--load-data", help="read the data from a file written by --save-data instead of the database")
    a = ap.parse_args()
    since = parse_time(a.since)
    until = parse_time(a.until) if a.until else int(time.time() * 1000)
    if a.load_data:
        with open(a.load_data) as fh:
            blob = json.load(fh)
        markets, positions = blob["markets"], blob["positions"]
        since, until = blob.get("since", since), blob.get("until", until)
        rounds = {(r["market"], r["interval"], r["ws"]): r for r in blob["rounds"]}
    else:
        markets, positions, rounds = fetch(load_env(), since, until)
        if a.save_data:
            with open(a.save_data, "w") as fh:
                json.dump({"since": since, "until": until, "markets": markets, "positions": positions, "rounds": list(rounds.values())}, fh)
    F = Findings()
    rows = audit_positions(markets, positions, rounds, D(a.treasury), F)
    audit_rounds(rounds, F)
    cb = {} if a.no_coinbase else audit_coinbase(rounds, a.tol_bp, a.range_tol_bp, F)

    iso = lambda ms: datetime.fromtimestamp(ms / 1000, timezone.utc).strftime("%Y-%m-%d %H:%M UTC")
    out = ["# Settlement audit", "",
           "range: %s .. %s" % (iso(since), iso(until)),
           "positions checked: %d (people %d, bots %d; legacy %d); rounds with a path: %d" % (
               len(positions), sum(1 for r in rows if not r["bot"]), sum(1 for r in rows if r["bot"]),
               sum(1 for r in rows if r["legacy"]), len(rounds)),
           "hard mismatches: %d; warnings: %d" % (len(F.hard), len(F.warn))]
    for title, items in (("Mismatches", F.hard), ("Warnings (parameters the bet did not store)", F.warn)):
        if not items:
            continue
        out += ["", "## " + title, ""]
        kinds = defaultdict(list)
        for it in items:
            kinds[it[0]].append(it)
        for code, lst in sorted(kinds.items()):
            out.append("- %s: %d" % (code, len(lst)))
        for code, lst in sorted(kinds.items()):
            out += ["", "### %s" % code]
            for _c, rid, pid, detail in lst[:a.max_list]:
                out.append("- round %s player %s: %s" % (rid, pid, detail))
            if len(lst) > a.max_list:
                out.append("- ... %d more" % (len(lst) - a.max_list))
    summary(rows, cb, out)
    print("\n".join(out))
    sys.exit(1 if F.hard else 0)


if __name__ == "__main__":
    main()
