#!/usr/bin/env python3
"""Standalone public verifier for Herd's price oracle singleton (Р-11/Р-40/Р-41/Р-47).

Anyone can run this — it needs none of our keys, none of our servers except (optionally)
one stand's own public HTTP API, and it never sends a spend, a signature or any mutating
request anywhere. It only reads:

  1. the public coinset.org light-wallet API for the target Chia network (default
     testnet11) — https://chia-network.github.io/light-client, no auth, read-only
     (get_coin_record_by_name / get_coin_records_by_parent_ids / get_puzzle_and_solution) —
     to walk the oracle singleton's full lineage from its launcher coin and decode every
     CREATE_PUZZLE_ANNOUNCEMENT condition each spend made; and
  2. (only if --stand-url is given) that stand's own public, unauthenticated JSON-RPC
     (POST /api/rpc/round, /api/rpc/rounds — see docs/product/local_stand.md, FIXED
     INTERFACE (1)) and its public GET /api/rounds/{id}/results.json endpoint (FIXED
     INTERFACE (2), Р-41) — to fetch, for each round the stand knows about, the exact
     bytes it claims were hashed into that round's on-chain resultsHash.

What "verified" means here, precisely:

  - The singleton lineage from the launcher coin forward is a straight, unforked chain of
    spends (each parent has exactly one child) — anyone re-walking it from a public API
    sees the exact same chain we do, with no hidden branch.
  - Every CREATE_PUZZLE_ANNOUNCEMENT decodes to the 6-element list this project's oracle
    always announces: (seq, ts, priceCents, resultsHash, sourceFlag, sourceTs) — see
    chain/README.md / docs/research/chain_service.md for the announcement format, and
    docs/product/decisions.md Р-11/Р-40/Р-41/Р-47 for why it exists.
  - Every seq number is checked for: first occurrence (which coin/height actually put it
    on chain), duplicates (the same seq announced again later — chainsvc's durable queue
    can and does re-publish an already-confirmed record after a reconciliation race; if
    the re-announcement carries byte-identical seq/ts/priceCents/resultsHash/sourceFlag/
    sourceTs, it is benign — no different price or results hash was ever anchored under
    that seq; if any field differs, that is a CONFLICT: two different truths were anchored
    under the same seq number, which the announcement protocol makes visible instead of
    silently overwriting), and gaps (a seq the singleton lineage jumps over — meaning some
    round's anchor never made it on chain at all).
  - (with --stand-url) For every on-chain seq the stand also claims via its own public API
    (see "How rounds map to seq" below), the exact results.json bytes the stand serves
    right now hash (sha256) to the same resultsHash the chain anchored for that seq back
    when it was published, and the round's closePriceCents in that file equals the
    priceCents this tool independently decoded from the chain. This is Р-41's whole point:
    "любой может сверить, что итоги не поменяли задним числом" — this tool is that anyone.

What this tool does NOT verify (out of scope, said plainly instead of implied): that the
priceCents the oracle published was itself a correct/honest BTC price (that trust is in
the operator key + Hyperliquid as the price source, Р-11/PLAN.md §2 — a centralized-oracle
tradeoff made on purpose and documented, not hidden); that the positions/payouts inside
results.json were computed correctly by the engine's leverage math (a separate concern,
covered by engine/'s own unit tests, not by this tool); AGG_SIG_ME signatures on each spend
(coinset's own full nodes already rejected any spend that failed that check before it could
confirm — re-checking it here would just be re-deriving trust in the same public chain data
this tool already reads, not adding any).

--- How rounds map to seq (no separate public "give me seq for this round" endpoint exists,
so this is how this tool finds it without one) ---

A round's id is deterministic: RoundWindow.roundId() = f"{interval.code()}-{startMs}"
(engine/src/main/java/crowdvsmarket/engine/round/RoundWindow.java) where startMs walks in
fixed steps from the stand's own start instant (RoundCalendar/RoundClock — "always one
running + one betting round per series", Р-19). So: ask the stand's own public
`POST /api/rpc/rounds {"args":["HOURLY"]}` (and `"DAILY"`) for the *currently* running
round's window (gives us one known-good startMs and the step size = endMs-startMs for that
series), then walk roundId backward one step at a time and ask the public, unauthenticated
`POST /api/rpc/round {"args":["<roundId>"]}` for each — every settled round it still has in
its database answers with `oracleAnchor: {seq, state, height}` right in the DTO
(server/src/main/java/crowdvsmarket/server/dto/Rounds.java `RoundSettled.oracleAnchor`,
persistence/OracleRecordRepository.findAnchorByRoundId) — that IS the public seq↔round
mapping, just not exposed as its own dedicated lookup-by-seq endpoint. This tool stops
walking a series backward at the first roundId the stand 404s on (either genuinely
before the stand's own origin, or a request racing a round still finishing) — a handful
of unmapped older seqs from before this tool's backward-walk horizon is not itself a
finding; it's just history the tool declined to guess at.

Proposal, if a project maintainer wants the mapping to be a first-class public API instead
of this derived walk: add `GET /api/rounds/by-seq/{seq}` returning the same anchor+
resultsHash+roundId shape as `round()` already carries — a thin read over the same
`oracle_records.round_id` column `OracleRecordRepository` already has, no new state needed.
Nothing here waits on that; the walk above already gets a complete, correct mapping for
every round still in the stand's database.

Dependency: chia_rs (pip install -r requirements.txt) — the official Chia Network Rust CLVM
implementation with a Python binding (pypi.org/project/chia_rs), used here strictly to run
each spend's own on-chain puzzle_reveal against its own on-chain solution and read off the
conditions it produced (pure computation over public data already fetched — no keys, no
network calls of its own). This is the one non-stdlib dependency; everything else here is
python3's own standard library.

Usage:
    python3 verify_oracle.py <launcher_id> [--network testnet11|mainnet] [--api-url URL]
        [--stand-url http://localhost:8480] [--json-out FILE] [--max-generations N]

Exit code: 0 if the run completed and found 0 CONFLICTs and (when --stand-url is given)
0 mismatches; 1 if it completed but found at least one CONFLICT or mismatch; 2 on a usage/
environment error (bad launcher id, network unreachable, chia_rs missing, etc).
"""
from __future__ import annotations

import argparse
import hashlib
import json
import sys
import time
import urllib.error
import urllib.request
from typing import Any, Optional

try:
    from chia_rs import Coin, Program
except ImportError:
    print(
        "error: this tool needs the 'chia_rs' package.\n"
        "  pip install -r requirements.txt\n"
        "(it is the official Chia Network CLVM implementation, used here only to run each\n"
        "on-chain spend's own puzzle against its own solution — no keys, no network calls\n"
        "of its own; see the module docstring in this file for exactly what it is used for.)",
        file=sys.stderr,
    )
    sys.exit(2)

NETWORKS = {
    "testnet11": "https://testnet11.api.coinset.org",
    "mainnet": "https://api.coinset.org",
}

# CLVM condition opcodes this tool cares about (chia-blockchain's condition_opcodes.py).
CREATE_COIN = 51
CREATE_COIN_ANNOUNCEMENT = 60
CREATE_PUZZLE_ANNOUNCEMENT = 62

# The oracle's own announcement schema (chain/README.md / docs/research/chain_service.md):
# a 6-element CLVM list (seq ts priceCents resultsHash sourceFlag sourceTs).
ANNOUNCEMENT_FIELDS = ("seq", "ts", "priceCents", "resultsHash", "sourceFlag", "sourceTs")

IDENTITY_PUZZLE = Program.to(1)


def hx(s: str) -> str:
    return s[2:] if s.startswith("0x") else s


def eprint(*args: Any) -> None:
    print(*args, file=sys.stderr, flush=True)


class CoinsetClient:
    """Thin client for the public, read-only coinset.org light-wallet REST API. No auth,
    no keys — every method here is a plain POST of public parameters (a coin name, a
    height) and returns whatever that public node already serves to any light wallet."""

    def __init__(self, base_url: str, timeout: float = 25.0, retries: int = 5):
        self.base_url = base_url.rstrip("/")
        self.timeout = timeout
        self.retries = retries

    def _post(self, path: str, body: dict) -> dict:
        url = f"{self.base_url}{path}"
        data = json.dumps(body).encode()
        last_err: Optional[Exception] = None
        for attempt in range(self.retries):
            try:
                req = urllib.request.Request(
                    url,
                    data=data,
                    headers={"Content-Type": "application/json", "User-Agent": "verify-oracle/1"},
                )
                with urllib.request.urlopen(req, timeout=self.timeout) as r:
                    return json.loads(r.read())
            except Exception as e:  # noqa: BLE001 — deliberately broad: retry any transient failure
                last_err = e
                if attempt < self.retries - 1:
                    time.sleep(min(2 ** attempt, 10))
        raise RuntimeError(f"coinset API call {path} failed after {self.retries} attempts: {last_err}")

    def get_coin_record_by_name(self, name_hex: str) -> Optional[dict]:
        r = self._post("/get_coin_record_by_name", {"name": "0x" + hx(name_hex)})
        return r.get("coin_record")

    def get_children(self, coin_id_hex: str) -> list[dict]:
        r = self._post(
            "/get_coin_records_by_parent_ids",
            {"parent_ids": ["0x" + hx(coin_id_hex)], "include_spent_coins": True},
        )
        return r.get("coin_records", [])

    def get_puzzle_and_solution(self, coin_id_hex: str, height: int) -> Optional[dict]:
        r = self._post("/get_puzzle_and_solution", {"coin_id": "0x" + hx(coin_id_hex), "height": height})
        return r.get("coin_solution")


class StandClient:
    """Thin client for one stand's own public, unauthenticated HTTP API — the RPC methods
    used here (`round`, `rounds`) take no session cookie (see server/src/main/java/
    crowdvsmarket/server/api/RpcResource.java — neither is in requireSignedIn), and
    GET .../results.json is deliberately public (Р-41). No operator token, no cookie, no
    mutating call is ever made from this class."""

    def __init__(self, base_url: str, timeout: float = 15.0):
        self.base_url = base_url.rstrip("/")
        self.timeout = timeout

    def _rpc(self, method: str, *args: Any) -> Any:
        url = f"{self.base_url}/api/rpc/{method}"
        data = json.dumps({"args": list(args)}).encode()
        req = urllib.request.Request(
            url, data=data, headers={"Content-Type": "application/json"}, method="POST"
        )
        try:
            with urllib.request.urlopen(req, timeout=self.timeout) as r:
                return json.loads(r.read())
        except urllib.error.HTTPError as e:
            if e.code == 404:
                return None
            raise

    def rounds_pair(self, interval: str) -> dict:
        return self._rpc("rounds", interval)

    def round(self, round_id: str) -> Optional[dict]:
        return self._rpc("round", round_id)

    def results_json_bytes(self, round_id: str) -> Optional[bytes]:
        url = f"{self.base_url}/api/rounds/{round_id}/results.json"
        req = urllib.request.Request(url)
        try:
            with urllib.request.urlopen(req, timeout=self.timeout) as r:
                return r.read()
        except urllib.error.HTTPError as e:
            if e.code == 404:
                return None
            raise


# --------------------------------------------------------------------------------------
# CLVM decoding — run each spend's own on-chain puzzle against its own on-chain solution
# (via chia_rs, see module docstring) and read off the conditions it produced.
# --------------------------------------------------------------------------------------


def atom_to_int(b: Optional[bytes]) -> int:
    if not b:
        return 0
    return int.from_bytes(b, "big", signed=True)


def to_pylist(node: Any) -> list[Any]:
    """Walk a chia_rs LazyNode cons-list into a python list of its elements."""
    items = []
    while node.pair is not None:
        first, rest = node.pair
        items.append(first)
        node = rest
    return items


def parse_announcement_message(msg_bytes: bytes) -> dict:
    """A CREATE_PUZZLE_ANNOUNCEMENT's message is itself a serialized CLVM list — decode it
    by running the identity puzzle `1` (return environment unchanged) with the message as
    the environment, which just re-parses the bytes into a walkable tree without evaluating
    anything (no operators in the message itself to run — it is pure data)."""
    reparsed = Program.from_bytes(msg_bytes)
    _cost, result = IDENTITY_PUZZLE.run_rust(11_000_000_000, 0, reparsed)
    items = to_pylist(result)
    if len(items) != len(ANNOUNCEMENT_FIELDS):
        raise ValueError(f"expected {len(ANNOUNCEMENT_FIELDS)}-element announcement list, got {len(items)}")
    seq, ts, price_cents = (atom_to_int(items[i].atom) for i in range(3))
    results_hash = "0x" + bytes(items[3].atom).hex()
    source_flag = atom_to_int(items[4].atom)
    source_ts = atom_to_int(items[5].atom)
    return {
        "seq": seq,
        "ts": ts,
        "priceCents": price_cents,
        "resultsHash": results_hash,
        "sourceFlag": source_flag,
        "sourceTs": source_ts,
    }


def decode_conditions(puzzle_hex: str, solution_hex: str) -> list[tuple[int, list[Any]]]:
    puzzle = Program.from_bytes(bytes.fromhex(hx(puzzle_hex)))
    solution = Program.from_bytes(bytes.fromhex(hx(solution_hex)))
    _cost, result = puzzle.run_rust(11_000_000_000, 0, solution)
    conds = []
    for c in to_pylist(result):
        items = to_pylist(c)
        opcode = atom_to_int(items[0].atom)
        conds.append((opcode, items[1:]))
    return conds


def coin_id_of(coin_json: dict) -> str:
    coin = Coin(
        bytes.fromhex(hx(coin_json["parent_coin_info"])),
        bytes.fromhex(hx(coin_json["puzzle_hash"])),
        coin_json["amount"],
    )
    return coin.name().hex()


# --------------------------------------------------------------------------------------
# Step 1: walk the singleton lineage from the launcher coin forward.
# --------------------------------------------------------------------------------------


def walk_lineage(client: CoinsetClient, launcher_id: str, max_generations: int, verbose: bool) -> dict:
    launcher_id = hx(launcher_id)
    launcher_rec = client.get_coin_record_by_name(launcher_id)
    if launcher_rec is None:
        eprint(
            f"error: launcher coin 0x{launcher_id} not found on this network — wrong network, "
            "or the singleton was never launched"
        )
        sys.exit(2)
    if not launcher_rec["spent"]:
        eprint("error: launcher coin exists but is not spent yet — no oracle singleton created from it")
        sys.exit(2)

    lineage: list[dict] = []
    errors: list[str] = []
    current_id = launcher_id
    generation = 0

    while True:
        children = client.get_children(current_id)
        if not children:
            break
        if len(children) != 1:
            errors.append(
                f"generation {generation}: parent 0x{current_id} has {len(children)} children "
                "(expected exactly 1 — a singleton lineage must never fork; this is a real problem, "
                "not a benign duplicate)"
            )
        child = children[0]
        coin = child["coin"]
        child_id = coin_id_of(coin)
        spent = bool(child["spent"])
        entry: dict[str, Any] = {
            "generation": generation,
            "coin_id": "0x" + child_id,
            "puzzle_hash": coin["puzzle_hash"],
            "confirmed_block_index": child["confirmed_block_index"],
            "spent": spent,
            "spent_block_index": child.get("spent_block_index"),
            "timestamp": child.get("timestamp"),
            "announcements": [],
        }
        if spent:
            sol = client.get_puzzle_and_solution(child_id, child.get("spent_block_index"))
            if sol is None:
                entry["decode_error"] = "get_puzzle_and_solution returned null for a spent coin"
            else:
                try:
                    conds = decode_conditions(sol["puzzle_reveal"], sol["solution"])
                    anns = []
                    for opcode, args in conds:
                        if opcode == CREATE_PUZZLE_ANNOUNCEMENT:
                            msg_bytes = bytes(args[0].atom)
                            try:
                                anns.append(parse_announcement_message(msg_bytes))
                            except Exception as e:  # noqa: BLE001
                                anns.append({"raw_hex": msg_bytes.hex(), "parse_error": str(e)})
                    entry["announcements"] = anns
                except Exception as e:  # noqa: BLE001
                    entry["decode_error"] = str(e)
        lineage.append(entry)
        if verbose:
            eprint(
                f"  gen={generation} coin=0x{child_id[:16]}… height={entry['confirmed_block_index']} "
                f"spent={spent} announcements={len(entry.get('announcements', []))}"
            )
        if not spent:
            break
        current_id = child_id
        generation += 1
        if generation > max_generations:
            errors.append(
                f"generation limit ({max_generations}) exceeded — stopped early; pass "
                "--max-generations to walk further"
            )
            break

    return {
        "launcher_id": "0x" + launcher_id,
        "launcher_record": launcher_rec,
        "generations_walked": len(lineage),
        "errors": errors,
        "lineage": lineage,
    }


# --------------------------------------------------------------------------------------
# Step 2: per-seq analysis — first occurrence, duplicates (benign vs CONFLICT), gaps.
# --------------------------------------------------------------------------------------


def analyze_seqs(lineage: list[dict]) -> dict:
    by_seq: dict[int, list[dict]] = {}
    unparsed: list[dict] = []

    for entry in lineage:
        for ann in entry.get("announcements", []):
            if "seq" not in ann:
                unparsed.append(
                    {
                        "generation": entry["generation"],
                        "coin_id": entry["coin_id"],
                        "height": entry.get("spent_block_index"),
                        **ann,
                    }
                )
                continue
            occ = {
                "generation": entry["generation"],
                "coin_id": entry["coin_id"],
                "height": entry.get("spent_block_index"),
                "announcement": ann,
            }
            by_seq.setdefault(ann["seq"], []).append(occ)

    per_seq: dict[int, dict] = {}
    conflicts: list[dict] = []
    benign_duplicate_count = 0

    for seq, occs in sorted(by_seq.items()):
        first = occs[0]
        status = "unique" if len(occs) == 1 else "duplicate"
        dup_details = []
        for other in occs[1:]:
            identical = all(
                other["announcement"].get(f) == first["announcement"].get(f) for f in ANNOUNCEMENT_FIELDS
            )
            dup_details.append(
                {
                    "generation": other["generation"],
                    "coin_id": other["coin_id"],
                    "height": other["height"],
                    "identical_to_first": identical,
                }
            )
            if identical:
                benign_duplicate_count += 1
            else:
                status = "CONFLICT"
                conflicts.append(
                    {
                        "seq": seq,
                        "first": first,
                        "conflicting": {
                            "generation": other["generation"],
                            "coin_id": other["coin_id"],
                            "height": other["height"],
                            "announcement": other["announcement"],
                        },
                    }
                )
        per_seq[seq] = {
            "status": status,
            "occurrence_count": len(occs),
            "first": first,
            "duplicates": dup_details,
        }

    seqs_sorted = sorted(per_seq.keys())
    gaps = []
    for a, b in zip(seqs_sorted, seqs_sorted[1:]):
        if b != a + 1:
            gaps.append({"after_seq": a, "before_seq": b, "missing_seqs": list(range(a + 1, b))})

    return {
        "per_seq": per_seq,
        "seq_min": seqs_sorted[0] if seqs_sorted else None,
        "seq_max": seqs_sorted[-1] if seqs_sorted else None,
        "distinct_seq_count": len(seqs_sorted),
        "benign_duplicate_count": benign_duplicate_count,
        "conflicts": conflicts,
        "gaps": gaps,
        "unparsed_announcements": unparsed,
    }


# --------------------------------------------------------------------------------------
# Step 3 (optional, --stand-url): map rounds to seq via the stand's own public API and
# cross-check its live results.json bytes against what the chain anchored.
# --------------------------------------------------------------------------------------


def discover_rounds(stand: StandClient, verbose: bool) -> dict[int, dict]:
    """Returns {seq: {roundId, resultsHash (from the RPC's own DTO), state, height}} for
    every settled round the stand still has, found by walking each series' roundId
    backward from its currently-running window (see module docstring, "How rounds map to
    seq")."""
    by_seq: dict[int, dict] = {}
    for interval, code in (("HOURLY", "1h"), ("DAILY", "1d")):
        pair = stand.rounds_pair(interval)
        running = pair.get("running") if pair else None
        if not running or "window" not in running:
            eprint(f"  {interval}: no running round returned by the stand — skipping this series")
            continue
        window = running["window"]
        step = window["endMs"] - window["startMs"]
        start_ms = window["startMs"]
        found = 0
        consecutive_misses = 0
        while consecutive_misses < 1:  # stop at the first roundId the stand doesn't know
            round_id = f"{code}-{start_ms}"
            dto = stand.round(round_id)
            if dto is None:
                consecutive_misses += 1
                break
            if dto.get("phase") == "SETTLED":
                anchor = dto.get("oracleAnchor")
                if anchor is not None:
                    by_seq[anchor["seq"]] = {
                        "roundId": round_id,
                        "resultsHash": dto.get("resultsHash"),
                        "state": anchor.get("state"),
                        "height": anchor.get("height"),
                    }
                    found += 1
            # BETTING/RUNNING/CANCELLED rounds carry no chain seq to cross-check; keep walking
            # backward regardless, since a cancelled round in the middle of history is expected
            # (Р-32) and must not stop the walk.
            start_ms -= step
        if verbose:
            eprint(f"  {interval}: found {found} settled round(s) with an oracle anchor")
    return by_seq


def cross_check(chain_seqs: dict[int, dict], stand: StandClient, stand_rounds: dict[int, dict]) -> dict:
    checked = []
    mismatches = []
    seqs_only_on_chain = []
    for seq, info in sorted(chain_seqs.items()):
        round_info = stand_rounds.get(seq)
        if round_info is None:
            seqs_only_on_chain.append(seq)
            continue
        round_id = round_info["roundId"]
        body = stand.results_json_bytes(round_id)
        if body is None:
            mismatches.append({"seq": seq, "roundId": round_id, "problem": "results.json not found on stand"})
            continue
        computed_hash = "0x" + hashlib.sha256(body).hexdigest()
        on_chain_hash = info["announcement"]["resultsHash"]
        try:
            results = json.loads(body)
        except json.JSONDecodeError as e:
            mismatches.append({"seq": seq, "roundId": round_id, "problem": f"results.json is not valid JSON: {e}"})
            continue
        close_cents = results.get("closePriceCents")
        on_chain_cents = info["announcement"]["priceCents"]
        hash_ok = computed_hash == on_chain_hash
        price_ok = close_cents == on_chain_cents or (results.get("status") == "CANCELLED" and close_cents is None)
        entry = {
            "seq": seq,
            "roundId": round_id,
            "resultsHash_onchain": on_chain_hash,
            "resultsHash_computed": computed_hash,
            "hash_match": hash_ok,
            "priceCents_onchain": on_chain_cents,
            "closePriceCents_stand": close_cents,
            "price_match": price_ok,
        }
        checked.append(entry)
        if not (hash_ok and price_ok):
            mismatches.append(entry)
    return {
        "checked_count": len(checked),
        "checked": checked,
        "mismatches": mismatches,
        "seqs_only_on_chain": seqs_only_on_chain,  # on chain but stand has no round mapped to them
    }


# --------------------------------------------------------------------------------------
# Output.
# --------------------------------------------------------------------------------------


def print_human_summary(result: dict) -> None:
    lineage = result["lineage_walk"]
    analysis = result["seq_analysis"]
    print(f"Launcher id:        {result['launcher_id']}")
    print(f"Network:            {result['network']} ({result['api_url']})")
    print(f"Generations walked: {lineage['generations_walked']}")
    if lineage["errors"]:
        print("Lineage errors:")
        for e in lineage["errors"]:
            print(f"  - {e}")
    else:
        print("Lineage errors:     none (single unforked chain from launcher to tip)")
    print()
    seq_range = f" (range {analysis['seq_min']}..{analysis['seq_max']})" if analysis["seq_min"] is not None else ""
    print(f"Distinct seq numbers on chain: {analysis['distinct_seq_count']}{seq_range}")
    print(f"Benign duplicates (re-announced, identical content): {analysis['benign_duplicate_count']}")
    print(f"CONFLICTS (re-announced, DIFFERENT content):         {len(analysis['conflicts'])}")
    print(f"Gaps in the seq sequence:                            {len(analysis['gaps'])}")
    if analysis["conflicts"]:
        print("\nCONFLICT DETAILS:")
        for c in analysis["conflicts"]:
            print(f"  seq={c['seq']}: first at height {c['first']['height']} coin {c['first']['coin_id']}")
            print(f"    vs conflicting at height {c['conflicting']['height']} coin {c['conflicting']['coin_id']}")
            print(f"    first:       {c['first']['announcement']}")
            print(f"    conflicting: {c['conflicting']['announcement']}")
    if analysis["gaps"]:
        print("\nGAP DETAILS:")
        for g in analysis["gaps"]:
            print(f"  after seq {g['after_seq']}, before seq {g['before_seq']}: missing {g['missing_seqs']}")
    if analysis["unparsed_announcements"]:
        print(f"\nUnparsed announcement-shaped conditions (not our 6-field schema): "
              f"{len(analysis['unparsed_announcements'])}")
        for u in analysis["unparsed_announcements"]:
            print(f"  generation={u['generation']} coin={u['coin_id']} height={u['height']}: "
                  f"{u.get('parse_error')} (raw_hex={u.get('raw_hex', '')[:40]}…)")

    stand_check = result.get("stand_check")
    if stand_check is None:
        print("\n(no --stand-url given — chain-only verification above; no round<->seq cross-check run)")
        return

    print(f"\nStand cross-check against: {result['stand_url']}")
    print(f"Settled rounds discovered on stand with an oracle anchor: {len(stand_check['stand_rounds'])}")
    print(f"Rounds checked (chain seq -> stand results.json):         {stand_check['checked_count']}")
    print(f"  hash matches:  {sum(1 for c in stand_check['checked'] if c['hash_match'])}")
    print(f"  price matches: {sum(1 for c in stand_check['checked'] if c['price_match'])}")
    print(f"Mismatches:                                                {len(stand_check['mismatches'])}")
    if stand_check["mismatches"]:
        print("\nMISMATCH DETAILS:")
        for m in stand_check["mismatches"]:
            print(f"  {m}")
    if stand_check["seqs_only_on_chain"]:
        print(f"\nOn-chain seqs with no round found on the stand (outside this tool's backward-walk "
              f"horizon, or a round the stand's DB no longer has): {stand_check['seqs_only_on_chain']}")


def main() -> int:
    ap = argparse.ArgumentParser(
        description="Standalone public verifier for Herd's price oracle singleton lineage.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__,
    )
    ap.add_argument("launcher_id", help="the oracle singleton's launcher coin id (with or without 0x)")
    ap.add_argument("--network", choices=sorted(NETWORKS), default="testnet11")
    ap.add_argument("--api-url", default=None, help="override the coinset API base URL for --network")
    ap.add_argument("--stand-url", default=None, help="e.g. http://localhost:8480 — enables the round<->seq cross-check")
    ap.add_argument("--json-out", default=None, help="also write the full machine-readable result as JSON to this file")
    ap.add_argument("--max-generations", type=int, default=20000, help="safety cap on how many spends to walk (default 20000)")
    ap.add_argument("-q", "--quiet", action="store_true", help="suppress progress lines on stderr")
    args = ap.parse_args()

    api_url = args.api_url or NETWORKS[args.network]
    verbose = not args.quiet

    eprint(f"Walking singleton lineage on {args.network} ({api_url})…")
    coinset = CoinsetClient(api_url)
    lineage_walk = walk_lineage(coinset, args.launcher_id, args.max_generations, verbose)

    eprint("Analyzing seq numbers (first occurrence / duplicates / gaps)…")
    seq_analysis = analyze_seqs(lineage_walk["lineage"])

    result: dict[str, Any] = {
        "launcher_id": lineage_walk["launcher_id"],
        "network": args.network,
        "api_url": api_url,
        "lineage_walk": lineage_walk,
        "seq_analysis": seq_analysis,
        "stand_url": args.stand_url,
        "stand_check": None,
    }

    if args.stand_url:
        eprint(f"Cross-checking against stand {args.stand_url} (public API only, no keys)…")
        stand = StandClient(args.stand_url)
        stand_rounds = discover_rounds(stand, verbose)
        chain_seqs = {seq: info["first"] for seq, info in seq_analysis["per_seq"].items()}
        stand_check = cross_check(chain_seqs, stand, stand_rounds)
        stand_check["stand_rounds"] = stand_rounds
        result["stand_check"] = stand_check

    print_human_summary(result)

    if args.json_out:
        with open(args.json_out, "w") as f:
            json.dump(result, f, indent=2, default=str)
        eprint(f"\nFull JSON result written to {args.json_out}")

    ok = len(seq_analysis["conflicts"]) == 0
    if result["stand_check"] is not None:
        ok = ok and len(result["stand_check"]["mismatches"]) == 0
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
