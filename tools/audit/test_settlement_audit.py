#!/usr/bin/env python3
"""Offline tests of the fixed-payout (Р-106) checks of settlement_audit.py: payout 2S / 0 / S by the open and the close, the fee
(3 % of the stake rounded up to 0.001 PLAY + 0.1), and the findings for a position that was paid or charged wrongly.

Run: python3 -m unittest tools/audit/test_settlement_audit.py   (no network, no database)
"""
import os
import sys
import unittest
from decimal import Decimal as D

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import settlement_audit as audit  # noqa: E402

MARKETS = {"spot_SOL": {"id": "spot_SOL", "kind": "PERP", "symbol": "SOL", "maxLeverage": 25, "capHourly": 600, "capDaily": 300,
                        "kappa": "0.1", "feeBp": "1.0"}}


def fixed_case(direction, open_p, close_p, payout, stake="100.000", herd=3000, net=100, leverage=2, mode="FIXED", bet_mode="FIXED",
               liquidated=False, with_liq_params=False):
    bet = {"playerId": "p1", "direction": direction, "leverage": leverage, "stakeMojos": int(D(stake) * 1000), "atMs": 1000,
           "market": "spot_SOL", "feeHerdMojos": herd, "feeNetMojos": net}
    if bet_mode:
        bet["mode"] = bet_mode
    if with_liq_params:
        bet["liqCap"], bet["liqKappa"] = 600, "0.1"
    position = {"round": "spot_SOL-1h-2000", "market": "spot_SOL", "interval": "HOURLY", "ws": 2000, "we": 182000, "player": "p1",
                "bot": False, "dir": direction, "lev": leverage, "stake": stake, "payout": payout, "liquidated": liquidated,
                "entry": open_p, "liqPrice": None, "size": "0.00000000", "dirCorrect": None, "mode": mode, "bet": bet}
    candle = {"openTimeMs": 2000, "closeTimeMs": 181999, "open": open_p, "high": max(open_p, close_p), "low": min(open_p, close_p),
              "close": close_p}
    rounds = {("spot_SOL", "HOURLY", 2000): {"market": "spot_SOL", "interval": "HOURLY", "ws": 2000, "we": 182000, "open": open_p,
                                              "close": close_p, "venue": None, "path": [candle], "bets": 1}}
    return position, rounds


def run(position, rounds):
    findings = audit.Findings()
    rows = audit.audit_positions(MARKETS, [position], rounds, D(99_998_100), findings)
    return findings, rows


class FixedPayoutAudit(unittest.TestCase):
    def test_expected_payouts(self):
        s = D(100)
        self.assertEqual(audit.expected_fixed_payout(s, 1, D("10"), D("11")), D(200))     # long, price up
        self.assertEqual(audit.expected_fixed_payout(s, 1, D("10"), D("9")), D(0))        # long, price down
        self.assertEqual(audit.expected_fixed_payout(s, -1, D("10"), D("9")), D(200))     # short, price down
        self.assertEqual(audit.expected_fixed_payout(s, -1, D("10"), D("11")), D(0))
        self.assertEqual(audit.expected_fixed_payout(s, 1, D("10"), D("10")), D(100))     # tie: stake back
        self.assertEqual(audit.expected_fixed_payout(s, -1, D("10"), D("10")), D(100))

    def test_expected_fees_round_up_to_a_thousandth(self):
        self.assertEqual(audit.expected_fixed_fees(D("100")), (3000, 100))
        self.assertEqual(audit.expected_fixed_fees(D("33.333")), (1000, 100))   # 0.99999
        self.assertEqual(audit.expected_fixed_fees(D("33.334")), (1001, 100))   # 1.00002
        self.assertEqual(audit.expected_fixed_fees(D("250.5")), (7515, 100))

    def test_a_correct_win_loss_and_tie_pass(self):
        for args in (("UP", "100.00", "101.00", "200.000"), ("DOWN", "100.00", "99.00", "200.000"),
                     ("UP", "100.00", "99.00", "0.000"), ("DOWN", "100.00", "101.00", "0.000"),
                     ("UP", "100.00", "100.00", "100.000"), ("DOWN", "100.00", "100.00", "100.000")):
            findings, rows = run(*fixed_case(*args))
            self.assertEqual(findings.hard, [], args)
            self.assertEqual(len(rows), 1)
            self.assertTrue(rows[0]["fixed"])

    def test_a_wrong_payout_is_a_hard_finding(self):
        findings, _ = run(*fixed_case("UP", "100.00", "101.00", "100.000"))  # paid the stake instead of twice
        self.assertEqual([f[0] for f in findings.hard], ["payout"])
        findings, _ = run(*fixed_case("UP", "100.00", "100.00", "0.000"))    # a tie paid nothing
        self.assertEqual([f[0] for f in findings.hard], ["payout"])

    def test_a_wrong_fee_is_a_hard_finding(self):
        findings, _ = run(*fixed_case("UP", "100.00", "101.00", "200.000", herd=2999))
        self.assertEqual([f[0] for f in findings.hard], ["fee"])
        findings, _ = run(*fixed_case("UP", "100.00", "101.00", "200.000", net=0))
        self.assertEqual([f[0] for f in findings.hard], ["fee"])

    def test_the_mode_leverage_and_liquidation_rules(self):
        findings, _ = run(*fixed_case("UP", "100.00", "101.00", "200.000", mode=None))
        self.assertIn("fixed-mode", [f[0] for f in findings.hard])
        findings, _ = run(*fixed_case("UP", "100.00", "101.00", "200.000", leverage=10))
        self.assertIn("fixed-leverage", [f[0] for f in findings.hard])
        findings, _ = run(*fixed_case("UP", "100.00", "101.00", "200.000", liquidated=True))
        self.assertIn("fixed-liquidated", [f[0] for f in findings.hard])
        findings, _ = run(*fixed_case("UP", "100.00", "101.00", "200.000", with_liq_params=True))
        self.assertIn("fixed-liquidation-params", [f[0] for f in findings.hard])

    def test_a_percentage_bet_is_untouched_by_the_fixed_checks(self):
        position, rounds = fixed_case("UP", "100.00", "101.00", "200.000", mode=None, bet_mode=None)
        self.assertNotIn("mode", position["bet"])
        position.update(liqPrice="95.00", size="1000.00000000")  # it takes the percentage path (and may fail its own checks - not the fixed ones)
        findings, rows = run(position, rounds)
        self.assertFalse(any(r.get("fixed") for r in rows))
        self.assertNotIn("fixed-mode", [f[0] for f in findings.hard])

    def test_the_summary_lists_the_fixed_positions_apart_from_the_leverage_tables(self):
        position, rounds = fixed_case("UP", "100.00", "101.00", "200.000")
        _, rows = run(position, rounds)
        out = []
        audit.summary(rows, {}, out)
        text = "\n".join(out)
        self.assertIn("Fixed-payout (Win or lose) positions", text)
        self.assertNotIn("| spot_SOL | 3 min | cap |", text)


if __name__ == "__main__":
    unittest.main()
