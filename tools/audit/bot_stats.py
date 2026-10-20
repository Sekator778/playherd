#!/usr/bin/env python3
"""Internal statistics per bot strategy of the Herd stand (not shown to players).

Read-only (ssh + psql inside a READ ONLY transaction, same mechanism and .local/vps.env as settlement_audit.py).
Python 3 standard library only.

Everything is split by payout mode (Р-106): FIXED ("Win or lose") and PERCENTAGE, each with its own tables, its own `coin` control
and the same Wilson / Newcombe columns. The mode is `player_round_history.mode` (V18). In FIXED there is no leverage and no
liquidation, so those two columns are blank there. The split is internal (Р-105 п. 3), not shown to players.

Per strategy family (bollinger, rsi, ema, coin, donchian, momentum, crowd-follow, crowd-contrarian, legacy names) and per
full strategy name, per round length (3 min = HOURLY, 15 min = DAILY) and for both together:
bots, bets, decided bets, win % with a 95 % Wilson interval, difference vs the `coin` control (same round length) in
percentage points with a Newcombe interval, liquidation share, average leverage, PnL before fees, fees, PnL after fees,
staked, return on stake after fees, verdict (the interval of the difference excludes 0 or not).

  tools/audit/bot_stats.py                       # last 72 h, Markdown to stdout
  tools/audit/bot_stats.py --hours 24 --out stats.md --csv stats.csv

Source: player_round_history (settled positions) joined to players (is_bot, bot_strategy), round_results (window start) and
the BetPlaced event (feeHerdMojos, feeNetMojos). 1 PLAY = 1000 mojo. A tie keeps the fees; a cancelled round has no history row.
"""
import argparse
import csv
import io
import json
import math
import os
import sys
import time
from datetime import datetime, timezone

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from settlement_audit import load_env, psql  # noqa: E402

Z = 1.959964
MOJO_PER_PLAY = 1000
IV_NAME = {"HOURLY": "3 мин", "DAILY": "15 мин"}
CONTROL = "coin"
MODES = ("FIXED", "PERCENTAGE")

SQL = """
with bp as (
  select distinct on (g.payload->>'market', g.payload->>'interval', g.payload->>'windowStart', g.payload->>'playerId')
         g.payload->>'market' m, g.payload->>'interval' iv, (g.payload->>'windowStart')::bigint ws, g.payload->>'playerId' pid,
         coalesce((g.payload->>'feeHerdMojos')::bigint, 0) + coalesce((g.payload->>'feeNetMojos')::bigint, 0) fee
  from game_events g
  where g.event_type = 'BetPlaced' and g.at_ms >= %(lo)d and g.at_ms < %(hi)d
  order by g.payload->>'market', g.payload->>'interval', g.payload->>'windowStart', g.payload->>'playerId', g.id desc)
select jsonb_build_object('s', p.bot_strategy, 'iv', h.interval, 'mode', h.mode, 'bots', count(distinct h.player_id), 'n', count(*),
  'w', count(*) filter (where h.direction_correct), 'd', count(*) filter (where h.direction_correct is not null),
  'liq', count(*) filter (where h.liquidated), 'lev', sum(h.leverage), 'pnl', sum(h.payout_mojos - h.stake_mojos),
  'st', sum(h.stake_mojos), 'fee', coalesce(sum(bp.fee), 0), 'nofee', count(*) filter (where bp.fee is null))::text
from player_round_history h
join players p on p.player_id = h.player_id
join round_results r on r.round_id = h.round_id
left join bp on bp.pid = h.player_id and bp.m = h.market and bp.iv = h.interval and bp.ws = r.window_start_ms
where p.is_bot and h.settled_at_ms >= %(since)d and h.settled_at_ms < %(until)d
group by p.bot_strategy, h.interval, h.mode;
"""


def family(strategy):
    s = (strategy or "unknown").lower().replace("_", "-")
    for f in ("crowd-follow", "crowd-contrarian"):
        if s.startswith(f):
            return f
    return s.split("-")[0] if s[:1].isalpha() else s


def wilson(w, n):
    if n == 0:
        return None
    p = w / n
    den = 1 + Z * Z / n
    c = (p + Z * Z / (2 * n)) / den
    h = Z * math.sqrt(p * (1 - p) / n + Z * Z / (4 * n * n)) / den
    return p, c - h, c + h


def newcombe(a, b):
    """95 % interval of p_a - p_b from the two Wilson intervals (Newcombe hybrid score, method 10)."""
    if a is None or b is None:
        return None
    pa, la, ua = a
    pb, lb, ub = b
    d = pa - pb
    return d, d - math.sqrt((pa - la) ** 2 + (ub - pb) ** 2), d + math.sqrt((ua - pa) ** 2 + (pb - lb) ** 2)


def add(t, r):
    for k in ("bots", "n", "w", "d", "liq", "lev", "pnl", "st", "fee", "nofee"):
        t[k] = t.get(k, 0) + r[k]


def empty():
    return {k: 0 for k in ("bots", "n", "w", "d", "liq", "lev", "pnl", "st", "fee", "nofee")}


def play(m):
    return m / MOJO_PER_PLAY


def pct(x):
    return "%.1f %%" % (100 * x)


def build(rows, ivs):
    """rows: raw dicts; returns list of dict rows for family and strategy levels."""
    fam, strat = {}, {}
    for r in rows:
        for level, key in ((fam, family(r["s"])), (strat, r["s"])):
            for iv in (r["iv"], "ALL"):
                add(level.setdefault((key, iv), empty()), r)
                if iv == "ALL":
                    continue
    # bots in a family across intervals: distinct count is not additive, take the max of the per-interval counts
    for level in (fam, strat):
        for (key, iv), t in level.items():
            if iv == "ALL":
                t["bots"] = max([level[(key, i)]["bots"] for i in ivs if (key, i) in level] or [0])
    out = []
    for lname, level in (("family", fam), ("strategy", strat)):
        for (key, iv), t in sorted(level.items(), key=lambda kv: (kv[0][1] != "ALL", kv[0][1], kv[0][0])):
            ctl = fam.get((CONTROL, iv))
            wi = wilson(t["w"], t["d"])
            ci = wilson(ctl["w"], ctl["d"]) if ctl else None
            diff = newcombe(wi, ci) if not (lname == "family" and key == CONTROL) else None
            after = t["pnl"] - t["fee"]
            if key == CONTROL and lname == "family":
                verdict = "контроль"
            elif diff is None:
                verdict = "нет данных"
            else:
                verdict = "отличима от монетки" if (diff[1] > 0 or diff[2] < 0) else "нет"
            out.append(dict(level=lname, name=key, iv=iv, bots=t["bots"], bets=t["n"], decided=t["d"], wins=t["w"],
                            win=wi, diff=diff, liq=t["liq"], liq_share=t["liq"] / t["n"] if t["n"] else None,
                            avg_lev=t["lev"] / t["n"] if t["n"] else None, pnl_before=play(t["pnl"]), fees=play(t["fee"]),
                            pnl_after=play(after), staked=play(t["st"]), ret=after / t["st"] if t["st"] else None,
                            verdict=verdict, nofee=t["nofee"]))
    return out


def split_by_mode(rows):
    """Raw rows -> {mode: rows}; a row without a mode is a percentage-mode row (the column default)."""
    by = {m: [] for m in MODES}
    for r in rows:
        by.setdefault(r.get("mode") or "PERCENTAGE", []).append(r)
    return by


def fmt_row(r, mode="PERCENTAGE"):
    w = r["win"]
    win = "%s [%s; %s]" % (pct(w[0]), pct(w[1]), pct(w[2])) if w else "—"
    d = r["diff"]
    diff = "%+.1f [%+.1f; %+.1f]" % (100 * d[0], 100 * d[1], 100 * d[2]) if d else "—"
    return [r["name"], IV_NAME.get(r["iv"], "обе"), str(r["bots"]), str(r["bets"]), str(r["decided"]), win, diff,
            "%d (%s)" % (r["liq"], pct(r["liq_share"])) if r["liq_share"] is not None and mode != "FIXED" else "—",
            "%.1f" % r["avg_lev"] if r["avg_lev"] is not None and mode != "FIXED" else "—",
            "%.1f" % r["pnl_before"], "%.1f" % r["fees"], "%.1f" % r["pnl_after"], "%.0f" % r["staked"],
            pct(r["ret"]) if r["ret"] is not None else "—", r["verdict"]]


MODE_TITLE = {"FIXED": "FIXED («Выиграл или проиграл»: 2 x ставка или 0, без плеча и ликвидации)",
              "PERCENTAGE": "PERCENTAGE (ставка x плечо x движение цены)"}

HEAD = ["Группа", "Раунд", "Ботов", "Ставок", "Решённых", "Угадал, % [95 % Уилсон]", "Разница с монеткой, п.п. [95 %]",
        "Ликвидаций", "Ср. плечо", "PnL до комиссий", "Комиссии", "PnL после комиссий", "Поставлено", "Доходность после комиссий",
        "Вердикт"]


def markdown(outs, meta):
    """outs: {mode: rows of build()}; the same tables once per payout mode, FIXED first."""
    lines = ["Период: %s — %s UTC (%d ч), расчёт сделан %s UTC. Суммы в PLAY (1 PLAY = 1000 mojo). Источник: `player_round_history`, "
             "`players`, `round_results`, событие `BetPlaced`; только боты (`is_bot`). «Угадал» — доля `direction_correct` среди "
             "решённых ставок (ничья и возвраты не входят). Разница с монеткой — интервал Ньюкомба из двух интервалов Уилсона, контроль — "
             "семейство `coin` того же раунда и того же режима выплаты (`player_round_history.mode`)." % (
                 meta["since"], meta["until"], meta["hours"], meta["at"]), ""]
    for mode in (m for m in MODES if m in outs):
        lines += ["# Режим: %s" % MODE_TITLE[mode], ""]
        for lname, title in (("family", "По семействам"), ("strategy", "По полным именам стратегий")):
            lines += ["## %s — %s" % (title, mode), "", "| " + " | ".join(HEAD) + " |", "|" + "---|" * len(HEAD)]
            for r in outs[mode]:
                if r["level"] == lname:
                    lines.append("| " + " | ".join(fmt_row(r, mode)) + " |")
            lines.append("")
    lines.append("Ставки одного бота зависимы друг от друга, интервалы считают их независимыми; на деле интервалы уже, чем показано "
                 "[вывод: ставки одного бота повторяют его правило, а не независимые испытания].")
    lines.append("Строк с вердиктом — десятки; при уровне 95 % часть «отличима» выпадает случайно [вывод: 5 % от числа проверок]; "
                 "вердикт читать вместе с числом ставок и повторяемостью на другом периоде.")
    lines.append("В режиме FIXED нет плеча и ликвидации: столбцы «Ликвидаций» и «Ср. плечо» пусты. Статистика внутренняя (Р-105 п. 3).")
    miss = sum(r["nofee"] for out in outs.values() for r in out if r["level"] == "family" and r["iv"] == "ALL")
    if miss:
        lines.append("Ставок без события `BetPlaced` (комиссия принята за 0): %d." % miss)
    return "\n".join(lines) + "\n"


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--hours", type=float, default=72)
    ap.add_argument("--out", help="write Markdown here (default stdout)")
    ap.add_argument("--csv", help="write CSV here")
    a = ap.parse_args()
    env = load_env()
    until = int(time.time() * 1000)
    since = until - int(a.hours * 3600 * 1000)
    # a bet is placed at most a round (15 min) plus a day before it settles; look back a day for the event
    sql = "set transaction read only;\nset default_transaction_read_only = on;\n" + SQL % dict(
        lo=since - 2 * 3600 * 1000, hi=until, since=since, until=until)
    rows = [json.loads(x) for x in psql(sql, env) if x.startswith("{")]
    ivs = sorted({r["iv"] for r in rows})
    outs = {mode: build(mrows, ivs) for mode, mrows in split_by_mode(rows).items()}
    f = lambda ms: datetime.fromtimestamp(ms / 1000, timezone.utc).strftime("%Y-%m-%d %H:%M")
    meta = dict(since=f(since), until=f(until), hours=a.hours, at=datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M:%S"))
    md = markdown(outs, meta)
    if a.out:
        with open(a.out, "w") as fh:
            fh.write(md)
    else:
        sys.stdout.write(md)
    if a.csv:
        with open(a.csv, "w", newline="") as fh:
            w = csv.writer(fh)
            w.writerow(["mode", "level", "name", "interval", "bots", "bets", "decided", "wins", "win_pct", "win_lo", "win_hi",
                        "diff_pp", "diff_lo", "diff_hi", "liquidated", "liq_share", "avg_leverage", "pnl_before_play",
                        "fees_play", "pnl_after_play", "staked_play", "return_after_fees", "verdict"])
            for mode, out in outs.items():
                for r in out:
                    wi, d = r["win"] or (None,) * 3, r["diff"] or (None,) * 3
                    w.writerow([mode, r["level"], r["name"], r["iv"], r["bots"], r["bets"], r["decided"], r["wins"], *wi,
                                *[None if x is None else 100 * x for x in d], r["liq"], r["liq_share"], r["avg_lev"],
                                r["pnl_before"], r["fees"], r["pnl_after"], r["staked"], r["ret"], r["verdict"]])


if __name__ == "__main__":
    main()
