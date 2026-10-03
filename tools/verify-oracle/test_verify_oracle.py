"""Offline tests for verify_oracle.py — no network, no stand, no coinset.

Run from the repository root with a Python that has chia_rs (requirements.txt):

    python3 -m unittest discover -s tools/verify-oracle -p 'test_*.py'

The verifier's two network layers (CoinsetClient, StandClient) are small classes the run is
parameterised on; here they are replaced by canned fakes that serve exactly the JSON the
real services serve, so every case goes through the same code a real run does: the real
lineage walk (chia_rs runs each canned spend's puzzle against its solution), the real
announcement parser, the real seq analysis and cross-check, the real verdict and exit code.

The announcement bytes are the same golden bytes chain/c2-svc's Rust tests pin
(tests/oracle_golden/mod.rs), so the two decoders are tied to one byte string.
"""
from __future__ import annotations

import contextlib
import hashlib
import io
import json
import unittest
import urllib.error
from typing import Any, Optional
from unittest import mock

from chia_rs import Program

import verify_oracle as v

# --- golden bytes shared with chain/c2-svc/tests/oracle_golden/mod.rs ----------------------

SIX_ELEMENT_HEX = (
    "ff820479ff846ab75600ff840082a230ffa0abababababababababababababababababababababababababababababababab"
    "ff80ff846ab7560180"
)
SEVEN_ELEMENT_ASSET_3_HEX = (
    "ff820479ff846ab75600ff840082a230ffa0abababababababababababababababababababababababababababababababab"
    "ff80ff846ab75601ff0380"
)
SEVEN_ELEMENT_ASSET_200_HEX = (
    "ff820479ff846ab75600ff840082a230ffa0abababababababababababababababababababababababababababababababab"
    "ff80ff846ab75601ff8200c880"
)
# The very first spend of the real singleton (week 1, before Р-41): (priceCents ts seq), 3 elements.
WEEK_ONE_THREE_ELEMENT_HEX = "ff8400801ed4ff846ab6a055ff0180"

LAUNCHER = bytes([0x11] * 32)
SINGLETON_PUZZLE_HASH = bytes([0xC3] * 32)
STAND_URL = "http://stand.test"


def clvm_message(
    seq: int,
    *,
    price: int = 8_561_200,
    results_hash: Optional[bytes] = None,
    asset: int = 0,
    force_seven: bool = False,
    ts: Optional[int] = None,
    source_flag: int = 0,
    source_ts: Optional[int] = None,
) -> bytes:
    """The announcement message of one record, as the Rust encoder lays it out: 6 elements,
    plus a 7th `asset` only when asset > 0 (force_seven = an explicit 0, which is legal to
    read but never produced)."""
    elements: list[Any] = [
        seq,
        ts if ts is not None else 1_790_000_000 + seq,
        price,
        results_hash if results_hash is not None else bytes([seq % 256]) * 32,
        source_flag,
        source_ts if source_ts is not None else 1_789_999_000 + seq,
    ]
    if asset or force_seven:
        elements.append(asset)
    return bytes(Program.to(elements))


def canonical_json(doc: dict) -> bytes:
    """What the server's ResultsHasher produces in shape: compact, keys sorted at every level."""
    return json.dumps(doc, sort_keys=True, separators=(",", ":")).encode()


class Round:
    """One settled round as a stand serves it: results.json, its hash, the journal row, and the
    announcement message the chain carries for it."""

    def __init__(self, seq: int, *, market: str = "BTC", asset: int = 0, price: int = 8_561_200,
                 status: str = "SETTLED", close_price: Any = "same"):
        self.seq = seq
        self.market = market
        self.asset = asset
        self.price = price
        self.round_id = f"1h-{1_790_000_000_000 + seq * 180_000}" if market == "BTC" else (
            f"{market}-1h-{1_790_000_000_000 + seq * 180_000}")
        doc = {
            "closePriceCents": price if close_price == "same" else close_price,
            "crowd": None,
            "openPriceCents": price - 100,
            "positions": [],
            "roundId": self.round_id,
            "series": "HOURLY",
            "status": status,
        }
        if market != "BTC":
            doc["market"] = market
        self.body = canonical_json(doc)
        self.results_hash = hashlib.sha256(self.body).digest()

    def message(self, **overrides: Any) -> bytes:
        kwargs: dict[str, Any] = dict(price=self.price, results_hash=self.results_hash, asset=self.asset)
        kwargs.update(overrides)
        return clvm_message(self.seq, **kwargs)

    def journal_row(self, state: str = "confirmed") -> dict:
        return {
            "seq": self.seq,
            "roundId": self.round_id,
            "marketId": self.market,
            "asset": self.asset,
            "ts": 1_790_000_000 + self.seq,
            "priceCents": self.price,
            "resultsHash": "0x" + self.results_hash.hex(),
            "state": state,
        }


class FakeCoinset:
    """Canned coinset.org: a launcher coin and a straight chain of singleton generations. Serves
    exactly the three calls walk_lineage makes, with the JSON shapes coinset serves."""

    def __init__(self) -> None:
        self.launcher_id = LAUNCHER.hex()
        self._children: dict[str, list[dict]] = {}
        self._solutions: dict[str, dict] = {}
        self._tip = self.launcher_id
        self._height = 4_735_700
        self.calls: list[str] = []

    def add_generation(self, messages: list[bytes], *, spent: bool = True) -> str:
        """The next singleton generation: spent, announcing `messages` (CREATE_PUZZLE_ANNOUNCEMENT
        each, next to the CREATE_COIN that recreates the singleton), or the unspent tip."""
        parent = self._tip
        coin = {"parent_coin_info": "0x" + parent, "puzzle_hash": "0x" + SINGLETON_PUZZLE_HASH.hex(), "amount": 1}
        coin_id = v.coin_id_of(coin)
        self._height += 3
        self._children.setdefault(parent, []).append(
            {
                "coin": coin,
                "spent": spent,
                "confirmed_block_index": self._height,
                "spent_block_index": self._height + 2 if spent else 0,
                "timestamp": 1_790_000_000 + self._height,
            }
        )
        if spent:
            conditions: list[Any] = [[51, SINGLETON_PUZZLE_HASH, 1]] + [[62, m] for m in messages]
            puzzle = Program.to((1, conditions))  # (q . conditions): returns the conditions as they are
            self._solutions[coin_id] = {"puzzle_reveal": "0x" + bytes(puzzle).hex(), "solution": "0x80"}
        self._tip = coin_id
        return coin_id

    def add_fork_sibling(self, parent_id: str) -> None:
        """A second child of `parent_id` — a singleton lineage must never fork."""
        coin = {"parent_coin_info": "0x" + parent_id, "puzzle_hash": "0x" + bytes([0xEE] * 32).hex(), "amount": 1}
        self._children[parent_id].append(
            {"coin": coin, "spent": False, "confirmed_block_index": self._height, "spent_block_index": 0}
        )

    # --- the three calls ---
    def get_coin_record_by_name(self, name_hex: str) -> Optional[dict]:
        self.calls.append("get_coin_record_by_name")
        if v.hx(name_hex) == self.launcher_id:
            return {"spent": True, "confirmed_block_index": 4_735_600, "spent_block_index": 4_735_660}
        return None

    def get_children(self, coin_id_hex: str) -> list[dict]:
        self.calls.append("get_children")
        return list(self._children.get(v.hx(coin_id_hex), []))

    def get_puzzle_and_solution(self, coin_id_hex: str, height: int) -> Optional[dict]:
        self.calls.append("get_puzzle_and_solution")
        return self._solutions.get(v.hx(coin_id_hex))


class FakeStand:
    """Canned stand. `journal=None` answers 404 on /api/oracle/records (an old stand);
    likewise `registry=None` on /api/markets/registry.json."""

    def __init__(self, rounds: list[Round], *, journal: Any = "auto", registry: Any = "auto",
                 first_seq: Optional[int] = None, bodies: Optional[dict] = None, rpc: Optional[dict] = None):
        self.rounds = {r.seq: r for r in rounds}
        if journal == "auto":
            journal = {
                "firstSeq": first_seq if first_seq is not None else (min(self.rounds) if self.rounds else None),
                "records": [r.journal_row() for r in sorted(rounds, key=lambda r: r.seq)],
            }
        self.journal = journal
        if registry == "auto":
            registry = [
                {"index": 0, "id": "BTC", "kind": "PERP", "symbol": "BTC", "priceDecimals": 2, "source": "hyperliquid",
                 "sourceRef": "BTC", "listedAtMs": 0},
                {"index": 3, "id": "ETH", "kind": "PERP", "symbol": "ETH", "priceDecimals": 2, "source": "hyperliquid",
                 "sourceRef": "ETH", "listedAtMs": 1},
                {"index": 200, "id": "xyz_NVDA", "kind": "PERP", "symbol": "NVDA", "priceDecimals": 3,
                 "source": "hyperliquid", "sourceRef": "xyz:NVDA", "listedAtMs": 2},
            ]
        self.registry_data = registry
        self.bodies = {r.round_id: r.body for r in rounds}
        if bodies:
            self.bodies.update(bodies)
        self.rpc = rpc or {}
        self.record_calls: list[tuple[int, int]] = []
        self.fetched: list[str] = []

    def oracle_records(self, from_seq: int, limit: int) -> Optional[dict]:
        self.record_calls.append((from_seq, limit))
        if self.journal is None:
            return None
        page = [r for r in self.journal["records"] if r["seq"] >= from_seq][:limit]
        return {"firstSeq": self.journal["firstSeq"], "records": page}

    def registry(self) -> Optional[list]:
        return self.registry_data

    def results_json_bytes(self, round_id: str) -> Optional[bytes]:
        self.fetched.append(round_id)
        return self.bodies.get(round_id)

    def rounds_pair(self, interval: str) -> Optional[dict]:
        return self.rpc.get("pairs", {}).get(interval)

    def round(self, round_id: str) -> Optional[dict]:
        return self.rpc.get("rounds", {}).get(round_id)


def chain_of(*generations: list[bytes], tip: bool = True) -> FakeCoinset:
    coinset = FakeCoinset()
    for messages in generations:
        coinset.add_generation(messages)
    if tip:
        coinset.add_generation([], spent=False)
    return coinset


def run(coinset: FakeCoinset, stand: Optional[FakeStand] = None, *, strict: bool = False,
        from_seq: Optional[int] = None, max_generations: int = 20000) -> tuple[dict, int]:
    """One verifier run exactly as main() performs it, minus argument parsing and real HTTP."""
    with contextlib.redirect_stderr(io.StringIO()):
        result = v.verify(
            coinset.launcher_id,
            coinset,
            stand,
            from_seq=from_seq,
            max_generations=max_generations,
            verbose=False,
            network="testnet11",
            api_url="https://coinset.test",
            stand_url=STAND_URL if stand else None,
        )
    return result, v.exit_code(result, strict)


def summary_text(result: dict, strict: bool = False) -> str:
    out = io.StringIO()
    with contextlib.redirect_stdout(out):
        v.print_human_summary(result, strict)
    return out.getvalue()


# ======================================================================================
# The announcement parser (the same bytes the Rust decoder test uses)
# ======================================================================================


class ParseAnnouncementTests(unittest.TestCase):
    def test_golden_six_element_message_is_asset_zero(self) -> None:
        parsed = v.parse_announcement_message(bytes.fromhex(SIX_ELEMENT_HEX))
        self.assertEqual(
            parsed,
            {
                "seq": 1145,
                "ts": 1_790_400_000,
                "priceCents": 8_561_200,
                "resultsHash": "0x" + "ab" * 32,
                "sourceFlag": 0,
                "sourceTs": 1_790_400_001,
                "asset": 0,
                "elements": 6,
            },
        )

    def test_golden_seven_element_messages_carry_their_asset(self) -> None:
        small = v.parse_announcement_message(bytes.fromhex(SEVEN_ELEMENT_ASSET_3_HEX))
        self.assertEqual((small["asset"], small["elements"], small["seq"]), (3, 7, 1145))
        # 200 needs the two-byte signed form 00c8; it must read back as 200, not -56.
        large = v.parse_announcement_message(bytes.fromhex(SEVEN_ELEMENT_ASSET_200_HEX))
        self.assertEqual((large["asset"], large["elements"]), (200, 7))

    def test_a_python_built_message_is_byte_identical_to_the_golden_ones(self) -> None:
        # The same independent encoder the Rust golden constants were cross-checked with.
        golden = dict(ts=1_790_400_000, price=8_561_200, results_hash=b"\xab" * 32, source_ts=1_790_400_001)
        self.assertEqual(clvm_message(1145, **golden).hex(), SIX_ELEMENT_HEX)
        self.assertEqual(clvm_message(1145, asset=3, **golden).hex(), SEVEN_ELEMENT_ASSET_3_HEX)
        self.assertEqual(clvm_message(1145, asset=200, **golden).hex(), SEVEN_ELEMENT_ASSET_200_HEX)

    def test_an_explicit_zero_asset_is_the_same_record_as_the_six_element_form(self) -> None:
        six = v.parse_announcement_message(clvm_message(7))
        seven = v.parse_announcement_message(clvm_message(7, force_seven=True))
        self.assertEqual(seven["elements"], 7)
        for field in v.ANNOUNCEMENT_FIELDS:
            self.assertEqual(six[field], seven[field], field)

    def test_what_is_not_a_record_raises(self) -> None:
        good = [1145, 1_790_400_000, 8_561_200, b"\xab" * 32, 0, 1_790_400_001]
        cases = {
            "the week-1 3-element message": bytes.fromhex(WEEK_ONE_THREE_ELEMENT_HEX),
            "5 elements": bytes(Program.to(good[:5])),
            "8 elements": bytes(Program.to(good + [3, 99])),
            "negative asset": bytes(Program.to(good + [-1])),
            "asset above u32": bytes(Program.to(good + [2**32])),
            "negative seq": bytes(Program.to([-5] + good[1:])),
            "31-byte hash": bytes(Program.to(good[:3] + [b"\xab" * 31] + good[4:])),
            "nested list as an element": bytes(Program.to(good[:2] + [[1, 2]] + good[3:])),
            "improper list": bytes(Program.to((1145, (1, (2, (3, (4, (5, 6)))))))),
            "a bare atom": bytes(Program.to(b"abc")),
            "truncated bytes": bytes.fromhex(SIX_ELEMENT_HEX)[:20],
        }
        for name, message in cases.items():
            with self.subTest(name), self.assertRaises(ValueError):
                v.parse_announcement_message(message)


# ======================================================================================
# Chain-only runs: shapes, duplicates, conflicts, gaps
# ======================================================================================


class ChainOnlyTests(unittest.TestCase):
    def test_a_chain_of_only_six_element_announcements(self) -> None:
        # The week-1 spend (3 elements) comes first, then BTC records 1..5 as 6-element messages.
        coinset = chain_of(
            [bytes.fromhex(WEEK_ONE_THREE_ELEMENT_HEX)],
            [clvm_message(1), clvm_message(2)],
            [clvm_message(3), clvm_message(4), clvm_message(5)],
        )
        result, code = run(coinset)
        analysis = result["seq_analysis"]
        self.assertEqual(analysis["distinct_seq_count"], 5)
        self.assertEqual((analysis["seq_min"], analysis["seq_max"]), (1, 5))
        self.assertEqual(analysis["conflicts"], [])
        self.assertEqual(analysis["gaps"], [])
        self.assertTrue(all(i["first"]["announcement"]["elements"] == 6 for i in analysis["per_seq"].values()))
        self.assertTrue(all(i["first"]["announcement"]["asset"] == 0 for i in analysis["per_seq"].values()))
        # The 3-element message stays "unparsed", and does not fail the run.
        self.assertEqual(len(analysis["unparsed_announcements"]), 1)
        self.assertIn("3", analysis["unparsed_announcements"][0]["parse_error"])
        self.assertEqual(code, 0)
        self.assertEqual(v.exit_code(result, strict=True), 0)

    def test_a_mixed_chain_of_six_and_seven_element_announcements(self) -> None:
        assets = {1: 0, 2: 3, 3: 0, 4: 200, 5: 3, 6: 0}
        coinset = chain_of(
            [clvm_message(1), clvm_message(2, asset=3)],
            [clvm_message(3), clvm_message(4, asset=200)],
            [clvm_message(5, asset=3), clvm_message(6)],
        )
        result, code = run(coinset)
        per_seq = result["seq_analysis"]["per_seq"]
        self.assertEqual({s: i["first"]["announcement"]["asset"] for s, i in per_seq.items()}, assets)
        self.assertEqual(
            {s: i["first"]["announcement"]["elements"] for s, i in per_seq.items()},
            {1: 6, 2: 7, 3: 6, 4: 7, 5: 7, 6: 6},
        )
        self.assertEqual(result["seq_analysis"]["gaps"], [])
        self.assertEqual(result["seq_analysis"]["conflicts"], [])
        self.assertEqual(code, 0)
        text = summary_text(result)
        self.assertIn("3 x 6-element, 3 x 7-element", text)
        self.assertIn("asset 3: 2", text)
        self.assertIn("VERDICT: OK", text)

    def test_an_identical_duplicate_is_benign(self) -> None:
        coinset = chain_of([clvm_message(1), clvm_message(2)], [clvm_message(2)], [clvm_message(3)])
        result, code = run(coinset)
        analysis = result["seq_analysis"]
        self.assertEqual(analysis["benign_duplicate_count"], 1)
        self.assertEqual(analysis["conflicts"], [])
        self.assertEqual(analysis["per_seq"][2]["status"], "duplicate")
        self.assertEqual(code, 0)

    def test_the_six_element_form_and_a_seven_element_zero_are_the_same_record(self) -> None:
        # Two encodings of one logical BTC record must compare equal, not conflict.
        coinset = chain_of([clvm_message(1)], [clvm_message(1, force_seven=True)])
        result, code = run(coinset)
        self.assertEqual(result["seq_analysis"]["benign_duplicate_count"], 1)
        self.assertEqual(result["seq_analysis"]["conflicts"], [])
        self.assertEqual(code, 0)

    def test_a_seq_re_announced_with_only_a_different_asset_is_a_conflict(self) -> None:
        for first, second in ((0, 3), (3, 0), (3, 4)):
            with self.subTest(first=first, second=second):
                coinset = chain_of([clvm_message(1, asset=first)], [clvm_message(1, asset=second)])
                result, code = run(coinset)
                conflicts = result["seq_analysis"]["conflicts"]
                self.assertEqual(len(conflicts), 1)
                self.assertEqual(conflicts[0]["differing_fields"], ["asset"])
                self.assertEqual(result["seq_analysis"]["per_seq"][1]["status"], "CONFLICT")
                self.assertEqual(code, 1)
                self.assertIn("differing fields: asset", summary_text(result))

    def test_a_seq_re_announced_with_a_different_price_is_a_conflict_too(self) -> None:
        coinset = chain_of([clvm_message(1)], [clvm_message(1, price=1)])
        result, code = run(coinset)
        self.assertEqual(result["seq_analysis"]["conflicts"][0]["differing_fields"], ["priceCents"])
        self.assertEqual(code, 1)

    def test_a_gap_is_reported_and_fails_only_under_strict(self) -> None:
        coinset = chain_of([clvm_message(1), clvm_message(2)], [clvm_message(4), clvm_message(5)])
        result, code = run(coinset)
        self.assertEqual(result["seq_analysis"]["gaps"], [{"after_seq": 2, "before_seq": 4, "missing_seqs": [3]}])
        self.assertEqual(result["scope"]["missing_in_scope"], [3])
        self.assertEqual(code, 0, "a gap alone does not fail a default run (it is printed)")
        self.assertEqual(v.exit_code(result, strict=True), 1)
        text = summary_text(result, strict=True)
        self.assertIn("VERDICT: PROBLEMS FOUND", text)
        self.assertIn("1 seq(s) missing from the chain inside the scope (--strict)", text)
        self.assertIn("VERDICT: OK", summary_text(result, strict=False))

    def test_from_seq_moves_the_scope_for_strict(self) -> None:
        coinset = chain_of([clvm_message(1), clvm_message(3)], [clvm_message(4), clvm_message(5)])
        result, _ = run(coinset, from_seq=3)
        self.assertEqual(result["scope"]["missing_in_scope"], [], "the gap at seq 2 is below --from-seq 3")
        self.assertEqual(v.exit_code(result, strict=True), 0)
        result, _ = run(coinset, from_seq=1)
        self.assertEqual(result["scope"]["missing_in_scope"], [2])
        self.assertEqual(v.exit_code(result, strict=True), 1)

    def test_a_leading_gap_at_the_start_of_an_explicit_scope_counts(self) -> None:
        coinset = chain_of([clvm_message(4), clvm_message(5)])
        result, _ = run(coinset, from_seq=2)
        self.assertEqual(result["scope"]["missing_in_scope"], [2, 3])

    def test_unparseable_shapes_are_listed_and_never_become_seqs(self) -> None:
        good = [9, 1_790_400_000, 8_561_200, b"\xab" * 32, 0, 1_790_400_001]
        junk = [
            bytes(Program.to(good[:5])),
            bytes(Program.to(good + [3, 99])),
            bytes(Program.to(good + [-1])),
            bytes(Program.to(good[:3] + [b"\xab" * 31] + good[4:])),
        ]
        coinset = chain_of(junk, [clvm_message(1)])
        result, code = run(coinset)
        self.assertEqual(len(result["seq_analysis"]["unparsed_announcements"]), 4)
        self.assertEqual(list(result["seq_analysis"]["per_seq"]), [1])
        self.assertEqual(code, 0)

    def test_a_lineage_fork_fails_the_run(self) -> None:
        coinset = FakeCoinset()
        tip = coinset.add_generation([clvm_message(1)])
        coinset.add_fork_sibling(coinset.launcher_id)
        coinset.add_generation([], spent=False)
        result, code = run(coinset)
        self.assertTrue(any("children" in e for e in result["lineage_walk"]["errors"]))
        self.assertEqual(code, 1)
        self.assertIsNotNone(tip)

    def test_a_walk_cut_short_by_max_generations_fails_the_run(self) -> None:
        coinset = chain_of(*[[clvm_message(i)] for i in range(1, 6)])
        result, code = run(coinset, max_generations=2)
        self.assertTrue(any("generation limit" in e for e in result["lineage_walk"]["errors"]))
        self.assertEqual(code, 1, "an incomplete verification must not read as OK")

    def test_a_spend_coinset_cannot_serve_fails_the_run(self) -> None:
        coinset = chain_of([clvm_message(1)], [clvm_message(2)])
        # coinset loses the puzzle/solution of the first spent coin.
        first_spent = next(iter(coinset._solutions))
        del coinset._solutions[first_spent]
        result, code = run(coinset)
        self.assertEqual(len(result["seq_analysis"]["decode_errors"]), 1)
        self.assertEqual(code, 1)

    def test_the_json_result_serialises(self) -> None:
        coinset = chain_of([clvm_message(1), clvm_message(2, asset=3)])
        stand = FakeStand([Round(1), Round(2, market="ETH", asset=3)])
        result, _ = run(coinset, stand)
        self.assertIn('"asset"', json.dumps(result, default=str))


# ======================================================================================
# Runs against a stand: the records journal
# ======================================================================================


def world(rounds: list[Round], per_generation: int = 2, **stand_kwargs: Any) -> tuple[FakeCoinset, FakeStand]:
    messages = [r.message() for r in rounds]
    generations = [messages[i:i + per_generation] for i in range(0, len(messages), per_generation)]
    return chain_of(*generations), FakeStand(rounds, **stand_kwargs)


class StandJournalTests(unittest.TestCase):
    def test_a_healthy_multi_market_stand_verifies(self) -> None:
        rounds = [Round(1), Round(2, market="ETH", asset=3), Round(3), Round(4, market="xyz_NVDA", asset=200)]
        coinset, stand = world(rounds)
        result, code = run(coinset, stand, strict=True)
        check = result["stand_check"]
        self.assertEqual(check["mode"], "records-journal")
        self.assertEqual(check["checked_count"], 4)
        self.assertEqual(check["mismatches"], [])
        self.assertEqual(check["below_scope"], [])
        self.assertEqual(check["unmapped_in_scope"], [])
        self.assertTrue(all(c["hash_match"] and c["price_match"] and c["asset_match"] for c in check["checked"]))
        self.assertEqual({c["seq"]: c["asset_label"] for c in check["checked"]},
                         {1: "asset 0 (BTC)", 2: "asset 3 (ETH)", 3: "asset 0 (BTC)", 4: "asset 200 (xyz_NVDA)"})
        self.assertEqual(code, 0)
        text = summary_text(result, strict=True)
        self.assertIn("[records-journal]", text)
        self.assertIn("asset 3 (ETH): 1", text)

    def test_a_stand_whose_btc_moved_to_coinbase_verifies_both_eras(self) -> None:
        # BTC's price moved from Hyperliquid to Coinbase ("Moved to a new price source: Coinbase"): the old BTC row
        # (asset 0) is closed but stays in the registry, the new market spot_BTC has an index of its own and the source
        # "coinbase". The verifier reads the registry for ids and indexes only — no source is special, none needs to be
        # known — so rounds of both eras verify, and each is labelled with its own market.
        rounds = [Round(1), Round(2), Round(3, market="spot_BTC", asset=9), Round(4, market="spot_BTC", asset=9)]
        registry = [
            {"index": 0, "id": "BTC", "kind": "PERP", "symbol": "BTC", "priceDecimals": 2, "source": "hyperliquid",
             "sourceRef": "BTC", "listedAtMs": 0},
            {"index": 9, "id": "spot_BTC", "kind": "PERP", "symbol": "BTC", "priceDecimals": 2, "source": "coinbase",
             "sourceRef": "BTC-USD", "listedAtMs": 5},
            {"index": 10, "id": "spot_HYPE", "kind": "PERP", "symbol": "HYPE", "priceDecimals": 4,
             "source": "hyperliquid", "sourceRef": "@107", "listedAtMs": 6},
        ]
        coinset = chain_of([r.message() for r in rounds])
        result, code = run(coinset, FakeStand(rounds, registry=registry), strict=True)
        check = result["stand_check"]
        self.assertEqual(check["mismatches"], [])
        self.assertEqual({c["seq"]: c["asset_label"] for c in check["checked"]},
                         {1: "asset 0 (BTC)", 2: "asset 0 (BTC)", 3: "asset 9 (spot_BTC)", 4: "asset 9 (spot_BTC)"})
        self.assertEqual(code, 0)

    def test_a_stand_whose_epoch_starts_mid_chain(self) -> None:
        # The chain holds 8 records; this stand's database starts at seq 5 — 1..4 are from an
        # earlier database against the same singleton. They are checked on chain only. A hole at
        # seq 2 in that old era does not fail a strict run for this stand.
        old = [Round(1), Round(3), Round(4)]
        new = [Round(s) for s in range(5, 9)]
        coinset = chain_of([r.message() for r in old], [r.message() for r in new])
        stand = FakeStand(new)  # journal firstSeq = 5
        result, code = run(coinset, stand, strict=True)
        check = result["stand_check"]
        self.assertEqual(check["stand_first_seq"], 5)
        self.assertEqual(result["scope"]["from_seq"], 5)
        self.assertEqual(check["below_scope"], [1, 3, 4])
        self.assertEqual(check["checked_count"], 4)
        self.assertEqual(check["mismatches"], [])
        self.assertEqual(result["seq_analysis"]["gaps"], [{"after_seq": 1, "before_seq": 3, "missing_seqs": [2]}])
        self.assertEqual(result["scope"]["missing_in_scope"], [])
        self.assertEqual(code, 0)
        # The older records were never looked up on the stand.
        self.assertEqual(sorted(stand.fetched), sorted(r.round_id for r in new))
        self.assertIn("Published before this stand's database (seq < 5): 3 (1, 3..4)", summary_text(result))

    def test_an_explicit_from_seq_overrides_the_stands_first_seq(self) -> None:
        rounds = [Round(s) for s in range(1, 7)]
        coinset, stand = world(rounds)
        result, _ = run(coinset, stand, from_seq=4)
        self.assertEqual(result["stand_check"]["below_scope"], [1, 2, 3])
        self.assertEqual(result["stand_check"]["checked_count"], 3)
        self.assertEqual(stand.record_calls[0][0], 4, "the journal is asked from --from-seq")
        self.assertIn("Below --from-seq 4: 3 (1..3)", summary_text(result))

    def test_a_tampered_results_json_is_a_hash_mismatch(self) -> None:
        rounds = [Round(1), Round(2), Round(3)]
        coinset, stand = world(rounds)
        tampered = json.loads(rounds[1].body)
        tampered["positions"] = [{"playerId": "mallory", "outcome": "WIN", "payoutMojos": 10**12}]
        stand.bodies[rounds[1].round_id] = canonical_json(tampered)
        result, code = run(coinset, stand)
        mismatches = result["stand_check"]["mismatches"]
        self.assertEqual([m["seq"] for m in mismatches], [2])
        self.assertFalse(mismatches[0]["hash_match"])
        self.assertTrue(mismatches[0]["price_match"])
        self.assertTrue(any("sha256" in p for p in mismatches[0]["problems"]))
        self.assertEqual(code, 1)
        self.assertIn("VERDICT: PROBLEMS FOUND", summary_text(result))

    def test_a_close_price_that_differs_from_the_chain_is_a_mismatch(self) -> None:
        rounds = [Round(1), Round(2, close_price=1)]
        # The chain anchors the real price of round 2; the stand's (self-consistent) document says 1.
        coinset = chain_of([rounds[0].message(), clvm_message(2, price=8_561_200, results_hash=rounds[1].results_hash)])
        result, code = run(coinset, FakeStand(rounds))
        mismatch = result["stand_check"]["mismatches"][0]
        self.assertEqual(mismatch["seq"], 2)
        self.assertTrue(mismatch["hash_match"])
        self.assertFalse(mismatch["price_match"])
        self.assertEqual(code, 1)

    def test_a_cancelled_round_with_no_close_price_matches(self) -> None:
        cancelled = Round(1, status="CANCELLED", close_price=None)
        coinset, stand = world([cancelled])
        result, code = run(coinset, stand)
        self.assertEqual(result["stand_check"]["mismatches"], [])
        self.assertEqual(code, 0)

    def test_a_results_json_the_stand_does_not_have_is_a_mismatch(self) -> None:
        rounds = [Round(1), Round(2)]
        coinset, stand = world(rounds)
        del stand.bodies[rounds[0].round_id]
        result, code = run(coinset, stand)
        self.assertEqual(result["stand_check"]["mismatches"][0]["problems"], ["results.json not found on stand"])
        self.assertEqual(code, 1)

    def test_the_asset_on_chain_must_equal_the_stands_asset(self) -> None:
        rounds = [Round(1, market="ETH", asset=3)]
        coinset = chain_of([rounds[0].message(asset=0)])  # the chain says BTC (6 elements)
        result, code = run(coinset, FakeStand(rounds))
        mismatch = result["stand_check"]["mismatches"][0]
        self.assertFalse(mismatch["asset_match"])
        self.assertTrue(any("assigns this round to asset 3" in p for p in mismatch["problems"]))
        self.assertEqual(code, 1)

        coinset = chain_of([rounds[0].message(asset=4)])  # a different non-zero asset
        result, code = run(coinset, FakeStand(rounds))
        self.assertFalse(result["stand_check"]["mismatches"][0]["asset_match"])
        self.assertEqual(code, 1)

    def test_a_registry_that_disagrees_with_the_journal_is_a_mismatch(self) -> None:
        rounds = [Round(1, market="SOL", asset=3)]  # the journal says SOL, the registry maps 3 to ETH
        coinset, stand = world(rounds)
        result, code = run(coinset, stand)
        problems = result["stand_check"]["mismatches"][0]["problems"]
        self.assertTrue(any("maps asset 3 to ETH but the round's market is SOL" in p for p in problems), problems)
        self.assertEqual(code, 1)

    def test_an_asset_missing_from_the_registry_is_a_mismatch(self) -> None:
        rounds = [Round(1, market="DOGE", asset=9)]
        coinset, stand = world(rounds)
        result, code = run(coinset, stand)
        self.assertTrue(any("not in the stand's own registry" in p for p in result["stand_check"]["mismatches"][0]["problems"]))
        self.assertEqual(code, 1)

    def test_a_results_json_naming_another_market_than_the_journal_is_a_mismatch(self) -> None:
        # Both the document and the journal carry a market, and they differ.
        eth = Round(1, market="ETH", asset=3)
        sol_body = canonical_json({**json.loads(eth.body), "market": "SOL"})
        sol = Round(1, market="ETH", asset=3)
        sol.body, sol.results_hash = sol_body, hashlib.sha256(sol_body).digest()
        coinset = chain_of([sol.message()])
        stand = FakeStand([sol])
        result, code = run(coinset, stand)
        self.assertTrue(any("says market 'SOL'" in p for p in result["stand_check"]["mismatches"][0]["problems"]))
        self.assertEqual(code, 1)

    def test_an_on_chain_seq_inside_the_scope_that_the_stand_cannot_map_fails_only_under_strict(self) -> None:
        rounds = [Round(5), Round(6), Round(7)]
        coinset = chain_of([r.message() for r in rounds])
        stand = FakeStand([rounds[0], rounds[2]])  # the stand's journal lacks seq 6
        result, code = run(coinset, stand)
        self.assertEqual(result["stand_check"]["unmapped_in_scope"], [6])
        self.assertEqual(result["scope"]["unmapped_in_scope"], [6])
        self.assertEqual(code, 0)
        self.assertEqual(v.exit_code(result, strict=True), 1)
        self.assertIn("inside the scope that no stand round maps to (FAILS under --strict): 6",
                      summary_text(result, strict=True))

    def test_stand_records_the_chain_does_not_have_yet_are_informational(self) -> None:
        rounds = [Round(1), Round(2), Round(3)]
        coinset = chain_of([rounds[0].message(), rounds[1].message()])
        stand = FakeStand(rounds)
        stand.journal["records"][2]["state"] = "queued"
        result, code = run(coinset, stand, strict=True)
        self.assertEqual([s["seq"] for s in result["stand_check"]["stand_only"]], [3])
        self.assertEqual(result["stand_check"]["stand_only"][0]["state"], "queued")
        self.assertEqual(code, 0)

    def test_an_empty_journal_means_every_on_chain_seq_predates_the_stand(self) -> None:
        coinset = chain_of([clvm_message(1), clvm_message(2)])
        stand = FakeStand([], first_seq=None)
        result, code = run(coinset, stand, strict=True)
        self.assertEqual(result["stand_check"]["below_scope"], [1, 2])
        self.assertEqual(result["stand_check"]["checked_count"], 0)
        self.assertEqual(code, 0)

    def test_the_journal_is_paged_by_limit_until_a_short_page(self) -> None:
        rounds = [Round(s) for s in range(1, 2501)]
        stand = FakeStand(rounds)
        journal = v.fetch_records_journal(stand, None)
        self.assertEqual(len(journal["records"]), 2500)
        self.assertEqual(journal["firstSeq"], 1)
        self.assertEqual(stand.record_calls, [(0, 1000), (1001, 1000), (2001, 1000)])

    def test_a_journal_that_ignores_from_seq_is_an_error_not_an_endless_loop(self) -> None:
        stand = FakeStand([Round(s) for s in range(1, 1001)])
        stand.oracle_records = lambda from_seq, limit: {  # type: ignore[method-assign]
            "firstSeq": 1, "records": [r for r in stand.journal["records"]][:limit]}
        with self.assertRaises(v.NetworkError):
            v.fetch_records_journal(stand, None)

    def test_a_malformed_journal_is_an_error(self) -> None:
        for bad in ({"firstSeq": 1}, {"firstSeq": 1, "records": "nope"},
                    {"firstSeq": 1, "records": [{"seq": "1", "roundId": "x"}]},
                    {"firstSeq": 1, "records": [{"seq": 1}]},
                    {"firstSeq": 1, "records": [{"seq": 1, "roundId": "x", "asset": "3"}]},
                    ["not", "an", "object"]):
            stand = FakeStand([])
            stand.oracle_records = lambda from_seq, limit, page=bad: page  # type: ignore[method-assign]
            with self.subTest(bad=bad), self.assertRaises(v.NetworkError):
                v.fetch_records_journal(stand, None)


# ======================================================================================
# A stand without /api/oracle/records: the BTC round walk
# ======================================================================================


def rpc_for(rounds: list[Round], *, step: int = 180_000, start: int = 1_790_000_000_000) -> dict:
    """The rounds/round RPC of an old stand holding `rounds` (BTC, hourly series): a running window
    after the last round, a SETTLED DTO with its oracleAnchor for each, 404 (None) before the first."""
    by_start = {start + r.seq * step: r for r in rounds}
    last = max(by_start)
    dtos = {
        r.round_id: {"phase": "SETTLED", "resultsHash": "0x" + r.results_hash.hex(),
                     "oracleAnchor": {"seq": r.seq, "state": "confirmed", "height": 4_735_705 + r.seq}}
        for r in rounds
    }
    # The running round (the window `rounds` reports) is a round the stand knows, not yet settled.
    dtos[f"1h-{last + step}"] = {"phase": "RUNNING"}
    return {
        "pairs": {"HOURLY": {"running": {"window": {"startMs": last + step, "endMs": last + 2 * step}}}, "DAILY": None},
        "rounds": dtos,
    }


class FallbackTests(unittest.TestCase):
    def old_stand(self, rounds: list[Round]) -> FakeStand:
        return FakeStand(rounds, journal=None, registry=None, rpc=rpc_for(rounds))

    def test_a_stand_without_the_records_endpoint_is_read_through_the_rounds_rpc(self) -> None:
        rounds = [Round(s) for s in range(1, 5)]
        coinset = chain_of([r.message() for r in rounds[:2]], [r.message() for r in rounds[2:]])
        result, code = run(coinset, self.old_stand(rounds), strict=True)
        check = result["stand_check"]
        self.assertEqual(check["mode"], "rounds-rpc-fallback")
        self.assertIsNone(check["stand_first_seq"])
        self.assertFalse(check["registry_published"])
        self.assertEqual(result["scope"]["from_seq"], 1)
        self.assertEqual(check["checked_count"], 4)
        self.assertEqual(check["mismatches"], [])
        self.assertEqual(code, 0)
        text = summary_text(result)
        self.assertIn("[rounds-rpc-fallback]", text)
        self.assertIn("no /api/oracle/records", text)

    def test_the_fallback_scope_starts_at_the_smallest_mapped_seq(self) -> None:
        old, mapped = [Round(1), Round(2)], [Round(3), Round(4)]
        coinset = chain_of([r.message() for r in old + mapped])
        result, code = run(coinset, self.old_stand(mapped), strict=True)
        check = result["stand_check"]
        self.assertEqual(result["scope"]["from_seq"], 3)
        self.assertEqual(check["below_scope"], [1, 2])
        self.assertEqual(check["checked_count"], 2)
        self.assertEqual(code, 0)

    def test_the_fallback_reports_an_unmapped_seq_inside_its_scope(self) -> None:
        rounds = [Round(1), Round(2), Round(3)]
        coinset = chain_of([r.message() for r in rounds])
        stand = self.old_stand([rounds[0], rounds[2]])
        # The stand's round for seq 2's window was cancelled: it has no anchor, and a cancelled
        # round in the middle of history does not stop the walk (Р-32).
        stand.rpc["rounds"][rounds[1].round_id] = {"phase": "CANCELLED"}
        result, code = run(coinset, stand)
        self.assertEqual(result["stand_check"]["unmapped_in_scope"], [2])
        self.assertEqual(code, 0)
        self.assertEqual(v.exit_code(result, strict=True), 1)

    def test_a_pre_multi_market_stand_is_btc_only(self) -> None:
        # The chain says asset 3 for a seq an old (BTC-only) stand maps to one of its rounds.
        rounds = [Round(1)]
        coinset = chain_of([rounds[0].message(asset=3)])
        result, code = run(coinset, self.old_stand(rounds))
        mismatch = result["stand_check"]["mismatches"][0]
        self.assertTrue(any("BTC only" in p for p in mismatch["problems"]), mismatch["problems"])
        self.assertEqual(code, 1)

    def test_the_fallback_still_catches_a_hash_mismatch(self) -> None:
        rounds = [Round(1), Round(2)]
        coinset = chain_of([r.message() for r in rounds])
        stand = self.old_stand(rounds)
        stand.bodies[rounds[0].round_id] = canonical_json({"closePriceCents": rounds[0].price, "roundId": "tampered"})
        result, code = run(coinset, stand)
        self.assertEqual([m["seq"] for m in result["stand_check"]["mismatches"]], [1])
        self.assertEqual(code, 1)

    def test_the_fallback_walk_stops_at_the_first_round_the_stand_does_not_know(self) -> None:
        rounds = [Round(1), Round(2), Round(3)]
        stand = self.old_stand(rounds)
        del stand.rpc["rounds"][rounds[0].round_id]  # the walk goes 3, 2, then 404 on 1
        with contextlib.redirect_stderr(io.StringIO()):  # "DAILY: no running round" is expected here
            found = v.discover_rounds(stand, verbose=False)
        self.assertEqual(sorted(found), [2, 3])


# ======================================================================================
# Failures: the network, bad arguments, exit codes
# ======================================================================================


class FailureTests(unittest.TestCase):
    def test_coinset_failure_is_exit_code_two_not_a_traceback(self) -> None:
        class DownCoinset:
            def __init__(self, *_a: Any, **_k: Any) -> None: ...

            def get_coin_record_by_name(self, _name: str) -> None:
                raise v.NetworkError("coinset API call /get_coin_record_by_name failed after 5 attempts: refused")

        err = io.StringIO()
        with mock.patch.object(v, "CoinsetClient", DownCoinset), contextlib.redirect_stderr(err), \
                contextlib.redirect_stdout(io.StringIO()):
            code = v.main([LAUNCHER.hex(), "-q"])
        self.assertEqual(code, 2)
        self.assertIn("error: coinset API call", err.getvalue())

    def test_a_stand_failure_is_exit_code_two(self) -> None:
        coinset = chain_of([clvm_message(1)])

        class DownStand:
            def __init__(self, *_a: Any, **_k: Any) -> None: ...

            def registry(self) -> None:
                raise v.NetworkError("cannot reach the stand at http://stand.test/api/markets/registry.json: refused")

        err = io.StringIO()
        with mock.patch.object(v, "CoinsetClient", lambda *_a, **_k: coinset), \
                mock.patch.object(v, "StandClient", DownStand), \
                contextlib.redirect_stderr(err), contextlib.redirect_stdout(io.StringIO()):
            code = v.main([LAUNCHER.hex(), "--stand-url", STAND_URL, "-q"])
        self.assertEqual(code, 2)
        self.assertIn("cannot reach the stand", err.getvalue())

    def test_main_exit_codes_for_ok_problem_and_strict(self) -> None:
        clean = chain_of([clvm_message(1), clvm_message(2)])
        gapped = chain_of([clvm_message(1), clvm_message(3)])
        conflicting = chain_of([clvm_message(1)], [clvm_message(1, asset=3)])

        def main_with(coinset: FakeCoinset, *argv: str) -> int:
            with mock.patch.object(v, "CoinsetClient", lambda *_a, **_k: coinset), \
                    contextlib.redirect_stderr(io.StringIO()), contextlib.redirect_stdout(io.StringIO()):
                return v.main([LAUNCHER.hex(), "-q", *argv])

        self.assertEqual(main_with(clean), 0)
        self.assertEqual(main_with(clean, "--strict"), 0)
        self.assertEqual(main_with(gapped), 0)
        self.assertEqual(main_with(gapped, "--strict"), 1)
        self.assertEqual(main_with(conflicting), 1)

    def test_a_missing_launcher_coin_is_exit_code_two(self) -> None:
        coinset = chain_of([clvm_message(1)])
        with mock.patch.object(v, "CoinsetClient", lambda *_a, **_k: coinset), \
                contextlib.redirect_stderr(io.StringIO()), contextlib.redirect_stdout(io.StringIO()):
            with self.assertRaises(SystemExit) as raised:
                v.main(["22" * 32, "-q"])
        self.assertEqual(raised.exception.code, 2)

    def test_bad_arguments_are_exit_code_two(self) -> None:
        for argv in (["not-a-coin-id"], ["abcd"], [LAUNCHER.hex(), "--from-seq", "-1"],
                     [LAUNCHER.hex(), "--max-generations", "0"], [LAUNCHER.hex(), "--from-seq", "x"], []):
            with self.subTest(argv=argv), contextlib.redirect_stderr(io.StringIO()):
                with self.assertRaises(SystemExit) as raised:
                    v.main(argv)
                self.assertEqual(raised.exception.code, 2)

    def test_a_launcher_id_with_0x_is_accepted(self) -> None:
        coinset = chain_of([clvm_message(1)])
        with mock.patch.object(v, "CoinsetClient", lambda *_a, **_k: coinset), \
                contextlib.redirect_stderr(io.StringIO()), contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(v.main(["0x" + LAUNCHER.hex(), "-q"]), 0)


class StandClientHttpTests(unittest.TestCase):
    """StandClient's own HTTP behaviour, with urlopen replaced: 404 is "the stand does not have this",
    everything else that is not a good answer is a NetworkError."""

    @staticmethod
    def response(body: bytes) -> Any:
        class Response(io.BytesIO):
            def __enter__(self) -> "Response":
                return self

            def __exit__(self, *_exc: Any) -> None:
                self.close()

        return Response(body)

    def http_error(self, code: int) -> urllib.error.HTTPError:
        error = urllib.error.HTTPError("http://stand.test/x", code, "status", {}, io.BytesIO(b""))  # type: ignore[arg-type]
        self.addCleanup(error.close)  # HTTPError wraps a temp file; closing it keeps the run free of ResourceWarnings
        return error

    def test_404_is_none_for_every_endpoint(self) -> None:
        stand = v.StandClient(STAND_URL)
        with mock.patch("urllib.request.urlopen", side_effect=self.http_error(404)):
            self.assertIsNone(stand.oracle_records(0, 1000))
            self.assertIsNone(stand.registry())
            self.assertIsNone(stand.results_json_bytes("1h-1"))
            self.assertIsNone(stand.round("1h-1"))
            self.assertIsNone(stand.rounds_pair("HOURLY"))

    def test_a_round_id_the_server_cannot_parse_is_a_round_it_does_not_have(self) -> None:
        stand = v.StandClient(STAND_URL)
        with mock.patch("urllib.request.urlopen", side_effect=self.http_error(400)):
            self.assertIsNone(stand.round("ETH-1h-1"))
            with self.assertRaises(v.NetworkError):
                stand.rounds_pair("HOURLY")

    def test_other_http_errors_unreachable_and_timeouts_are_network_errors(self) -> None:
        stand = v.StandClient(STAND_URL)
        for failure in (self.http_error(500), self.http_error(503), urllib.error.URLError("refused"),
                        TimeoutError("timed out"), ConnectionResetError("reset")):
            with self.subTest(failure=repr(failure)), mock.patch("urllib.request.urlopen", side_effect=failure):
                with self.assertRaises(v.NetworkError):
                    stand.oracle_records(0, 1000)
                with self.assertRaises(v.NetworkError):
                    stand.results_json_bytes("1h-1")

    def test_a_body_that_is_not_json_is_a_network_error(self) -> None:
        stand = v.StandClient(STAND_URL)
        with mock.patch("urllib.request.urlopen", side_effect=lambda *_a, **_k: self.response(b"<html>not json</html>")):
            with self.assertRaises(v.NetworkError):
                stand.oracle_records(0, 1000)
            with self.assertRaises(v.NetworkError):
                stand.registry()

    def test_the_records_url_carries_from_seq_and_limit(self) -> None:
        stand = v.StandClient(STAND_URL + "/")
        seen: list[str] = []

        def fake_urlopen(req: Any, timeout: float = 0) -> Any:
            seen.append(req.full_url)
            return self.response(b'{"firstSeq": 1, "records": []}')

        with mock.patch("urllib.request.urlopen", side_effect=fake_urlopen):
            self.assertEqual(stand.oracle_records(1146, 1000), {"firstSeq": 1, "records": []})
        self.assertEqual(seen, [f"{STAND_URL}/api/oracle/records?fromSeq=1146&limit=1000"])

    def test_the_results_url_quotes_the_round_id(self) -> None:
        stand = v.StandClient(STAND_URL)
        seen: list[str] = []

        def fake_urlopen(req: Any, timeout: float = 0) -> Any:
            seen.append(req.full_url)
            return self.response(b"{}")

        with mock.patch("urllib.request.urlopen", side_effect=fake_urlopen):
            stand.results_json_bytes("../../admin")
        self.assertEqual(seen, [f"{STAND_URL}/api/rounds/..%2F..%2Fadmin/results.json"])


class CompactRangesTests(unittest.TestCase):
    def test_compact_ranges(self) -> None:
        self.assertEqual(v.compact_ranges([]), "none")
        self.assertEqual(v.compact_ranges([5]), "5")
        self.assertEqual(v.compact_ranges([1, 2, 3, 7, 9, 10]), "1..3, 7, 9..10")


if __name__ == "__main__":
    unittest.main()
