-- Markets, categories and live prices (Р-69, Р-70; docs/research/markets_architecture.md §3, §6).
-- Everything that existed before this migration is BTC: the DEFAULT 'BTC' / 0 below labels the
-- whole history without a backfill.

CREATE TABLE markets (
    id            TEXT PRIMARY KEY,
    kind          TEXT NOT NULL,          -- PERP | ODDS
    category      TEXT NOT NULL,
    symbol        TEXT NOT NULL,
    title         TEXT NOT NULL,
    subtitle      TEXT,
    icon_url      TEXT,
    source        TEXT NOT NULL,          -- hyperliquid | polymarket | synthetic
    source_ref    TEXT NOT NULL,
    source_meta   JSONB,
    max_leverage  INT NOT NULL,
    lot_exp       INT NOT NULL,
    oracle_index  INT NOT NULL UNIQUE,    -- append-only; published on chain as the record's asset
    status        TEXT NOT NULL,          -- ACTIVE | CLOSED
    listed_at_ms  BIGINT NOT NULL,
    closed_at_ms  BIGINT,
    close_reason  TEXT,
    ends_at_ms    BIGINT
);

INSERT INTO markets (id, kind, category, symbol, title, source, source_ref, max_leverage, lot_exp,
                     oracle_index, status, listed_at_ms)
VALUES ('BTC', 'PERP', 'crypto', 'BTC', 'Bitcoin', 'hyperliquid', 'BTC', 40, 0, 0, 'ACTIVE', 0);

-- One row per poll of a price source: {"<marketId>": "<raw price>"}. Restores the tick buffer
-- after a restart; rows older than the buffer are pruned.
CREATE TABLE price_snapshots (
    source TEXT   NOT NULL,
    at_ms  BIGINT NOT NULL,
    prices JSONB  NOT NULL,
    PRIMARY KEY (source, at_ms)
);
CREATE INDEX price_snapshots_at_idx ON price_snapshots (at_ms);

ALTER TABLE round_results        ADD COLUMN market TEXT NOT NULL DEFAULT 'BTC';
ALTER TABLE player_round_history ADD COLUMN market TEXT NOT NULL DEFAULT 'BTC';
ALTER TABLE oracle_records       ADD COLUMN market TEXT NOT NULL DEFAULT 'BTC';
ALTER TABLE oracle_records       ADD COLUMN asset_index INT NOT NULL DEFAULT 0;

CREATE INDEX round_results_market_interval_window_idx ON round_results (market, interval, window_start_ms);
CREATE INDEX player_round_history_market_idx ON player_round_history (market, settled_at_ms DESC);
CREATE INDEX oracle_records_round_idx ON oracle_records (round_id);
