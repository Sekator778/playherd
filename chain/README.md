# chain/ — Chia Rust SDK: simulator spike, testnet11 CLI, chain service

Week 1 (`PLAN.md` §5) Chia Rust SDK (`chia-wallet-sdk` family) work: an
offline compile/design spike against `chia-sdk-test::Simulator`
(`sim-spike/`), and a real testnet11 CLI built on the same proven patterns
(`c2-cli/`, binary `c2`). Week 2 adds `c2-svc/`, the HTTP chain service
("chainsvc", FIXED INTERFACE (3): Sage signature verification + the
multi-record oracle publisher) other components (the game server) talk to
-- it depends on `c2-cli` as a plain library, reusing its key
derivation/signing/oracle-state code unchanged rather than duplicating it,
see `docs/research/chain_service.md` §1. Write-ups:
[`docs/research/week1_chain_spike.md`](../docs/research/week1_chain_spike.md) (day 1 spike),
[`docs/research/week1_chain_access.md`](../docs/research/week1_chain_access.md) (network access routes, Р-11 oracle design),
[`docs/research/week1_testnet_cli.md`](../docs/research/week1_testnet_cli.md) (`c2-cli` command reference, key derivation, offer format),
[`docs/research/week1_testnet_results.md`](../docs/research/week1_testnet_results.md) (every real testnet11 action this CLI performed: tx/coin ids, block heights, costs, fees, kill-criteria status),
[`docs/research/chain_service.md`](../docs/research/chain_service.md) (`c2-svc`: FIXED INTERFACE (3), the multi-record oracle-publish batch spend, the durable publisher queue, real `get_fee_estimate`/`get_coin_record_by_name` responses verified against testnet11; §13 adds real on-chain PLAY withdraw/deposit, Р-6/decision Р-53).

## Layout

- `Cargo.toml` — cargo workspace root (`resolver = "2"`, `edition = "2024"`),
  members `sim-spike`, `c2-cli` and `c2-svc`.
- `sim-spike/` — offline compile/design spike, unchanged since day 1.
  - `src/lib.rs` — `measure_bundle_cost`, a helper that measures a spend
    bundle's real consensus CLVM cost (byte + execution + condition cost)
    from public `chia-consensus`/`chia-sdk-types` building blocks, because
    `chia-sdk-test::Simulator` itself does not expose spend cost.
  - `tests/standard_coin.rs` — fund and spend a plain p2 (standard) coin.
  - `tests/cat_play.rs` — issue the PLAY CAT (genesis-by-coin-id,
    single-issuance TAIL) and transfer part of it. Prints the asset id.
  - `tests/oracle_singleton.rs` — minimal "managed resolver" oracle: a
    singleton controlled by one manager key, spent 3 times, each spend
    recreating the singleton and emitting a puzzle announcement encoding
    `(price, timestamp, sequence)` as CLVM; a second, independent coin in
    the same bundle asserts that announcement. Prints the CLVM cost of the
    singleton spend alone and of the whole bundle, per round.
  - `tests/offer_play_xch.rs` — makes and takes a PLAY CAT ↔ XCH offer
    entirely inside the simulator, using `chia-sdk-driver`'s action-system
    (`Spends`/`Action`/`Offer`).
- `c2-cli/` — the `c2` binary: a real testnet11 CLI for the PLAY CAT / oracle
  singleton / offers primitives, built on the exact same builder functions
  its own simulator tests exercise (`src/builders/{send,issue,offer,oracle,fee}.rs`).
  Commands (all spending commands are dry-run unless `--push`; see
  `docs/research/week1_testnet_cli.md` for the full reference and
  `week1_testnet_results.md` for real testnet11 runs of every one of them):
  - `keygen` / `address` / `balance` — key management and read-only balance
    (coinset.org, no account needed).
  - `send` — plain XCH transfer to any `txch1...` address.
  - `issue-play` — mints the PLAY CAT (single-issuance genesis-by-coin-id
    TAIL; 1 PLAY = 1000 CAT mojos).
  - `play-balance` / `send-play` — general CAT-coin balance/transfer for any
    `--asset-id` (not just a coin this same process minted itself): scans
    `ADDRESS_GAP` indices' CAT puzzle hashes and, for `send-play`,
    reconstructs the found coin's lineage proof from its parent's on-chain
    spend (`src/cat_chain.rs::find_cat_coin`/`cat_from_coin_id`,
    `chia-sdk-driver`'s `Cat::parse_children`) -- what makes a real deposit
    from another wallet (e.g. `counterparty.json`) possible, see
    `docs/research/chain_service.md` §13.5.
  - `offer-make` / `offer-take` — PLAY ↔ TXCH offers (`offer1...`, byte-exact
    with the reference wallet's compression format); `offer-make` can offer
    less than a wallet's full cached PLAY coin, with the remainder returned
    as CAT change in the same spend.
  - `oracle-launch` / `oracle-publish` — the minimal oracle singleton of
    decision Р-11 (manager-key signature + self-recreation +
    `CREATE_PUZZLE_ANNOUNCEMENT(price, timestamp, sequence)`, no Action
    Layer). `oracle-publish --from-hyperliquid` reads BTC `markPx` and the
    current time live from Hyperliquid's public `/info` endpoint instead of
    taking `--price`/`--ts` (`src/hyperliquid.rs`; index looked up by name in
    `meta.universe`, never hardcoded).
  - A nonzero `--fee` on `oracle-publish` is paid by a *second* ordinary
    coin in the same bundle (`src/builders/fee.rs`), because the recreated
    singleton always carries exactly 1 mojo and can't reserve a fee itself
    without breaking its own value conservation.
  - Every dry-run/pushed spend prints the exact `push_tx` JSON body plus its
    CLVM cost breakdown (`src/cost.rs`, `src/spend_json.rs`). Local caches
    a command needs from an earlier one (`~/.c2/testnet11/<name>.play.json`,
    `<name>.oracle.json`) are only ever updated once a spend has actually
    been broadcast (`--push`, and only on a successful `push_tx`) — never on
    a dry run, which creates nothing on chain.
  - Keys: `~/.c2/testnet11/<name>.json` (`0600`, dir `0700`), mnemonic never
    printed. `keygen` refuses to overwrite an existing name.
- `c2-svc/` — the `c2-svc` binary: FIXED INTERFACE (3) HTTP service
  ("chainsvc", port 8090), see `docs/research/chain_service.md` for the full
  write-up. Depends on `c2-cli` as a plain path dependency (reuses its
  `keys`/`sign`/`state`/`keystore`/`cost`/`chain`/`builders::{oracle,fee}`
  code and JSON shapes unchanged, see that doc's §1) rather than duplicating
  any of it.
  - `POST /v1/auth/verify` (`src/auth.rs`) — Sage
    `chia_signMessageByAddress` signature verification (decision Р-4/Р-45),
    the same AugSchemeMPL/sha256tree scheme `docs/research/
    week1_wallet_auth.md` documents. Test vectors:
    `tests/vectors/auth_vectors.json` (generated by
    `tests/gen_auth_vectors.rs` from fixed seeds), replayed by
    `tests/auth_verify.rs`.
  - `POST /v1/oracle/records` / `GET /v1/oracle/status`
    (`src/oracle/`) — extends `c2-cli`'s single-record oracle-publish
    singleton spend to up to 40 records per spend (`MAX_RECORDS_PER_SPEND`;
    `oracle/spend.rs::build_oracle_publish_batch`, simulator-tested in
    `tests/oracle_multi_announce.rs`, a full batch measured at ~61M cost),
    each as a 6-element announcement `(seq ts priceCents resultsHash
    sourceFlag sourceTs)` or, for a non-BTC market, a 7-element one with a
    trailing `asset` index (record format v2, `docs/research/chain_service.md`
    §16), backed by a durable, crash-safe
    publish queue (`oracle/queue.rs`, `oracle/publisher.rs`) that never
    double-spends the singleton, retries a stuck broadcast with backoff, and
    is unit-tested end to end against a fake, in-memory chain client
    (`tests/oracle_queue.rs`, no network).
  - `POST /v1/play/withdrawals` / `GET /v1/play/withdrawals/{requestId}` /
    `POST /v1/play/deposit-address` / `GET /v1/play/deposits`
    (`src/play/`, Р-6, decision Р-53 2026-09-26) — real on-chain PLAY
    withdraw/deposit. Withdrawals batch up to 20 payouts per treasury CAT
    spend with change back to the treasury, same durable-queue/never-double-
    spend/fee-rebuild discipline as the oracle publisher above but as an
    independent structure keyed by `requestId`
    (`play/withdrawal_queue.rs`/`withdrawal_publisher.rs`); deposit addresses
    are derived at a dedicated index range and scanned for by their CAT
    puzzle hash (`play/deposit.rs`). Unit-tested against a fake chain client
    (`tests/play_withdrawals.rs`, `tests/play_deposits.rs`, no network). See
    `docs/research/chain_service.md` §13.
  - Fee-coin reserve (`src/fee_reserve.rs`, `docs/research/chain_service.md`
    §16): keeps the operator wallet at `C2_FEE_COINS_MIN` (4) free fee coins of
    `C2_FEE_COIN_MOJOS` (200 000 000) each by splitting its largest coin when
    the oracle publisher is not mid-spend, so the oracle and the withdrawal
    publisher never wait for each other's coin; `GET /v1/oracle/status` shows
    `feeCoinsFree`/`feeCoinsFreeMojos`, `maxKnownSeq` and `recordFormat`.
  - Fees and the mempool (`docs/research/chain_service.md` §17): when
    `get_fee_estimate` fails or answers 0 a spend is priced at the last
    successful estimate's rate, else at `C2_FALLBACK_FEE_RATE_MILLIMOJOS_PER_COST`
    (default 100 = 0.1 mojo per cost), capped at `C2_MAX_FEE_MOJOS`, never 0
    (`src/fee_estimate.rs`); an in-flight oracle bundle or PLAY withdrawal
    batch that sits in the node's mempool for `C2_MEMPOOL_REPLACE_AFTER_SECS`
    (default 180, 0 = off) is replaced by one that satisfies the node's
    replacement rule (`src/mempool_rule.rs`: a superset of its coins, +10 000 000
    mojos), and which of the two the chain took is read off the chain, never
    assumed; the chain walk settles our own confirmation as an INFO line, not a
    warning; `POST /v1/play/withdrawals` answers HTTP 409 for a `requestId` held
    with another recipient or amount. Tested against the real consensus
    simulator with a model mempool (`tests/sim_chain/`, `tests/oracle_replace.rs`,
    `tests/withdrawal_replace.rs`).
  - `Dockerfile` — multi-stage release build, build context `chain/` (needs
    the `c2-cli` path dependency + the shared `Cargo.lock` in scope).
  - `--dry-run` / `C2_PUSH=0` — builds and signs spends, never broadcasts or
    advances persisted state, same convention as `c2-cli`'s own dry-run.

## Running

```sh
cd chain
cargo test --workspace              # warm run
cargo clean && cargo test --workspace  # cold build + test (no network; needs crates.io cache)
cargo build --workspace             # binaries at target/debug/{c2,c2-svc}
```

All dependency versions in each crate's `Cargo.toml` are pinned exactly
(`=x.y.z`); `Cargo.lock` is committed. Toolchain: `rustc 1.98.1` (workspace
edition `2024`). `c2-cli` additionally talks to the network at runtime
(`chia-sdk-coinset` against `https://testnet11.api.coinset.org`, and
`reqwest` against `https://api.hyperliquid.xyz`, both plain HTTPS, no
certificates) — `cargo build`/`test` themselves stay offline beyond the
usual `crates.io` dependency fetch. `c2-svc` talks to the same coinset.org
endpoint at runtime (plus `get_fee_estimate`, not wrapped by
`chia-sdk-coinset` `0.36.0` -- see `docs/research/chain_service.md` §3.5);
its own `cargo build`/`test` stay just as offline (the fake-chain-client
tests, `c2-svc/tests/oracle_queue.rs`, never touch the network).

## Known gaps (see the docs above for detail)

- `chia-sdk-test::Simulator` does not expose spend/bundle cost; measuring it
  needs `sim-spike`'s own `measure_bundle_cost` helper (ported into
  `c2-cli/src/cost.rs` for the CLI binary, which doesn't depend on the
  simulator/test crate).
- RewardDistributor / Action Layer (CHIP-50/51) drivers, including
  `RewardDistributorType::Managed`, exist in `chia-sdk-driver` (feature
  `action-layer`) — located, not used for the oracle (Р-11 chose a minimal
  custom singleton instead, see `week1_chain_access.md` §8). CHIP-50 Action
  Layer is still the planned path for the week 2-3 `create_market/resolve/redeem`
  application.
- `c2-cli`'s local per-wallet caches (`<name>.play.json`, `<name>.oracle.json`)
  advance as soon as a spend is accepted into coinset's mempool
  (`push_tx` succeeds), not once it's confirmed in a block — on a
  long-unconfirmed or evicted push (testnet11's mempool was observed
  consistently 60-100% full during week 1, day 3-4), the cache can point at
  a coin that never ends up existing on chain, and needs manual repair
  before retrying. See `week1_testnet_results.md` for a real incident this
  caused and how it was fixed/worked around.
- `offer-take`/`oracle-publish` fee support (`--fee`) and `offer-make`'s
  partial-amount support (`--amount`) were added this pass; `send` is new
  this pass too. Not yet wired up: batching multiple `oracle-publish`
  rounds or fee-bumping a still-pending push from the CLI itself (done by
  hand this pass, see the results doc).
