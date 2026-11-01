# verify-oracle

A standalone public verifier for Herd's price oracle singleton (D-11/D-40/D-41/D-47 in
[`the decision log`](../../the decision log)). Anyone can run it — it
needs none of our keys, none of our servers except (optionally) one stand's own public
API, and it never sends a spend, a signature, or any other mutating request. Full design
rationale and real runs' output: [`docs/research/oracle_verification.md`](../../docs/research/oracle_verification.md)
(Russian, this project's docs language). This file is the short, English "just run it"
reference for an outside auditor.

## What it checks

1. Walks the oracle singleton's lineage from its launcher coin, forward, using only the
   public coinset.org light-wallet API (no auth, read-only) for the chosen network
   (default `testnet11`).
2. Decodes every `CREATE_PUZZLE_ANNOUNCEMENT` condition in every spend. A record is a CLVM
   list of 6 elements `(seq, ts, priceCents, resultsHash, sourceFlag, sourceTs)` or, since
   record format v2, of 7: the same six plus `asset`, the index of the record's market in
   the public registry. A BTC record (asset 0) is still the 6-element list, byte for byte
   what it always was — `asset` is announced only when it is non-zero, and a missing one
   reads as 0. Anything else (the 3-element week-1 message, other lengths, an `asset`
   outside `0..2^32-1`, a `resultsHash` that is not 32 bytes) is listed as *unparsed*,
   never guessed at.
3. Reports, per `seq`: its first on-chain occurrence (height + coin), any later duplicate
   (identical content, `asset` included = benign re-publish; different content, even only
   in `asset` = **CONFLICT**), and any gap in the `seq` sequence (a round whose anchor
   never made it on chain).
4. With `--stand-url`, additionally holds a stand to account for the `seq` range in scope:
   it maps each `seq` to a round with the stand's records journal
   (`GET /api/oracle/records?fromSeq=&limit=`, every market) — or, for an older stand that
   answers 404 there, with the BTC-only round walk through its public RPC (see the module
   docstring in `verify_oracle.py`, "How rounds map to seq") — fetches that round's public
   `GET /api/rounds/{id}/results.json`, and checks `sha256(bytes) == resultsHash`,
   `closePriceCents == priceCents`, and that the asset on chain is the asset the stand gives
   the round (and that the stand's `GET /api/markets/registry.json` maps it to the round's
   market).
5. For a crypto market that has a backup exchange, also the **venue** of each round (D-76): a
   round opens on the market's main exchange or on its backup and runs on that one exchange to
   the end. The round names it in a root key `"venue"` of its `results.json` — inside the
   hash the chain anchored, and `priceCents` is the close on that exchange — and the journal
   repeats it per record. The two must agree (also when only one of them names an exchange),
   and the venue must be the market's main exchange or its backup in the stand's registry
   (`source` and `backup.source`). A round with no venue — every round of a market without a
   backup, every round from before venues, a cancelled round no exchange could price —
   has no `"venue"` key and its document is byte for byte what it always was. The summary
   counts the rounds by exchange and lists the registry's markets that have a backup.

## Requirements

- Python 3.9+
- [`chia_rs`](https://pypi.org/project/chia_rs/) — the official Chia Network CLVM
  implementation with a Python binding; the one non-stdlib dependency, used only to run
  each spend's own on-chain puzzle against its own on-chain solution (pure computation
  over already-public data — no keys, no network calls of its own).

```bash
pip install -r requirements.txt
```

## Run it

Against testnet11 only (no stand — chain-only lineage/seq check):

```bash
python3 verify_oracle.py c5cb79a5ea20fb66a29c63867806dec4d1120fa002b386c6033fec09b9cdeb20
```

Against testnet11 **and** cross-checked with a running local stand
([`CONTRIBUTING.md`](../../CONTRIBUTING.md)):

```bash
python3 verify_oracle.py c5cb79a5ea20fb66a29c63867806dec4d1120fa002b386c6033fec09b9cdeb20 \
    --stand-url http://localhost:8480 --json-out result.json
```

Strict (a gap or an unmapped `seq` inside the scope also fails the run), and answering for the
stand only from `seq` 1146 on:

```bash
python3 verify_oracle.py c5cb79a5ea20fb66a29c63867806dec4d1120fa002b386c6033fec09b9cdeb20 \
    --stand-url http://localhost:8480 --strict --from-seq 1146
```

`c5cb79a5ea20fb66a29c63867806dec4d1120fa002b386c6033fec09b9cdeb20` is this project's own
oracle singleton's launcher id (week 1, see `chain/README.md`) — pass any other launcher
id to verify a different singleton on the same or another network (`--network mainnet`).

Options: `--network testnet11|mainnet`, `--api-url URL` (another coinset-compatible API),
`--stand-url URL`, `--from-seq N`, `--strict`, `--json-out FILE`, `--max-generations N`
(default 20000), `-q`.

## Scope: `--from-seq`, `--strict`

The *scope* is the range of `seq` numbers a run holds the stand to account for: `--from-seq N`
if given, otherwise the stand's own `firstSeq` (the start of its database; for an old stand
without the journal, the smallest `seq` it maps), and the whole chain when there is no stand.

- Below the scope — a `seq` published before this stand's database existed (an earlier
  database against the same singleton) — is reported as such and checked **on chain only**
  (lineage, duplicates, conflicts, gaps). There is no stand round to compare it with; that is
  not a mismatch.
- Inside the scope, an on-chain `seq` that no stand round maps to is *unmapped* and a `seq` the
  lineage skips is a *gap*. Both are always printed; they fail the run only with `--strict`.
  Gaps below the scope belong to an earlier era and never fail a strict run.
- Records the stand queued that are not on chain yet are listed for information only.

## Reading the output

- Prints a human summary to stdout; `--json-out FILE` additionally writes the full
  machine-readable result (every generation, every announcement, every seq's full
  history, the stand cross-check detail) as JSON. The last line is the verdict:
  `VERDICT: OK (exit 0)` or `VERDICT: PROBLEMS FOUND (exit 1)` followed by the reasons.
- **Healthy** run: `CONFLICTS: 0`, `Gaps in the seq sequence: 0`, and (with
  `--stand-url`) `Mismatches: 0`. `Benign duplicates` being non-zero is normal — the
  publisher's durable queue re-announces an already-confirmed record after a
  reconciliation race sometimes; that is safe exactly because the re-announcement always
  carries identical content, which is what this tool checks, not just that the
  count went up.
- Exit code:
  - `0` — the run completed and found nothing wrong;
  - `1` — the run completed and found a problem: a CONFLICT; a mismatch with the stand; a
    lineage error (a fork, a walk cut short by `--max-generations`, a spend that could not be
    decoded — an incomplete verification is not "ok"); and, with `--strict`, a gap or an
    unmapped `seq` inside the scope;
  - `2` — the run could not complete: coinset or the stand unreachable or answering something
    that is not their API, bad arguments, `chia_rs` missing, a launcher coin that does not exist
    on that network or was never spent. A network or HTTP failure is always `2` with a message
    on stderr — never a traceback, never the `1` that means "found a problem".
- One early announcement on this project's own singleton (the very first spend, `seq`
  effectively `1`, week 1 before D-41) used a since-superseded 3-field schema (no
  `resultsHash` yet) — the tool reports it under `Unparsed announcement-shaped
  conditions` rather than misreading it as a seq collision. That is expected history,
  not a finding.

## Tests

```bash
python3 -m unittest discover -s tools/verify-oracle -p 'test_*.py'   # from the repo root
```

`test_verify_oracle.py` runs offline: the two network classes are replaced by canned fakes
serving exactly the JSON the real services serve, and the CLVM spends are real, so every case
goes through the same lineage walk, parser, `seq` analysis, cross-check, verdict and exit code
as a real run. The announcement bytes are the golden ones the Rust tests
(`chain/c2-svc/tests/oracle_golden/mod.rs`) pin, tying both decoders to one byte string; the
`results.json` documents with a venue are the ones the server's `ResultsHasherTest` pins, so the
Java hasher and this verifier share those bytes too.

## Scope, stated plainly

Verified: the singleton lineage is a single unforked chain; every announcement decodes
to the declared schema; `seq` accounting (duplicates/conflicts/gaps); with a stand,
that its live `results.json` bytes still hash to what was anchored on chain, its
close price matches what the chain says was published, the market (asset) it
assigns to a round is the one the chain announced, and the exchange a crypto round ran on
is the same in the hashed document and in the journal and is one the registry lists for it.

**Not** verified by this tool: that a published price was itself an honest price
(trust in the operator key + the price source — Hyperliquid, Coinbase, Kraken or Polymarket —
is a deliberate, documented tradeoff — D-11, `the project plan` §2; the venue only tells you which
exchange to compare the price with); that a round's payouts were computed correctly by
the game engine (covered by `engine/`'s own unit tests, a different concern); AGG_SIG_ME
signatures on each spend (the public chain already rejected any spend that failed that check
before confirming it — this tool reads already-confirmed chain data).
