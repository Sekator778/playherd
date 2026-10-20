#!/usr/bin/env python3
"""Offline tests of bot_stats.py: the split by payout mode (Р-106) and the Wilson / Newcombe columns inside each mode.

Run: python3 -m unittest tools/audit/test_bot_stats.py   (no network, no database)
"""
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import bot_stats as stats  # noqa: E402


def row(s, mode, n, w, iv="HOURLY", bots=1, lev=None):
    return {"s": s, "iv": iv, "mode": mode, "bots": bots, "n": n, "w": w, "d": n, "liq": 0, "lev": n * 2 if lev is None else lev,
            "pnl": 0, "st": n * 100000, "fee": 0, "nofee": 0}


ROWS = [row("coin-1", "FIXED", 100, 50), row("momentum-5", "FIXED", 100, 70),
        row("coin-1", "PERCENTAGE", 100, 50), row("momentum-5", "PERCENTAGE", 100, 52),
        {k: v for k, v in row("rsi-14", None, 10, 5).items() if k != "mode"}]  # a row without a mode is a percentage-mode row


class BotStatsModeTest(unittest.TestCase):
    def test_rows_are_split_by_mode(self):
        by = stats.split_by_mode(ROWS)
        self.assertEqual({r["s"] for r in by["FIXED"]}, {"coin-1", "momentum-5"})
        self.assertEqual({r["s"] for r in by["PERCENTAGE"]}, {"coin-1", "momentum-5", "rsi-14"})

    def test_each_mode_has_its_own_numbers_and_its_own_coin_control(self):
        by = stats.split_by_mode(ROWS)
        fixed = {(r["level"], r["name"], r["iv"]): r for r in stats.build(by["FIXED"], ["HOURLY"])}
        pct = {(r["level"], r["name"], r["iv"]): r for r in stats.build(by["PERCENTAGE"], ["HOURLY"])}
        self.assertEqual(fixed[("family", "momentum", "HOURLY")]["bets"], 100)
        self.assertEqual(fixed[("family", "momentum", "HOURLY")]["wins"], 70)
        self.assertEqual(pct[("family", "momentum", "HOURLY")]["wins"], 52)
        # 70 % against the coin's 50 % excludes zero in FIXED, 52 % does not in PERCENTAGE
        self.assertEqual(fixed[("family", "momentum", "HOURLY")]["verdict"], "отличима от монетки")
        self.assertEqual(pct[("family", "momentum", "HOURLY")]["verdict"], "нет")
        self.assertEqual(fixed[("family", "coin", "HOURLY")]["verdict"], "контроль")
        # Wilson and Newcombe columns are filled
        win = fixed[("family", "momentum", "HOURLY")]["win"]
        self.assertAlmostEqual(win[0], 0.70)
        self.assertLess(win[1], win[0])
        self.assertGreater(win[2], win[0])
        self.assertEqual(len(fixed[("family", "momentum", "HOURLY")]["diff"]), 3)

    def test_markdown_has_a_block_per_mode_and_no_leverage_in_fixed(self):
        by = stats.split_by_mode(ROWS)
        outs = {m: stats.build(r, ["HOURLY"]) for m, r in by.items()}
        md = stats.markdown(outs, dict(since="a", until="b", hours=72, at="c"))
        self.assertIn("Режим: FIXED", md)
        self.assertIn("Режим: PERCENTAGE", md)
        self.assertLess(md.index("Режим: FIXED"), md.index("Режим: PERCENTAGE"))
        fixed_rows = [r for r in outs["FIXED"] if r["level"] == "family" and r["name"] == "momentum" and r["iv"] == "HOURLY"]
        cells = stats.fmt_row(fixed_rows[0], "FIXED")
        self.assertEqual(cells[7], "—")  # liquidations
        self.assertEqual(cells[8], "—")  # average leverage
        pct_cells = stats.fmt_row([r for r in outs["PERCENTAGE"] if r["level"] == "family" and r["name"] == "momentum"
                                   and r["iv"] == "HOURLY"][0], "PERCENTAGE")
        self.assertNotEqual(pct_cells[8], "—")

    def test_the_query_groups_by_mode(self):
        self.assertIn("'mode', h.mode", stats.SQL)
        self.assertIn("group by p.bot_strategy, h.interval, h.mode", stats.SQL)


if __name__ == "__main__":
    unittest.main()
