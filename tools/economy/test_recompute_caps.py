"""Unit tests for recompute_caps.py: python3 -I -m unittest   (run from tools/economy)."""
import json
import math
import os
import random
import tempfile
import unittest

import recompute_caps as rc

MIN = rc.MINUTE


def walk_rows(n_minutes, sigma_min, seed, start=0):
    """1-minute candles of a driftless random walk in log price (constant volatility); each minute's high/low are the
    endpoints, so a window's extremes are exactly the extremes of the discrete walk."""
    rnd = random.Random(seed)
    price = 100.0
    rows = []
    for i in range(n_minutes):
        o = price
        price = o * math.exp(rnd.gauss(0, sigma_min))
        rows.append([start + i * MIN, o, max(o, price), min(o, price), price])
    return rows


def Phi(z):
    return 0.5 * (1 + math.erf(z / math.sqrt(2)))


def theoretical_pliq(L, cap, sigma_min, T, kappa=rc.KAPPA):
    """Reflection principle for a driftless walk, with the Broadie-Glasserman shift 0.5826 sigma for discrete monitoring."""
    dl, us = rc.liq_levels(L, cap, kappa)
    s = sigma_min * math.sqrt(T)
    shift = 0.5826 * sigma_min
    p_long = 2 * Phi(-(-math.log(dl) + shift) / s)
    p_short = 2 * Phi(-(math.log(us) + shift) / s)
    return (p_long + p_short) / 2


class WindowTests(unittest.TestCase):
    def test_window_from_minutes(self):
        rows = [[i * MIN, 100 + i, 101 + i, 99 + i, 100.5 + i] for i in range(6)]   # two 3-minute windows
        w = rc.build_windows(rows, 1, 3)
        self.assertEqual(w.n, 2)
        self.assertAlmostEqual(w.hi[0], 103 / 100)        # max high of minutes 0-2
        self.assertAlmostEqual(w.lo[0], 99 / 100)         # min low
        self.assertAlmostEqual(w.close[0], 102.5 / 100)   # last close / first open
        self.assertAlmostEqual(w.hi[1], 106 / 103)

    def test_incomplete_and_unaligned_windows_dropped(self):
        rows = [[i * MIN, 100, 100, 100, 100] for i in range(1, 8)]    # starts at minute 1: first window incomplete
        w = rc.build_windows(rows, 1, 3)
        self.assertEqual(w.starts, [3 * MIN, 6 * MIN][:w.n])
        self.assertEqual(w.n, 1)                                        # minutes 3-5; 6-7 lacks minute 8

    def test_coarse_candles(self):
        rows = [[i * 3 * MIN, 100, 101, 99, 100] for i in range(10)]
        self.assertEqual(rc.build_windows(rows, 3, 15).n, 2)
        with self.assertRaises(ValueError):
            rc.build_windows(rows, 15, 3)

    def test_fill_flat_minutes(self):
        rows = [[0, 10, 11, 9, 10.5], [3 * MIN, 12, 13, 11, 12.5]]
        f = rc.fill_flat_minutes(rows)
        self.assertEqual([r[0] for r in f], [0, MIN, 2 * MIN, 3 * MIN])
        self.assertEqual(f[1], [MIN, 10.5, 10.5, 10.5, 10.5])


class MathTests(unittest.TestCase):
    def test_pliq_known_windows(self):
        # ten windows: lows 1.0 .. 0.91 step 0.01, highs symmetric
        lo = [1 - 0.01 * i for i in range(10)]
        hi = [1 + 0.01 * i for i in range(10)]
        w = rc.Windows(3, list(range(10)), lo, hi, [1.0] * 10)
        # L=100, cap=100, kappa=.1: l=.001; long liq at 0.99/0.999 = 0.990991 -> lows <= it: 0.99 is below? 0.99<=0.990991 yes
        dl, us = rc.liq_levels(100, 100)
        self.assertAlmostEqual(dl, 0.99 / 0.999)
        self.assertAlmostEqual(us, 1.01 / 1.001)
        # long: lows 0.99 and below -> i=1..9 = 9 windows ; short: highs >= 1.0090909 -> 1.01 and above -> i=1..9 = 9
        self.assertAlmostEqual(rc.p_liq(w, 100), 18 / 20)
        # L=2 : barrier ~ -50 %, never reached
        self.assertEqual(rc.p_liq(w, 2), 0.0)

    def test_liquidation_levels_match_engine_formula(self):
        # LiquidationCalculator: liq = (Q P0 - side m) / (Q (1 - l side)), m = Q P0 / L  ->  P0 (1 - side/L) / (1 - l side)
        for L, cap in ((50, 200), (7, 1000), (1000, 1000)):
            l = rc.KAPPA / cap
            dl, us = rc.liq_levels(L, cap)
            self.assertAlmostEqual(dl, (1 - 1 / L) / (1 - l))
            self.assertAlmostEqual(us, (1 + 1 / L) / (1 + l))

    def test_nice_down(self):
        self.assertEqual(rc.nice_down(1000), 1000)
        self.assertEqual(rc.nice_down(1199), 1000)
        self.assertEqual(rc.nice_down(1200), 1200)
        self.assertEqual(rc.nice_down(777), 600)
        self.assertEqual(rc.nice_down(7999), 6000)
        self.assertEqual(rc.nice_down(8000), 8000)
        self.assertEqual(rc.nice_down(9.9), 9)
        self.assertEqual(rc.proposed_cap(1000), 800)
        self.assertEqual(rc.proposed_cap(10 ** 6), rc.LEVERAGE_ABS_MAX)

    def test_r_m_clip(self):
        self.assertEqual(rc.r_m_bp(8.4e-4), 0.84)
        self.assertEqual(rc.r_m_bp(50e-4), 1.0)
        self.assertEqual(rc.r_m_bp(0.5e-4), 0.2)

    def test_kurtosis_and_sigma(self):
        s, k = rc.mean_sd_kurt([1, -1] * 50)
        self.assertAlmostEqual(s, 1.0)
        self.assertAlmostEqual(k, -2.0)       # two-point distribution

    def test_band_layout(self):
        self.assertEqual([(l, a, b) for l, a, b in rc.bands(1200)],
                         [("2-10", 2, 10), ("11-100", 11, 100), ("101-cap/4", 101, 300), ("cap/4-cap", 301, 1200)])
        self.assertEqual([x[0] for x in rc.bands(300)], ["2-10", "11-100", "cap/4-cap"])      # cap/4 = 75 < 101

    def test_stress_move(self):
        r = [0.001 * ((-1) ** i) for i in range(1000)]
        w = rc.Windows(3, list(range(1000)), [1] * 1000, [1] * 1000, [math.exp(v) for v in r])
        st, p999, s5 = rc.stress_move(w)
        self.assertAlmostEqual(p999, 0.001)
        self.assertAlmostEqual(s5, 0.005)
        self.assertAlmostEqual(st, 0.005)


class RandomWalkTests(unittest.TestCase):
    """Constant-volatility walk, 3-minute rounds: P(liquidation) must match the reflection-principle value."""

    @classmethod
    def setUpClass(cls):
        cls.sigma_min = 0.0005
        cls.w = rc.build_windows(walk_rows(3 * 60_000, cls.sigma_min, seed=7), 1, 3)   # 60,000 windows

    def test_window_count(self):
        self.assertEqual(self.w.n, 60_000)

    def test_sigma(self):
        s, _ = rc.mean_sd_kurt(self.w.log_returns())
        self.assertAlmostEqual(s / (self.sigma_min * math.sqrt(3)), 1.0, delta=0.02)

    def test_pliq_matches_theory(self):
        for L, cap in ((200, 200), (300, 300), (600, 600), (400, 1000)):
            got = rc.p_liq(self.w, L, cap)
            want = theoretical_pliq(L, cap, self.sigma_min, 3)
            self.assertAlmostEqual(got, want, delta=0.012, msg="L=%d cap=%d got %.4f want %.4f" % (L, cap, got, want))

    def test_exact_cap_hits_target(self):
        ex = rc.exact_cap(self.w)
        self.assertLess(rc.p_liq(self.w, ex - 1), rc.TARGET_PLIQ)
        self.assertGreaterEqual(rc.p_liq(self.w, ex), rc.TARGET_PLIQ)
        # theory: the cap where the reflection formula gives 35 %
        lo, hi = 2, 100000
        while hi - lo > 1:
            mid = (lo + hi) // 2
            if theoretical_pliq(mid, mid, self.sigma_min, 3) < rc.TARGET_PLIQ:
                lo = mid
            else:
                hi = mid
        self.assertAlmostEqual(ex / hi, 1.0, delta=0.08)   # barrier ~1.4 sigma_min: the discrete-monitoring shift is approximate

    def test_cap_scales_inversely_with_volatility(self):
        w2 = rc.build_windows(walk_rows(3 * 20_000, 2 * self.sigma_min, seed=11), 1, 3)
        e1, e2 = rc.exact_cap(self.w), rc.exact_cap(w2)
        self.assertAlmostEqual(e1 / e2, 2.0, delta=0.25)

    def test_proposed_is_conservative(self):
        a = rc.analyse(self.w, 1000, 0.5, "synthetic")
        self.assertLessEqual(a["proposed_cap"], 0.8 * a["exact_cap"])
        self.assertLess(a["p_liq_at_proposed"], rc.TARGET_PLIQ)
        self.assertTrue(a["needs_operator_approval"] == (a["proposed_cap"] > 1000))
        self.assertEqual(a["weekly_blocks"], 18)              # 125 days: 17 full weeks + a block that is 86 % full

    def test_rtp_driftless_and_remainder_gap(self):
        # driftless walk, long+short averaged: without liquidation the mean payout is 1 (+ convexity ~ 0);
        # at low leverage the gap is ~0 and RTP = 1 - fee
        e = rc.evaluate(self.w, 5, 600, fee_bp=1.0)
        self.assertAlmostEqual(e["mean_payout"], 1.0, delta=0.004)
        self.assertAlmostEqual(e["fee"], 5e-4)
        self.assertEqual(e["p_liq"], 0.0)
        self.assertAlmostEqual(e["rtp"], e["mean_payout"] - e["fee"])
        # at the cap the fee is capped at 1 % of the stake and liquidations occur; the remainder gap is the stake lost
        top = rc.evaluate(self.w, 600, 600, fee_bp=1.0)
        self.assertGreater(top["p_liq"], 0.03)
        self.assertAlmostEqual(top["fee"], 0.01 if 600 * 1e-4 > 0.01 else 600 * 1e-4)
        self.assertAlmostEqual(top["rtp"], 1 - top["fee"] - top["gap"])
        self.assertGreater(top["p_lose"], top["p_liq"] - 1e-9)

    def test_weeks_blocks(self):
        w = rc.build_windows(walk_rows(3 * 7 * 1440 + 100, 0.0005, seed=3), 1, 3)
        blocks = rc.week_blocks(w)
        self.assertEqual(len(blocks), 3)
        self.assertEqual(sum(b.n for b in blocks), 3 * 7 * 480)


class EndToEndTests(unittest.TestCase):
    def test_cache_roundtrip_and_outputs(self):
        with tempfile.TemporaryDirectory() as d:
            cache = os.path.join(d, "candles")
            os.makedirs(cache)
            rows = walk_rows(15 * 960, 0.0004, seed=5, start=0)       # 10 days, one market
            json.dump(rows, open(os.path.join(cache, "cb_BTC_1m.json"), "w"))
            json.dump({"fetched_at_ms": 10 * 86_400_000, "weeks": 4}, open(os.path.join(cache, "meta.json"), "w"))
            out = os.path.join(d, "out")
            p = rc.run(out, 4, from_cache=cache, symbols=["BTC"])
            self.assertIn("BTC", p["markets"])
            m = p["markets"]["BTC"]
            self.assertEqual(set(m), {"cap_hourly", "cap_daily", "kappa", "herd_fee_bp", "stress_move"})
            self.assertEqual(m["kappa"], 0.1)
            self.assertTrue(0.2 <= m["herd_fee_bp"] <= 1.0)
            self.assertGreater(m["stress_move"], 0)
            self.assertTrue(os.path.exists(os.path.join(out, "caps_proposal.json")))
            md = open(os.path.join(out, "caps_proposal.md")).read()
            self.assertIn("BTC", md)
            # the safe set never exceeds the current values
            cur = rc.CURRENT_DEFAULTS["BTC"]
            safe = p["markets_apply_without_approval"]["BTC"]
            self.assertLessEqual(safe["cap_hourly"], cur[0])
            self.assertLessEqual(safe["cap_daily"], cur[1])
            self.assertLessEqual(safe["herd_fee_bp"], cur[2])
            for x in p["needs_operator_approval"]:
                self.assertGreater(x["proposed"], x["current"])
            # a rerun from the same cache is identical (deterministic, no network)
            p2 = rc.run(os.path.join(d, "out2"), 4, from_cache=cache, symbols=["BTC"])
            self.assertEqual(p["markets"], p2["markets"])

    def test_source_choice_prefers_longer_history_then_finer(self):
        fine = walk_rows(15 * 100, 0.0004, seed=1)                                      # 25 h of 1m
        coarse = [[i * 15 * MIN, 100, 101, 99, 100] for i in range(300)]                # 75 h of 15m
        label, w = rc.pick_source([("Coinbase 1m", 1, fine), ("Hyperliquid 15m", 15, coarse)], 15)
        self.assertEqual(label, "Hyperliquid 15m")
        full = walk_rows(15 * 300, 0.0004, seed=1)
        label, w = rc.pick_source([("Coinbase 1m", 1, full), ("Hyperliquid 15m", 15, coarse)], 15)
        self.assertEqual(label, "Coinbase 1m")


if __name__ == "__main__":
    unittest.main()
