#!/usr/bin/env python3
"""Standalone public verifier for Herd's price oracle singleton (Р-11/Р-40/Р-41/Р-47), v2.

Anyone can run this — it needs none of our keys, none of our servers except (optionally)
one stand's own public HTTP API, and it never sends a spend, a signature or any mutating
request anywhere. It only reads:

  1. the public coinset.org light-wallet API for the target Chia network (default
     testnet11) — https://chia-network.github.io/light-client, no auth, read-only
     (get_coin_record_by_name / get_coin_records_by_parent_ids / get_puzzle_and_solution) —
     to walk the oracle singleton's full lineage from its launcher coin and decode every
     CREATE_PUZZLE_ANNOUNCEMENT condition each spend made; and
  2. (only if --stand-url is given) that stand's own public, unauthenticated HTTP API:
     GET /api/oracle/records (the journal of every oracle record the stand queued, with the
     round each belongs to), GET /api/markets/registry.json (asset index -> market), and
     GET /api/rounds/{id}/results.json (FIXED INTERFACE (2), Р-41) — to fetch, for each round
     the stand claims, the exact bytes it says were hashed into that round's on-chain
     resultsHash. A stand that predates the journal is read through its public JSON-RPC
     (POST /api/rpc/rounds, /api/rpc/round — docs/product/local_stand.md, FIXED INTERFACE (1))
     instead, for BTC only.

What "verified" means here, precisely:

  - The singleton lineage from the launcher coin forward is a straight, unforked chain of
    spends (each parent has exactly one child) — anyone re-walking it from a public API
    sees the exact same chain we do, with no hidden branch.
  - Every CREATE_PUZZLE_ANNOUNCEMENT decodes to the list this project's oracle announces:
    (seq, ts, priceCents, resultsHash, sourceFlag, sourceTs) — 6 elements — or, from record
    format v2 on, the same six plus a seventh `asset`, the index of the record's market in the
    public registry. An announcement of a BTC record (asset 0) stays 6 elements, byte for byte
    what it always was, so the 7th element is present only when asset > 0 and a missing one
    reads as asset 0. Any other shape (the very first, week-1 spend announced a 3-element
    (priceCents ts seq); a short or long list; an `asset` outside 0..2^32-1; a resultsHash
    that is not 32 bytes) is not a record and is listed under "unparsed", never guessed at.
    See chain/README.md / docs/research/chain_service.md §3.1 and §16 for the format, and
    docs/product/decisions.md Р-11/Р-40/Р-41/Р-47 for why it exists.
  - Every seq number is checked for: first occurrence (which coin/height actually put it
    on chain), duplicates (the same seq announced again later — chainsvc's durable queue
    can and does re-publish an already-confirmed record after a reconciliation race; if
    the re-announcement carries identical seq/ts/priceCents/resultsHash/sourceFlag/
    sourceTs/asset, it is benign — nothing different was ever anchored under that seq; if
    any field differs — including only the asset — that is a CONFLICT: two different truths
    were anchored under the same seq number, which the announcement protocol makes visible
    instead of silently overwriting), and gaps (a seq the singleton lineage jumps over —
    meaning some round's anchor never made it on chain at all).
  - (with --stand-url) For every on-chain seq in scope that the stand also claims (see "How
    rounds map to seq" below), the exact results.json bytes the stand serves right now hash
    (sha256) to the same resultsHash the chain anchored for that seq back when it was
    published, the round's closePriceCents in that file equals the priceCents this tool
    independently decoded from the chain, and the asset index on chain equals the asset the
    stand assigns to that round (and its registry maps that index to that round's market).
    This is Р-41's whole point: "любой может сверить, что итоги не поменяли задним числом" —
    this tool is that anyone.
  - (with --stand-url) Which exchange a crypto round ran on (Р-76). A market that has a backup
    exchange (the registry's `backup`) opens each round on its main exchange or on the backup and
    runs on that one exchange to the end; the round says which in a root key "venue" of its
    results.json — so the venue is inside the hash the chain anchored, and priceCents is the
    close ON THAT EXCHANGE — and the journal repeats it per record. The two must agree (a round
    whose document names another exchange than the journal, or only one of them names one, is a
    mismatch), and the venue must be the market's main or backup exchange in the stand's
    registry. A round with no venue (every round of a market without a backup, every BTC round
    before multi-venue, a cancelled round that never got one) has no "venue" key, and its
    document is byte for byte what it always was.

What this tool does NOT verify (out of scope, said plainly instead of implied): that the
priceCents the oracle published was itself a correct/honest price on the exchange it names (that
trust is in the operator key + the price source, Hyperliquid, Coinbase, Kraken or Polymarket,
Р-11/PLAN.md §2 — a centralized-oracle tradeoff made on purpose and documented, not hidden; the
venue only tells you WHICH exchange to compare it against); that the positions/payouts
inside results.json were computed correctly by the engine's leverage math (a separate
concern, covered by engine/'s own unit tests, not by this tool); AGG_SIG_ME signatures on
each spend (coinset's own full nodes already rejected any spend that failed that check before
it could confirm — re-checking it here would just be re-deriving trust in the same public
chain data this tool already reads, not adding any).

--- How rounds map to seq ---

Preferred: the stand's records journal, `GET /api/oracle/records?fromSeq=N&limit=1000` →
`{firstSeq, records: [{seq, roundId, marketId, asset, ts, priceCents, resultsHash, state, venue}]}`
(`venue` is null or absent for a round that ran on no particular exchange),
paged by `limit` (1000) until a short page. It is a thin read over the stand's own
`oracle_records` table, so it covers every market and every round the stand ever queued, not
only the ones a walk could reach. `firstSeq` is the lowest seq in that table: the start of THIS
stand's database. A chain seq below it was published before this stand's database existed
(an earlier database against the same singleton): such seqs are reported as "published before
this stand's database" and checked on chain only (lineage, duplicates, conflicts, gaps) — there
is no stand round to compare them with, and that is not a mismatch.

Fallback, for a stand without the journal (HTTP 404 on /api/oracle/records): the BTC-only
discovery this tool always had. Ask the stand's public `POST /api/rpc/rounds {"args":["HOURLY"]}`
(and "DAILY") for the *currently* running round's window (one known-good startMs and the step
size = endMs-startMs for that series), walk the roundId `f"{code}-{startMs}"` backward one
step at a time and ask the public `POST /api/rpc/round {"args":["<roundId>"]}` for each — every
settled round the stand still has answers with `oracleAnchor: {seq, state, height}` in its
DTO. The walk stops at the first roundId the stand does not know. Such a stand is BTC-only
(asset 0): a chain seq it maps to a non-zero asset is a mismatch. With no journal there is no
`firstSeq`; the scope starts at the smallest seq the stand maps.

--- Scope, --from-seq and --strict ---

The scope is the range of seqs this run holds the stand to account for: `--from-seq N` if
given, otherwise the stand's `firstSeq` (or, in the fallback, the smallest seq it maps), and
the whole chain when there is no stand. Inside the scope, a chain seq the stand cannot map to
a round is "unmapped" (the chain holds an announcement the stand knows nothing about — e.g. a
foreign or stale record inside this stand's epoch) and a seq the lineage skips is a gap.
Both are always printed; by default they do not change the exit code (they are history a
reader may already know about); with `--strict` either one makes the run fail. Gaps below the
scope belong to an earlier era and never fail a strict run.

Dependency: chia_rs (pip install -r requirements.txt) — the official Chia Network Rust CLVM
implementation with a Python binding (pypi.org/project/chia_rs), used here strictly to run
each spend's own on-chain puzzle_reveal against its own on-chain solution and read off the
conditions it produced (pure computation over public data already fetched — no keys, no
network calls of its own). This is the one non-stdlib dependency; everything else here is
python3's own standard library.

Usage:
    python3 verify_oracle.py <launcher_id> [--network testnet11|mainnet] [--api-url URL]
        [--stand-url http://localhost:8480] [--from-seq N] [--strict]
        [--json-out FILE] [--max-generations N] [-q]

Exit code:
    0  the run completed and found nothing wrong;
    1  the run completed and found a problem: a CONFLICT, a stand mismatch, a lineage error
       (a fork, a walk cut short by --max-generations, a spend it could not decode) — and,
       with --strict, a gap or an unmapped seq inside the scope;
    2  the run could not complete: coinset or the stand unreachable or answering garbage,
       chia_rs missing, bad arguments, a launcher coin that does not exist or was never spent.
A network or HTTP failure is always exit 2 with a message on stderr, never a traceback and
never the 1 that means "found a problem".
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
import time
import urllib.error
import urllib.parse
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

# The oracle's own announcement schema (chain/README.md / docs/research/chain_service.md §3.1
# and §16): (seq ts priceCents resultsHash sourceFlag sourceTs [asset]). `asset` is announced
# only when non-zero; every parsed record carries it (0 when absent), so a 6-element
# announcement and a 7-element one with an explicit 0 compare as the same record.
ANNOUNCEMENT_FIELDS = ("seq", "ts", "priceCents", "resultsHash", "sourceFlag", "sourceTs", "asset")
BASE_ELEMENTS = 6
MAX_ELEMENTS = 7
U32_MAX = 2**32 - 1

# `limit` the stand's /api/oracle/records accepts at most (docs/research/markets_architecture.md §7 item 6).
RECORDS_PAGE_LIMIT = 1000

# What a stand without /api/markets/registry.json (every stand before multi-market) means by
# an asset index: only BTC, index 0.
DEFAULT_REGISTRY = {0: {"index": 0, "id": "BTC", "kind": "PERP", "symbol": "BTC"}}

IDENTITY_PUZZLE = Program.to(1)

HEX64 = re.compile(r"^(0x)?[0-9a-fA-F]{64}$")


class NetworkError(RuntimeError):
    """coinset or the stand could not be reached, or answered something that is not what its
    API promises. Always exit code 2 in main(): the run could not complete, which is not the
    same thing as "the run completed and found a problem" (exit 1)."""


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
                    headers={"Content-Type": "application/json", "User-Agent": "verify-oracle/2"},
                )
                with urllib.request.urlopen(req, timeout=self.timeout) as r:
                    decoded = json.loads(r.read())
                if not isinstance(decoded, dict):
                    raise ValueError(f"expected a JSON object, got {type(decoded).__name__}")
                return decoded
            except Exception as e:  # noqa: BLE001 — deliberately broad: retry any transient failure
                last_err = e
                if attempt < self.retries - 1:
                    time.sleep(min(2 ** attempt, 10))
        raise NetworkError(f"coinset API call {path} failed after {self.retries} attempts: {last_err}")

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
    """Thin client for one stand's own public, unauthenticated HTTP API. The RPC methods used
    here (`round`, `rounds`) take no session cookie (see server/src/main/java/crowdvsmarket/
    server/api/RpcResource.java — neither is in requireSignedIn); the GET endpoints
    (results.json, oracle/records, markets/registry.json) are deliberately public (Р-41,
    docs/research/markets_architecture.md §7). No operator token, no cookie, no mutating call is
    ever made from this class.

    Every method returns None for "the stand does not have this" (HTTP 404) and raises
    NetworkError for everything else that is not a good answer — unreachable, timeout, any
    other HTTP status, a body that is not JSON."""

    def __init__(self, base_url: str, timeout: float = 15.0):
        self.base_url = base_url.rstrip("/")
        self.timeout = timeout

    def _fetch(self, url: str, data: Optional[bytes] = None, post: bool = False, missing: tuple = (404,)) -> Optional[bytes]:
        headers = {"User-Agent": "verify-oracle/2"}
        if post:
            headers["Content-Type"] = "application/json"
        req = urllib.request.Request(url, data=data, headers=headers, method="POST" if post else "GET")
        try:
            with urllib.request.urlopen(req, timeout=self.timeout) as r:
                return r.read()
        except urllib.error.HTTPError as e:  # before URLError: HTTPError is a subclass of it
            if e.code in missing:
                return None
            raise NetworkError(f"the stand answered HTTP {e.code} for {url}") from e
        except (urllib.error.URLError, OSError) as e:  # unreachable, refused, timed out
            raise NetworkError(f"cannot reach the stand at {url}: {e}") from e

    @staticmethod
    def _json(body: bytes, url: str) -> Any:
        try:
            return json.loads(body)
        except ValueError as e:
            raise NetworkError(f"the stand answered something that is not JSON for {url}: {e}") from e

    def _rpc(self, method: str, *args: Any, missing: tuple = (404,)) -> Any:
        url = f"{self.base_url}/api/rpc/{method}"
        body = self._fetch(url, json.dumps({"args": list(args)}).encode(), post=True, missing=missing)
        return None if body is None else self._json(body, url)

    def rounds_pair(self, interval: str) -> Optional[dict]:
        return self._rpc("rounds", interval)

    def round(self, round_id: str) -> Optional[dict]:
        # 400 too: a roundId in a format this stand's server does not parse is "a round it does
        # not have" for the purpose of walking history backward (GameService.badRequest).
        return self._rpc("round", round_id, missing=(404, 400))

    def results_json_bytes(self, round_id: str) -> Optional[bytes]:
        return self._fetch(f"{self.base_url}/api/rounds/{urllib.parse.quote(round_id, safe='')}/results.json")

    def oracle_records(self, from_seq: int, limit: int) -> Optional[dict]:
        url = f"{self.base_url}/api/oracle/records?fromSeq={from_seq}&limit={limit}"
        body = self._fetch(url)
        return None if body is None else self._json(body, url)

    def registry(self) -> Optional[list]:
        url = f"{self.base_url}/api/markets/registry.json"
        body = self._fetch(url)
        return None if body is None else self._json(body, url)


# --------------------------------------------------------------------------------------
# CLVM decoding — run each spend's own on-chain puzzle against its own on-chain solution
# (via chia_rs, see module docstring) and read off the conditions it produced.
# --------------------------------------------------------------------------------------


def atom_to_int(b: Optional[bytes]) -> int:
    if not b:
        return 0
    return int.from_bytes(b, "big", signed=True)


def to_pylist_with_tail(node: Any) -> tuple[list[Any], Optional[bytes]]:
    """Walk a chia_rs LazyNode cons-list into a python list of its elements, plus whatever
    atom ends it (b"" = nil for a proper list, anything else = an improper tail)."""
    items = []
    while node.pair is not None:
        first, rest = node.pair
        items.append(first)
        node = rest
    return items, node.atom


def to_pylist(node: Any) -> list[Any]:
    return to_pylist_with_tail(node)[0]


def _atom(node: Any, what: str) -> bytes:
    atom = node.atom
    if atom is None:
        raise ValueError(f"{what} is not an atom (a nested list)")
    return bytes(atom)


def parse_announcement_message(msg_bytes: bytes) -> dict:
    """A CREATE_PUZZLE_ANNOUNCEMENT's message is itself a serialized CLVM list — decode it
    by running the identity puzzle `1` (return environment unchanged) with the message as
    the environment, which just re-parses the bytes into a walkable tree without evaluating
    anything (no operators in the message itself to run — it is pure data).

    Accepts the 6-element list of every BTC record (asset 0) and the 7-element list of record
    v2 with a non-zero asset; the result always has `asset` (0 when the 7th element is absent),
    plus `elements` (6 or 7) for the reader. Raises ValueError for anything else.

    Deliberately a little stricter than chainsvc's own decoder (clvm-traits' `list` repr
    ignores extra elements and an improper tail): this is the auditor's tool, and "not a shape
    I understand" is exactly what it should say rather than read part of a message."""
    reparsed = Program.from_bytes(msg_bytes)
    _cost, result = IDENTITY_PUZZLE.run_rust(11_000_000_000, 0, reparsed)
    items, tail = to_pylist_with_tail(result)
    if tail != b"":
        raise ValueError("announcement is not a proper CLVM list")
    if len(items) not in (BASE_ELEMENTS, MAX_ELEMENTS):
        raise ValueError(
            f"expected a {BASE_ELEMENTS}- or {MAX_ELEMENTS}-element announcement list, got {len(items)}"
        )
    seq = atom_to_int(_atom(items[0], "seq"))
    if seq < 0:
        raise ValueError(f"seq {seq} is negative")
    results_hash = _atom(items[3], "resultsHash")
    if len(results_hash) != 32:
        raise ValueError(f"resultsHash is {len(results_hash)} bytes, expected 32")
    asset = atom_to_int(_atom(items[6], "asset")) if len(items) == MAX_ELEMENTS else 0
    if not 0 <= asset <= U32_MAX:
        raise ValueError(f"asset {asset} is outside 0..{U32_MAX}")
    return {
        "seq": seq,
        "ts": atom_to_int(_atom(items[1], "ts")),
        "priceCents": atom_to_int(_atom(items[2], "priceCents")),
        "resultsHash": "0x" + results_hash.hex(),
        "sourceFlag": atom_to_int(_atom(items[4], "sourceFlag")),
        "sourceTs": atom_to_int(_atom(items[5], "sourceTs")),
        "asset": asset,
        "elements": len(items),
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


def walk_lineage(client: Any, launcher_id: str, max_generations: int, verbose: bool) -> dict:
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
                            try:
                                msg_bytes = _atom(args[0], "announcement message")
                            except (IndexError, ValueError) as e:
                                anns.append({"raw_hex": "", "parse_error": str(e)})
                                continue
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
    decode_errors: list[dict] = []

    for entry in lineage:
        if "decode_error" in entry:
            decode_errors.append(
                {"generation": entry["generation"], "coin_id": entry["coin_id"], "error": entry["decode_error"]}
            )
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
                        "differing_fields": [
                            f
                            for f in ANNOUNCEMENT_FIELDS
                            if other["announcement"].get(f) != first["announcement"].get(f)
                        ],
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
        "decode_errors": decode_errors,
    }


# --------------------------------------------------------------------------------------
# Step 3 (optional, --stand-url): map rounds to seq via the stand's own public API and
# cross-check its live results.json bytes against what the chain anchored.
# --------------------------------------------------------------------------------------


def fetch_records_journal(stand: Any, from_seq: Optional[int]) -> Optional[dict]:
    """The stand's whole records journal from `from_seq` on (0 when None), paged. Returns
    {"firstSeq": int|None, "records": {seq: record}}, or None when the stand has no such
    endpoint (HTTP 404 on the first page — an old stand; see discover_rounds for what
    replaces it)."""
    cursor = from_seq if from_seq is not None else 0
    first_seq: Optional[int] = None
    records: dict[int, dict] = {}
    first_page = True
    while True:
        page = stand.oracle_records(cursor, RECORDS_PAGE_LIMIT)
        if page is None:
            if first_page:
                return None
            raise NetworkError("the stand's /api/oracle/records disappeared between two pages")
        if not isinstance(page, dict) or not isinstance(page.get("records"), list):
            raise NetworkError(
                "the stand's /api/oracle/records did not answer {firstSeq, records: [...]} — "
                f"got {str(page)[:120]!r}"
            )
        if first_page:
            first_seq = page.get("firstSeq")
            first_page = False
        rows = page["records"]
        for row in rows:
            if (
                not isinstance(row, dict)
                or not isinstance(row.get("seq"), int)
                or not isinstance(row.get("roundId"), str)
                or not row["roundId"]
                or not isinstance(row.get("asset", 0), (int, type(None)))
                or not isinstance(row.get("venue"), (str, type(None)))
            ):
                raise NetworkError(f"the stand's /api/oracle/records has a malformed row: {str(row)[:160]!r}")
            records.setdefault(row["seq"], row)
        if len(rows) < RECORDS_PAGE_LIMIT:
            break
        next_cursor = rows[-1]["seq"] + 1
        if next_cursor <= cursor:
            raise NetworkError("the stand's /api/oracle/records did not advance between pages (ignores fromSeq?)")
        cursor = next_cursor
    return {"firstSeq": first_seq, "records": records}


def discover_rounds(stand: Any, verbose: bool) -> dict[int, dict]:
    """The fallback for a stand without the records journal. Returns {seq: {roundId,
    resultsHash (from the RPC's own DTO), state, height}} for every settled round the stand
    still has, found by walking each series' roundId backward from its currently-running
    window (see module docstring, "How rounds map to seq"). BTC only, as that stand is."""
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
        while True:  # stop at the first roundId the stand doesn't know
            round_id = f"{code}-{start_ms}"
            dto = stand.round(round_id)
            if dto is None:
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


def load_registry(stand: Any) -> tuple[dict[int, dict], bool]:
    """The stand's public asset registry as {index: entry}, and whether the stand actually
    published one (False -> DEFAULT_REGISTRY, i.e. only asset 0 = BTC is known)."""
    raw = stand.registry()
    if raw is None:
        return dict(DEFAULT_REGISTRY), False
    if not isinstance(raw, list):
        raise NetworkError(f"the stand's /api/markets/registry.json is not a JSON list: {str(raw)[:120]!r}")
    registry: dict[int, dict] = {}
    for entry in raw:
        if not isinstance(entry, dict) or not isinstance(entry.get("index"), int):
            raise NetworkError(f"the stand's registry has a malformed entry: {str(entry)[:160]!r}")
        registry[entry["index"]] = entry
    return registry, True


def asset_label(registry: dict[int, dict], asset: int) -> str:
    entry = registry.get(asset)
    if entry is None or not entry.get("id"):
        return f"asset {asset}"
    return f"asset {asset} ({entry['id']})"


def backup_of(entry: Optional[dict]) -> Optional[dict]:
    """The registry entry's backup exchange, `{source, ref}`, or None for a market with none (or a stand that
    predates backups and has no such key)."""
    backup = entry.get("backup") if entry else None
    return backup if isinstance(backup, dict) and backup.get("source") else None


def venues_of(entry: Optional[dict]) -> set[str]:
    """The exchanges a round of this registry entry may have run on: the market's main exchange and its backup.
    Empty when the entry names none (a stand whose registry predates `source`), which allows any."""
    venues = set()
    if entry and entry.get("source"):
        venues.add(entry["source"])
    backup = backup_of(entry)
    if backup:
        venues.add(backup["source"])
    return venues


def cross_check(
    chain_seqs: dict[int, dict],
    stand: Any,
    stand_records: dict[int, dict],
    registry: dict[int, dict],
    scope_from: Optional[int],
    asset_known: bool,
) -> dict:
    """Checks every on-chain seq in scope against the stand's round for it.

    `stand_records`: {seq: {roundId, marketId (or None), asset (or None), state, venue (or None)}} —
    the records journal, or what the BTC-only fallback discovered (asset/marketId None: "BTC, by
    assumption"; venue None: that stand has no venues).
    `asset_known`: whether `stand_records` carries a real asset per seq (the journal) or not."""
    checked = []
    mismatches = []
    below_scope = []
    unmapped_in_scope = []
    for seq, info in sorted(chain_seqs.items()):
        if scope_from is not None and seq < scope_from:
            below_scope.append(seq)
            continue
        record = stand_records.get(seq)
        if record is None:
            unmapped_in_scope.append(seq)
            continue
        round_id = record["roundId"]
        announcement = info["announcement"]
        chain_asset = announcement["asset"]
        # Without a journal the stand is a pre-multi-market one: BTC, asset 0, by assumption.
        stand_asset = record["asset"] if asset_known else 0
        market_id = record.get("marketId") if asset_known else "BTC"
        label = asset_label(registry, chain_asset)

        problems: list[str] = []
        if chain_asset != stand_asset:
            problems.append(
                f"on-chain {label} but the stand assigns this round to asset {stand_asset}"
                + ("" if asset_known else " (this stand has no records journal: BTC only)")
            )
        elif asset_known:
            reg = registry.get(chain_asset)
            if reg is None:
                problems.append(f"asset {chain_asset} is not in the stand's own registry")
            elif market_id is not None and reg.get("id") not in (None, market_id):
                problems.append(
                    f"the stand's registry maps asset {chain_asset} to {reg.get('id')} but the round's market is {market_id}"
                )

        # Which exchange the round ran on (Р-76): what the journal says, and below what the hashed document says.
        journal_venue = record.get("venue") if asset_known else None
        doc_venue: Any = None
        venue_ok = True

        body = stand.results_json_bytes(round_id)
        results: Any = None
        computed_hash = None
        close_cents = None
        hash_ok = price_ok = False
        if body is None:
            problems.append("results.json not found on stand")
        else:
            # The bytes as served, hashed as they are: a root key "venue" is inside them and so inside the hash.
            computed_hash = "0x" + hashlib.sha256(body).hexdigest()
            try:
                results = json.loads(body)
            except json.JSONDecodeError as e:
                problems.append(f"results.json is not valid JSON: {e}")
            hash_ok = computed_hash == announcement["resultsHash"]
            if not hash_ok:
                problems.append("sha256(results.json) differs from the resultsHash anchored on chain")
            if isinstance(results, dict):
                close_cents = results.get("closePriceCents")
                price_ok = close_cents == announcement["priceCents"] or (
                    results.get("status") == "CANCELLED" and close_cents is None
                )
                if not price_ok:
                    problems.append("closePriceCents in results.json differs from the priceCents anchored on chain")
                # Both present and different = the hashed document and the journal disagree about
                # which market this round is. An absent key proves nothing (BTC documents never had one).
                doc_market = results.get("market")
                if doc_market is not None and market_id is not None and doc_market != market_id:
                    problems.append(f"results.json says market {doc_market!r} but the stand's journal says {market_id!r}")
                doc_venue = results.get("venue")
                if doc_venue is not None and not isinstance(doc_venue, str):
                    problems.append(f"results.json has a venue that is not a string: {doc_venue!r}")
                    venue_ok = False
                elif asset_known and doc_venue != journal_venue:
                    # Unlike the market, the venue is compared when only one side has it: a journal that names an
                    # exchange the hashed document does not (or the other way round) is two different stories.
                    if doc_venue is not None and journal_venue is not None:
                        problems.append(f"results.json says venue {doc_venue!r} but the stand's journal says {journal_venue!r}")
                    elif doc_venue is not None:
                        problems.append(f"results.json carries venue {doc_venue!r} but the stand's journal gives none")
                    else:
                        problems.append(f"the stand's journal says venue {journal_venue!r} but results.json carries none")
                    venue_ok = False
            elif results is not None:
                problems.append("results.json is not a JSON object")

        # The hashed document's word first (it is what the chain anchored); the journal's when there is no document.
        venue = doc_venue if isinstance(doc_venue, str) else journal_venue
        if venue is not None:
            allowed = venues_of(registry.get(chain_asset))
            if allowed and venue not in allowed:
                problems.append(
                    f"venue {venue!r} is neither the main exchange nor the backup the stand's registry lists for "
                    f"asset {chain_asset}: {', '.join(sorted(allowed))}"
                )
                venue_ok = False

        entry = {
            "seq": seq,
            "roundId": round_id,
            "marketId": market_id,
            "asset_onchain": chain_asset,
            "asset_stand": stand_asset,
            "asset_label": label,
            "asset_match": chain_asset == stand_asset,
            "resultsHash_onchain": announcement["resultsHash"],
            "resultsHash_computed": computed_hash,
            "hash_match": hash_ok,
            "priceCents_onchain": announcement["priceCents"],
            "closePriceCents_stand": close_cents,
            "price_match": price_ok,
            "venue": venue,
            "venue_match": venue_ok,
            "stand_state": record.get("state"),
            "problems": problems,
        }
        checked.append(entry)
        if problems:
            mismatches.append(entry)
    return {
        "checked_count": len(checked),
        "checked": checked,
        "mismatches": mismatches,
        # On chain, below the scope (the stand's epoch, or --from-seq): checked on chain only.
        "below_scope": below_scope,
        # On chain, inside the scope, but no round on the stand maps to it.
        "unmapped_in_scope": unmapped_in_scope,
    }


def missing_in_scope(analysis: dict, scope_from: Optional[int]) -> list[int]:
    """Every seq the lineage lacks between the start of the scope and the highest seq on chain."""
    seq_max = analysis["seq_max"]
    if seq_max is None:
        return []
    low = scope_from if scope_from is not None else analysis["seq_min"]
    return [s for s in range(low, seq_max + 1) if s not in analysis["per_seq"]]


def compact_ranges(seqs: list[int]) -> str:
    """[1,2,3,7,9,10] -> '1..3, 7, 9..10' (for human output)."""
    if not seqs:
        return "none"
    out = []
    start = prev = seqs[0]
    for s in seqs[1:]:
        if s == prev + 1:
            prev = s
            continue
        out.append(f"{start}..{prev}" if start != prev else f"{start}")
        start = prev = s
    out.append(f"{start}..{prev}" if start != prev else f"{start}")
    return ", ".join(out)


# --------------------------------------------------------------------------------------
# The run itself (everything but argument parsing and printing — tests drive this with
# canned coinset/stand clients) and its verdict.
# --------------------------------------------------------------------------------------


def verify(
    launcher_id: str,
    coinset: Any,
    stand: Optional[Any] = None,
    *,
    from_seq: Optional[int] = None,
    max_generations: int = 20000,
    verbose: bool = False,
    network: str = "testnet11",
    api_url: str = "",
    stand_url: Optional[str] = None,
) -> dict:
    eprint(f"Walking singleton lineage on {network} ({api_url or 'coinset'})…")
    lineage_walk = walk_lineage(coinset, launcher_id, max_generations, verbose)

    eprint("Analyzing seq numbers (first occurrence / duplicates / gaps)…")
    seq_analysis = analyze_seqs(lineage_walk["lineage"])

    result: dict[str, Any] = {
        "launcher_id": lineage_walk["launcher_id"],
        "network": network,
        "api_url": api_url,
        "lineage_walk": lineage_walk,
        "seq_analysis": seq_analysis,
        "stand_url": stand_url,
        "stand_check": None,
    }

    scope_from = from_seq
    scope_source = "--from-seq" if from_seq is not None else "the whole chain (no stand)"
    stand_check: Optional[dict] = None

    if stand is not None:
        eprint(f"Cross-checking against stand {stand_url} (public API only, no keys)…")
        registry, registry_published = load_registry(stand)
        journal = fetch_records_journal(stand, from_seq)
        chain_seqs = {seq: info["first"] for seq, info in seq_analysis["per_seq"].items()}
        if journal is not None:
            mode = "records-journal"
            stand_records = {
                seq: {
                    "roundId": row["roundId"],
                    "marketId": row.get("marketId"),
                    "asset": row.get("asset") or 0,  # absent or null = BTC, the only market there was
                    "state": row.get("state"),
                    "venue": row.get("venue"),  # absent or null = the round ran on no particular exchange
                }
                for seq, row in journal["records"].items()
            }
            stand_first_seq = journal["firstSeq"]
            if from_seq is None:
                if stand_first_seq is not None:
                    scope_from, scope_source = stand_first_seq, "the stand's firstSeq (its database starts there)"
                else:
                    # An empty journal: this stand's database owns no seq yet, so every seq on chain predates it.
                    scope_from = (seq_analysis["seq_max"] or 0) + 1
                    scope_source = "the stand's journal is empty (every on-chain seq predates it)"
        else:
            mode = "rounds-rpc-fallback"
            eprint("  the stand has no /api/oracle/records — falling back to the BTC round walk")
            discovered = discover_rounds(stand, verbose)
            stand_records = {
                seq: {"roundId": d["roundId"], "marketId": None, "asset": None, "state": d.get("state"), "venue": None}
                for seq, d in discovered.items()
            }
            stand_first_seq = None
            if from_seq is None:
                scope_from = min(stand_records) if stand_records else (seq_analysis["seq_max"] or 0) + 1
                scope_source = "the smallest seq the stand maps (no records journal)"
        stand_check = cross_check(
            chain_seqs, stand, stand_records, registry, scope_from, asset_known=journal is not None
        )
        stand_check["mode"] = mode
        stand_check["stand_first_seq"] = stand_first_seq
        stand_check["registry"] = {str(k): v for k, v in sorted(registry.items())}
        stand_check["registry_published"] = registry_published
        stand_check["stand_records"] = {str(k): v for k, v in sorted(stand_records.items())}
        # Informational: the stand queued these, the chain does not have them (yet).
        stand_check["stand_only"] = [
            {"seq": s, "roundId": r["roundId"], "state": r.get("state")}
            for s, r in sorted(stand_records.items())
            if s not in seq_analysis["per_seq"] and (scope_from is None or s >= scope_from)
        ]
        result["stand_check"] = stand_check

    missing = missing_in_scope(seq_analysis, scope_from)
    result["scope"] = {
        "from_seq": scope_from,
        "explicit": from_seq is not None,
        "source": scope_source,
        "missing_in_scope": missing,
        "unmapped_in_scope": stand_check["unmapped_in_scope"] if stand_check else [],
    }
    return result


def findings(result: dict, strict: bool) -> list[str]:
    """Why the run fails (empty = clean). Conflicts, stand mismatches and lineage errors fail
    every run; gaps and unmapped seqs inside the scope only with --strict."""
    problems = []
    analysis = result["seq_analysis"]
    if analysis["conflicts"]:
        problems.append(f"{len(analysis['conflicts'])} CONFLICT(s): a seq announced twice with different content")
    stand_check = result.get("stand_check")
    if stand_check is not None and stand_check["mismatches"]:
        problems.append(f"{len(stand_check['mismatches'])} stand mismatch(es)")
    if result["lineage_walk"]["errors"]:
        problems.append(f"{len(result['lineage_walk']['errors'])} lineage error(s) (a fork, or the walk was cut short)")
    if analysis["decode_errors"]:
        problems.append(f"{len(analysis['decode_errors'])} spend(s) could not be decoded, so their seqs are unknown")
    if strict:
        scope = result["scope"]
        if scope["missing_in_scope"]:
            problems.append(f"{len(scope['missing_in_scope'])} seq(s) missing from the chain inside the scope (--strict)")
        if scope["unmapped_in_scope"]:
            problems.append(f"{len(scope['unmapped_in_scope'])} on-chain seq(s) inside the scope the stand cannot map (--strict)")
    return problems


def exit_code(result: dict, strict: bool) -> int:
    return 1 if findings(result, strict) else 0


# --------------------------------------------------------------------------------------
# Output.
# --------------------------------------------------------------------------------------


def print_human_summary(result: dict, strict: bool = False) -> None:
    lineage = result["lineage_walk"]
    analysis = result["seq_analysis"]
    scope = result["scope"]
    print(f"Launcher id:        {result['launcher_id']}")
    print(f"Network:            {result['network']} ({result['api_url']})")
    print(f"Generations walked: {lineage['generations_walked']}")
    if lineage["errors"]:
        print("Lineage errors:")
        for e in lineage["errors"]:
            print(f"  - {e}")
    else:
        print("Lineage errors:     none (single unforked chain from launcher to tip)")
    if analysis["decode_errors"]:
        print(f"Spends that could not be decoded: {len(analysis['decode_errors'])}")
        for d in analysis["decode_errors"]:
            print(f"  generation={d['generation']} coin={d['coin_id']}: {d['error']}")
    print()
    seq_range = f" (range {analysis['seq_min']}..{analysis['seq_max']})" if analysis["seq_min"] is not None else ""
    print(f"Distinct seq numbers on chain: {analysis['distinct_seq_count']}{seq_range}")
    shapes: dict[int, int] = {}
    assets: dict[int, int] = {}
    for info in analysis["per_seq"].values():
        ann = info["first"]["announcement"]
        shapes[ann["elements"]] = shapes.get(ann["elements"], 0) + 1
        assets[ann["asset"]] = assets.get(ann["asset"], 0) + 1
    if shapes:
        registry = {int(k): v for k, v in (result.get("stand_check") or {}).get("registry", {}).items()}
        by_shape = ", ".join(f"{n} x {e}-element" for e, n in sorted(shapes.items()))
        by_asset = ", ".join(f"{asset_label(registry, a)}: {n}" for a, n in sorted(assets.items()))
        print(f"Announcement shapes: {by_shape}")
        print(f"Records by asset:    {by_asset}")
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
            print(f"    differing fields: {', '.join(c['differing_fields'])}")
    if analysis["gaps"]:
        print("\nGAP DETAILS:")
        for g in analysis["gaps"]:
            print(f"  after seq {g['after_seq']}, before seq {g['before_seq']}: missing {g['missing_seqs']}")
    if analysis["unparsed_announcements"]:
        print(f"\nUnparsed announcement-shaped conditions (not a 6- or 7-element record): "
              f"{len(analysis['unparsed_announcements'])}")
        for u in analysis["unparsed_announcements"]:
            print(f"  generation={u['generation']} coin={u['coin_id']} height={u['height']}: "
                  f"{u.get('parse_error')} (raw_hex={u.get('raw_hex', '')[:40]}…)")

    stand_check = result.get("stand_check")
    if stand_check is None:
        print("\n(no --stand-url given — chain-only verification above; no round<->seq cross-check run)")
        if scope["from_seq"] is not None:
            print(f"Scope: seq >= {scope['from_seq']} ({scope['source']}); missing in scope: "
                  f"{compact_ranges(scope['missing_in_scope'])}")
    else:
        print(f"\nStand cross-check against: {result['stand_url']}  [{stand_check['mode']}]")
        if stand_check["mode"] == "rounds-rpc-fallback":
            print("  (this stand has no /api/oracle/records: BTC-only discovery through the rounds RPC)")
        if not stand_check["registry_published"]:
            print("  (this stand publishes no /api/markets/registry.json: only asset 0 = BTC is labelled)")
        print(f"Scope: seq >= {scope['from_seq']} — from {scope['source']}")
        below = stand_check["below_scope"]
        if below:
            why = (f"below --from-seq {scope['from_seq']}" if scope["explicit"]
                   else f"published before this stand's database (seq < {scope['from_seq']})")
            print(f"{why[0].upper()}{why[1:]}: {len(below)} ({compact_ranges(below)}) — checked on chain only")
        print(f"Records the stand queued (in its journal / discovered):  {len(stand_check['stand_records'])}")
        print(f"Rounds checked (chain seq -> stand results.json):        {stand_check['checked_count']}")
        print(f"  hash matches:  {sum(1 for c in stand_check['checked'] if c['hash_match'])}")
        print(f"  price matches: {sum(1 for c in stand_check['checked'] if c['price_match'])}")
        print(f"  asset matches: {sum(1 for c in stand_check['checked'] if c['asset_match'])}")
        by_venue: dict[str, int] = {}
        for c in stand_check["checked"]:
            if c.get("venue"):
                by_venue[c["venue"]] = by_venue.get(c["venue"], 0) + 1
        if by_venue:
            # Rounds with no venue are every round of a market without a backup; only the others are counted here.
            print(f"  venue matches: {sum(1 for c in stand_check['checked'] if c.get('venue_match', True))}"
                  f"  (rounds by exchange: {', '.join(f'{venue} {n}' for venue, n in sorted(by_venue.items()))})")
        stand_registry = {int(k): e for k, e in stand_check["registry"].items()}
        with_backup = [(a, e) for a, e in sorted(stand_registry.items()) if backup_of(e)]
        if with_backup:
            print("Markets with a backup exchange (the stand's registry): " + ", ".join(
                f"{asset_label(stand_registry, a)} {e.get('source')} -> {backup_of(e)['source']}"
                for a, e in with_backup))
        print(f"Mismatches:                                                {len(stand_check['mismatches'])}")
        if stand_check["mismatches"]:
            print("\nMISMATCH DETAILS:")
            for m in stand_check["mismatches"]:
                print(f"  seq={m['seq']} round={m['roundId']} [{m['asset_label']}]"
                      + (f" venue={m['venue']}" if m.get("venue") else ""))
                for p in m["problems"]:
                    print(f"    - {p}")
        if stand_check["unmapped_in_scope"]:
            print(f"\nOn-chain seqs inside the scope that no stand round maps to"
                  f"{' (FAILS under --strict)' if strict else ''}: "
                  f"{compact_ranges(stand_check['unmapped_in_scope'])}")
        if stand_check["stand_only"]:
            print(f"\nSeqs the stand queued that are not on chain (yet): "
                  f"{compact_ranges([s['seq'] for s in stand_check['stand_only']])}")
        if scope["missing_in_scope"]:
            print(f"\nSeqs missing from the chain inside the scope{' (FAILS under --strict)' if strict else ''}: "
                  f"{compact_ranges(scope['missing_in_scope'])}")

    problems = findings(result, strict)
    print()
    if problems:
        print("VERDICT: PROBLEMS FOUND (exit 1)")
        for p in problems:
            print(f"  - {p}")
    else:
        print("VERDICT: OK (exit 0)" + ("" if strict else " — gaps and unmapped seqs are listed above but only fail with --strict"))


def build_arg_parser() -> argparse.ArgumentParser:
    ap = argparse.ArgumentParser(
        description="Standalone public verifier for Herd's price oracle singleton lineage.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__,
    )
    ap.add_argument("launcher_id", help="the oracle singleton's launcher coin id (with or without 0x)")
    ap.add_argument("--network", choices=sorted(NETWORKS), default="testnet11")
    ap.add_argument("--api-url", default=None, help="override the coinset API base URL for --network")
    ap.add_argument("--stand-url", default=None, help="e.g. http://localhost:8480 — enables the round<->seq cross-check")
    ap.add_argument("--from-seq", type=int, default=None, metavar="N",
                    help="hold the stand to account only for seq >= N (default: the stand's own firstSeq)")
    ap.add_argument("--strict", action="store_true",
                    help="also fail (exit 1) on a seq missing from the chain or an on-chain seq no stand round maps to, inside the scope")
    ap.add_argument("--json-out", default=None, help="also write the full machine-readable result as JSON to this file")
    ap.add_argument("--max-generations", type=int, default=20000, help="safety cap on how many spends to walk (default 20000)")
    ap.add_argument("-q", "--quiet", action="store_true", help="suppress progress lines on stderr")
    return ap


def main(argv: Optional[list[str]] = None) -> int:
    ap = build_arg_parser()
    args = ap.parse_args(argv)
    if not HEX64.match(args.launcher_id):
        ap.error("launcher_id must be 64 hex characters (a coin id), with or without 0x")
    if args.from_seq is not None and args.from_seq < 0:
        ap.error("--from-seq must be >= 0")
    if args.max_generations < 1:
        ap.error("--max-generations must be >= 1")

    api_url = args.api_url or NETWORKS[args.network]
    try:
        result = verify(
            args.launcher_id,
            CoinsetClient(api_url),
            StandClient(args.stand_url) if args.stand_url else None,
            from_seq=args.from_seq,
            max_generations=args.max_generations,
            verbose=not args.quiet,
            network=args.network,
            api_url=api_url,
            stand_url=args.stand_url,
        )
    except NetworkError as e:
        eprint(f"error: {e}")
        return 2

    print_human_summary(result, args.strict)

    if args.json_out:
        with open(args.json_out, "w") as f:
            json.dump(result, f, indent=2, default=str)
        eprint(f"\nFull JSON result written to {args.json_out}")

    return exit_code(result, args.strict)


if __name__ == "__main__":
    sys.exit(main())
