# verify-oracle

A standalone public verifier for Herd's price oracle singleton (Р-11/Р-40/Р-41/Р-47 in
[`docs/product/decisions.md`](../../docs/product/decisions.md)). Anyone can run it — it
needs none of our keys, none of our servers except (optionally) one stand's own public
API, and it never sends a spend, a signature, or any other mutating request. Full design
rationale and a real run's output: [`docs/research/oracle_verification.md`](../../docs/research/oracle_verification.md)
(Russian, this project's docs language). This file is the short, English "just run it"
reference for an outside auditor.

## What it checks

1. Walks the oracle singleton's lineage from its launcher coin, forward, using only the
   public coinset.org light-wallet API (no auth, read-only) for the chosen network
   (default `testnet11`).
2. Decodes every `CREATE_PUZZLE_ANNOUNCEMENT` condition in every spend — each one is a
   6-field CLVM list `(seq, ts, priceCents, resultsHash, sourceFlag, sourceTs)`.
3. Reports, per `seq`: its first on-chain occurrence (height + coin), any later duplicate
   (identical content = benign re-publish; different content = **CONFLICT**), and any gap
   in the `seq` sequence (a round whose anchor never made it on chain).
4. With `--stand-url`, additionally maps each `seq` to the round it belongs to (via that
   stand's own public, unauthenticated API — see the module docstring in
   `verify_oracle.py`, "How rounds map to seq"), fetches that round's public
   `GET /api/rounds/{id}/results.json`, and checks `sha256(bytes) == resultsHash` and
   `closePriceCents == priceCents` against what the chain anchored.

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
([`docs/product/local_stand.md`](../../docs/product/local_stand.md)):

```bash
python3 verify_oracle.py c5cb79a5ea20fb66a29c63867806dec4d1120fa002b386c6033fec09b9cdeb20 \
    --stand-url http://localhost:8480 --json-out result.json
```

`c5cb79a5ea20fb66a29c63867806dec4d1120fa002b386c6033fec09b9cdeb20` is this project's own
oracle singleton's launcher id (week 1, see `chain/README.md`) — pass any other launcher
id to verify a different singleton on the same or another network (`--network mainnet`).

## Reading the output

- Prints a human summary to stdout; `--json-out FILE` additionally writes the full
  machine-readable result (every generation, every announcement, every seq's full
  history, the stand cross-check detail) as JSON.
- **Healthy** run: `CONFLICTS: 0`, `Gaps in the seq sequence: 0`, and (with
  `--stand-url`) `Mismatches: 0`. `Benign duplicates` being non-zero is normal — the
  publisher's durable queue re-announces an already-confirmed record after a
  reconciliation race sometimes; that is safe exactly because the re-announcement always
  carries byte-identical content, which is what this tool checks, not just that the
  count went up.
- Exit code: `0` = clean (no conflicts, no mismatches), `1` = ran fine but found at least
  one CONFLICT or mismatch (read the `CONFLICT DETAILS`/`MISMATCH DETAILS` section),
  `2` = usage/environment error (bad launcher id for that network, `chia_rs` missing,
  coinset unreachable, etc).
- One early announcement on this project's own singleton (the very first spend, `seq`
  effectively `1`, week 1 before Р-41) used a since-superseded 3-field schema (no
  `resultsHash` yet) — the tool reports it under `Unparsed announcement-shaped
  conditions` rather than misreading it as a seq collision. That is expected history,
  not a finding.

## Scope, stated plainly

Verified: the singleton lineage is a single unforked chain; every announcement decodes
to the declared schema; `seq` accounting (duplicates/conflicts/gaps); with a stand,
that its live `results.json` bytes still hash to what was anchored on chain, and its
close price matches what the chain says was published.

**Not** verified by this tool: that a published price was itself an honest BTC price
(trust in the operator key + Hyperliquid as source is a deliberate, documented tradeoff
— Р-11, `PLAN.md` §2); that a round's payouts were computed correctly by the game engine
(covered by `engine/`'s own unit tests, a different concern); AGG_SIG_ME signatures on
each spend (the public chain already rejected any spend that failed that check before
confirming it — this tool reads already-confirmed chain data).
