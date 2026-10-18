#!/usr/bin/env python3
"""Funnel of the human players of the Herd stand, with the honest "played" metric.

Read-only (ssh + psql inside a READ ONLY transaction, same mechanism and .local/vps.env as settlement_audit.py). Python 3 stdlib only.
Humans only (players.is_bot = false). Cohort = players created in the period.

  entered             players created in the period
  bet >= 1            at least one BetPlaced event
  >= 3 / >= 5 rounds  old metric: rows in player_round_history (settled rounds with a bet; a probe of 15 bets in one minute counts 15)
  played (new)        bets in at least 5 different 3-minute windows (bet time, UTC) on at least 2 different UTC days;
                      only bets of settled rounds count (a cancelled round is not played)
                      (docs/research/features_growth_2026-10-18.md section 4)
  returned            a bet on a UTC day later than the UTC day of the player's first bet

  tools/audit/funnel.py                       # cohort: created since T0 (2026-10-11 17:18 UTC) until now
  tools/audit/funnel.py --hours 72            # created in the last 72 h
  tools/audit/funnel.py --since 2026-10-11T17:18Z --exclude <player_id>,<player_id> --detail
"""
import argparse
import json
import os
import sys
import time
from datetime import datetime, timezone

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from settlement_audit import load_env, psql  # noqa: E402

T0_MS = 1791739080000  # 2026-10-11 17:18 UTC, the first post (docs/product/feedback/wave1-log.md)
WINDOW_MS = 180000
DAY_MS = 86400000
MIN_WINDOWS, MIN_DAYS = 5, 2

SQL_PLAYERS = ("select jsonb_build_object('id', player_id, 'created', created_at_ms)::text from players "
               "where not is_bot and created_at_ms >= %d and created_at_ms < %d;")
SQL_BETS = """
with bp as (
  select distinct on (g.payload->>'market', g.payload->>'interval', g.payload->>'windowStart', g.payload->>'playerId')
         g.payload->>'market' m, g.payload->>'interval' iv, (g.payload->>'windowStart')::bigint ws, g.payload->>'playerId' pid,
         coalesce((g.payload->>'atMs')::bigint, g.at_ms) at_ms
  from game_events g join players p on p.player_id = g.payload->>'playerId' and not p.is_bot
  where g.event_type = 'BetPlaced'
  order by g.payload->>'market', g.payload->>'interval', g.payload->>'windowStart', g.payload->>'playerId', g.id desc)
select jsonb_build_object('pid', bp.pid, 'at', bp.at_ms, 'settled', h.round_id is not null)::text
from bp
left join round_results r on r.market = bp.m and r.interval = bp.iv and r.window_start_ms = bp.ws
left join player_round_history h on h.round_id = r.round_id and h.player_id = bp.pid;
"""


def ts(ms):
    return datetime.fromtimestamp(ms / 1000, timezone.utc).strftime("%Y-%m-%d %H:%M")


def parse_since(v):
    if v.isdigit():
        return int(v)
    return int(datetime.fromisoformat(v.replace("Z", "+00:00")).replace(tzinfo=timezone.utc).timestamp() * 1000)


def analyse(players, bets, excluded):
    per = {p["id"]: dict(created=p["created"], bets=[], settled=0) for p in players if p["id"] not in excluded}
    for b in bets:
        p = per.get(b["pid"])
        if p is None:
            continue
        p["bets"].append(b["at"])
        p["settled"] += 1 if b["settled"] else 0
    res = dict(entered=len(per), bet=0, r3=0, r5=0, played=0, returned=0)
    detail = []
    for pid, p in per.items():
        ats = sorted(p["bets"])
        sat = [b["at"] for b in bets if b["pid"] == pid and b["settled"]]
        windows = {a // WINDOW_MS for a in sat}
        days = {a // DAY_MS for a in sat}
        played = len(windows) >= MIN_WINDOWS and len(days) >= MIN_DAYS
        returned = bool(ats) and any(a // DAY_MS > ats[0] // DAY_MS for a in ats)
        res["bet"] += bool(ats)
        res["r3"] += p["settled"] >= 3
        res["r5"] += p["settled"] >= 5
        res["played"] += played
        res["returned"] += returned
        detail.append((p["created"], len(ats), p["settled"], len(windows), len(days), played, returned))
    detail.sort()
    return res, detail


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--hours", type=float, help="cohort: players created in the last N hours")
    ap.add_argument("--since", help="cohort start: ISO time (UTC) or epoch ms (default T0)")
    ap.add_argument("--until", help="cohort end (default now)")
    ap.add_argument("--exclude", default="", help="comma-separated player ids to leave out (our test players)")
    ap.add_argument("--detail", action="store_true", help="one anonymous line per player")
    ap.add_argument("--out", help="write Markdown here (default stdout)")
    a = ap.parse_args()
    now = int(time.time() * 1000)
    until = parse_since(a.until) if a.until else now
    since = now - int(a.hours * 3600 * 1000) if a.hours else (parse_since(a.since) if a.since else T0_MS)
    env = load_env()
    pre = "set default_transaction_read_only = on;\n"
    players = [json.loads(x) for x in psql(pre + SQL_PLAYERS % (since, until), env) if x.startswith("{")]
    bets = [json.loads(x) for x in psql(pre + SQL_BETS, env) if x.startswith("{")]
    res, detail = analyse(players, bets, {x for x in a.exclude.split(",") if x})
    lines = ["Когорта: люди (`is_bot = false`), созданные %s — %s UTC; расчёт сделан %s UTC." %
             (ts(since), ts(until), datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M:%S")),
             "Исключено по `--exclude`: %d." % len([x for x in a.exclude.split(",") if x]), "",
             "| Вошли | Поставили | ≥3 раундов | ≥5 раундов (старая) | Играли (новая: ≥5 окон по 3 мин в ≥2 днях UTC) | Вернулись (ставка в другой день UTC) |",
             "|---|---|---|---|---|---|",
             "| %(entered)d | %(bet)d | %(r3)d | %(r5)d | %(played)d | %(returned)d |" % res, ""]
    if a.detail:
        lines += ["| № | Создан (UTC) | Ставок | Ставок в рассчитанных раундах | 3-мин окон | Дней UTC | Играл | Вернулся |", "|---|---|---|---|---|---|---|---|"]
        for i, d in enumerate(detail, 1):
            lines.append("| %d | %s | %d | %d | %d | %d | %s | %s |" % (i, ts(d[0]), d[1], d[2], d[3], d[4], "да" if d[5] else "нет", "да" if d[6] else "нет"))
        lines.append("")
    md = "\n".join(lines) + "\n"
    if a.out:
        open(a.out, "w").write(md)
    else:
        sys.stdout.write(md)


if __name__ == "__main__":
    main()
