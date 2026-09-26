-- Р-53: real on-chain PLAY withdraw/deposit against chainsvc's FIXED INTERFACE
-- (POST /v1/play/withdrawals, GET /v1/play/withdrawals/{requestId}, POST /v1/play/deposit-address,
-- GET /v1/play/deposits) — see docs/research/server_design.md.

-- Cached chainsvc-derived deposit address per player (GameService#myDepositAddress). The address
-- is deterministic given player_index, so this is fetched from chainsvc at most once per player,
-- ever, and reused after that — never re-derived, never rotated.
CREATE TABLE player_deposit_addresses (
    player_id         TEXT PRIMARY KEY REFERENCES players(player_id),
    address           TEXT NOT NULL,
    inner_puzzle_hash TEXT NOT NULL,
    cat_puzzle_hash   TEXT NOT NULL,
    created_at_ms     BIGINT NOT NULL
);

-- One row per on-chain PLAY CAT coin ever credited to a player's in-game balance — the deposit
-- poller's exactly-once guard (DepositPollerService): PRIMARY KEY (coin_id) rejects a coin already
-- credited, whether chainsvc's own GET /v1/play/deposits re-reports it across overlapping
-- sinceHeight windows or this server itself restarts mid-poll. This table is NOT replayed the way
-- game_events is — it is the durable idempotency record a DepositConfirmedOnChain event's
-- replay-safety depends on one layer up (a coin is only ever turned into that event once, at the
-- moment this row is first successfully inserted; see DepositPollerService's javadoc for the
-- ordering/trade-off).
CREATE TABLE deposit_confirmations (
    coin_id        TEXT PRIMARY KEY,
    player_id      TEXT NOT NULL,
    player_index   INT NOT NULL,
    amount_mojos   BIGINT NOT NULL,
    height         BIGINT NOT NULL,
    credited_at_ms BIGINT NOT NULL
);
CREATE INDEX deposit_confirmations_player_idx ON deposit_confirmations (player_id, credited_at_ms DESC);

-- Real on-chain fields for withdrawal_requests, additive — existing rows (the pre-Р-53
-- auto-confirmed stub's output) get NULLs here, which is the honest "we don't know, this predates
-- real chain tracking" rather than a guessed value.
ALTER TABLE withdrawal_requests ADD COLUMN to_address TEXT;
ALTER TABLE withdrawal_requests ADD COLUMN chain_state TEXT;
ALTER TABLE withdrawal_requests ADD COLUMN coin_id TEXT;
ALTER TABLE withdrawal_requests ADD COLUMN height BIGINT;
ALTER TABLE withdrawal_requests ADD COLUMN failure_reason TEXT;
ALTER TABLE withdrawal_requests ADD COLUMN submitted_at_ms BIGINT;
ALTER TABLE withdrawal_requests ADD COLUMN last_checked_at_ms BIGINT;
