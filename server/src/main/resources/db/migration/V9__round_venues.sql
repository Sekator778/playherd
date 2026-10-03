-- The exchange a round runs on (Р-76: "a round runs entirely on one exchange").
--
-- A market of the crypto category has a primary exchange and, where one exists, a backup (markets.source_meta says which:
-- {"backup":{"source":"kraken","ref":"BTC/USD"}}). The exchange of a round is chosen ONCE, when the round opens, and
-- never changed: the primary if it has a live price at the round's start, else the backup, else the round waits until
-- one of them can price its start. The round is then settled — open, close, path, liquidation — from that exchange's
-- prices alone.
--
-- round_venues holds the choice. It is written once (INSERT ... ON CONFLICT DO NOTHING, then the stored row is read
-- back: whoever wrote first decided) and only for rounds of markets that have a backup; the server loads the recent
-- rows at start, so a restart does not change the exchange of a round that is running.
CREATE TABLE round_venues (
    round_id      TEXT   PRIMARY KEY,
    market_id     TEXT   NOT NULL,
    venue         TEXT   NOT NULL,          -- coinbase | kraken | hyperliquid
    decided_at_ms BIGINT NOT NULL
);
CREATE INDEX round_venues_decided_idx ON round_venues (decided_at_ms);

-- What a settled or cancelled round says about its exchange: the venue it ran on, NULL for a round of a market that
-- has none (every round before this migration, and every market without a backup). The canonical results JSON of such
-- a round carries it as the root key "venue"; a round with none has the JSON it always had.
ALTER TABLE round_results ADD COLUMN venue TEXT;
