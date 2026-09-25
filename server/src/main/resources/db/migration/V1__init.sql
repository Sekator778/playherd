-- Herd server schema (предложение — docs/research/server_design.md "Persistence").
--
-- Append-only event log (game_events) is the only table replayed to rebuild in-memory
-- GameEngine/LedgerBook/CrowdAccount state on startup. Every other table is a read model,
-- written in the same transaction as the event that produced it, and read directly by the API
-- — never replayed.

CREATE TABLE server_meta (
    key   TEXT PRIMARY KEY,
    value TEXT NOT NULL
);

CREATE TABLE players (
    player_id      TEXT PRIMARY KEY,
    wallet_address TEXT NOT NULL UNIQUE,
    nickname       TEXT,
    is_bot         BOOLEAN NOT NULL DEFAULT FALSE,
    bot_strategy   TEXT,
    created_at_ms  BIGINT NOT NULL
);

-- Append-only. Replayed in `id` order at startup (crowdvsmarket.server.events.EventStore /
-- EventApplier) to rebuild the in-memory engine. `payload` carries everything the corresponding
-- GameEngine/LedgerBook call needs to be replayed deterministically (see GameEvent subtypes).
CREATE TABLE game_events (
    id         BIGSERIAL PRIMARY KEY,
    event_type TEXT NOT NULL,
    payload    JSONB NOT NULL,
    at_ms      BIGINT NOT NULL
);

-- Read model: one row per resolved (settled or cancelled) round, for round history, public
-- profiles, results.json (FIXED INTERFACE 2) and the cancellations log. Never replayed.
CREATE TABLE round_results (
    round_id             TEXT PRIMARY KEY,
    interval             TEXT NOT NULL,
    window_start_ms      BIGINT NOT NULL,
    window_end_ms        BIGINT NOT NULL,
    status               TEXT NOT NULL, -- SETTLED | CANCELLED
    open_price_cents     BIGINT,
    close_price_cents    BIGINT,
    positions_json       JSONB,
    crowd_json           JSONB,
    results_hash         TEXT,
    -- Exact bytes hashed into results_hash (FIXED INTERFACE 2) — stored verbatim, not
    -- regenerated on read, so GET /api/rounds/{id}/results.json always reproduces byte-identical
    -- output regardless of later DTO-shape changes to positions_json/crowd_json.
    canonical_json       TEXT,
    settled_at_ms        BIGINT,
    cancelled_at_ms       BIGINT,
    cancellation_reason  TEXT,
    refunded_bet_count   INT,
    created_at_ms        BIGINT NOT NULL
);
CREATE INDEX round_results_interval_window_idx ON round_results (interval, window_start_ms);
CREATE INDEX round_results_status_idx ON round_results (status);

-- Read model: one row per (settled round, player who had a bet in it) — the source for
-- publicProfile()/myProfile()'s `history` (ProfileRoundEntryDto). Kept separate from
-- round_results.positions_json (which holds the WHOLE round's positions, richer fields, for
-- round()/results.json) so a per-player history query is a plain indexed lookup, not a JSONB
-- containment scan.
CREATE TABLE player_round_history (
    round_id           TEXT NOT NULL,
    player_id          TEXT NOT NULL,
    interval           TEXT NOT NULL,
    settled_at_ms       BIGINT NOT NULL,
    direction           TEXT NOT NULL,
    leverage             INT NOT NULL,
    stake_mojos         BIGINT NOT NULL,
    liquidated           BOOLEAN NOT NULL,
    direction_correct    BOOLEAN,
    payout_mojos        BIGINT NOT NULL,
    PRIMARY KEY (round_id, player_id)
);
CREATE INDEX player_round_history_player_idx ON player_round_history (player_id, settled_at_ms DESC);

CREATE TABLE withdrawal_requests (
    request_id      TEXT PRIMARY KEY,
    player_id       TEXT NOT NULL,
    amount_mojos    BIGINT NOT NULL,
    state           TEXT NOT NULL,
    requested_at_ms BIGINT NOT NULL
);

CREATE TABLE deposit_requests (
    request_id      TEXT PRIMARY KEY,
    player_id       TEXT NOT NULL,
    amount_mojos    BIGINT NOT NULL,
    state           TEXT NOT NULL,
    requested_at_ms BIGINT NOT NULL
);

CREATE TABLE auth_challenges (
    nonce         TEXT PRIMARY KEY,
    message       TEXT NOT NULL,
    address       TEXT,
    expires_at_ms BIGINT NOT NULL,
    used          BOOLEAN NOT NULL DEFAULT FALSE,
    created_at_ms BIGINT NOT NULL
);

CREATE TABLE sessions (
    session_id    TEXT PRIMARY KEY,
    player_id     TEXT NOT NULL,
    created_at_ms BIGINT NOT NULL,
    expires_at_ms BIGINT NOT NULL
);

-- Oracle publication log (Р-40/41/47): one row per (roundId) record ever queued to chainsvc.
-- `seq` is the server's own monotonic oracle sequence number (distinct from round ids).
CREATE TABLE oracle_records (
    seq            BIGINT PRIMARY KEY,
    round_id       TEXT NOT NULL,
    ts             BIGINT NOT NULL,
    price_cents    BIGINT NOT NULL,
    results_hash   TEXT NOT NULL,
    source         TEXT NOT NULL, -- live | replay
    source_ts      BIGINT NOT NULL,
    queued_at_ms   BIGINT NOT NULL,
    chainsvc_queued BOOLEAN NOT NULL DEFAULT FALSE
);
