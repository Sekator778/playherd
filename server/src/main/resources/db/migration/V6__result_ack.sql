-- Р-62's "while you were away" fix (a player offline at settlement never otherwise sees the
-- result moment, docs/product/design/result-moment.md): tracks, per (round, player), whether that
-- player has acknowledged (played/dismissed) the result moment for their own settled position.
-- NULL = not yet seen (myUnseenResults()'s WHERE clause); set once by ackResults(). A durable
-- column on the existing read model, not a new table -- one row per (round_id, player_id) already
-- exists here for every settled position (see V1__init.sql's comment on player_round_history).
ALTER TABLE player_round_history ADD COLUMN acked_at_ms BIGINT;

-- Partial index: only unacknowledged rows are ever queried by player_id (myUnseenResults()) --
-- once acked, a row never needs this index again.
CREATE INDEX player_round_history_unacked_idx ON player_round_history (player_id) WHERE acked_at_ms IS NULL;
