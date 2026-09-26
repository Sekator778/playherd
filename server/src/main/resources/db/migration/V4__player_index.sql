-- Р-53 (real on-chain PLAY withdraw/deposit): a stable per-player integer index for chainsvc's
-- CAT deposit-address derivation (FIXED INTERFACE: POST /v1/play/deposit-address {playerIndex} —
-- chainsvc derives the address from the operator master key at playerIndex + a fixed offset that
-- never collides with the oracle/treasury keys, see docs/research/server_design.md). This is a
-- chain-derivation index, not a display id — once handed to chainsvc for a player it must never
-- change or be reused, or that player's already-told-about deposit address would stop matching
-- what chainsvc derives.
ALTER TABLE players ADD COLUMN player_index INT;

-- Existing players (if any): deterministic backfill ordered by first-seen (created_at_ms), tied
-- broken by player_id, so replaying this migration against any copy of this database assigns the
-- exact same indices in the exact same order every time.
WITH ordered AS (
    SELECT player_id, (ROW_NUMBER() OVER (ORDER BY created_at_ms, player_id) - 1)::INT AS idx
    FROM players
)
UPDATE players p SET player_index = ordered.idx FROM ordered WHERE ordered.player_id = p.player_id;

ALTER TABLE players ALTER COLUMN player_index SET NOT NULL;
ALTER TABLE players ADD CONSTRAINT players_player_index_key UNIQUE (player_index);

-- Seeds the counter new players draw from (ServerMetaRepository#reserveNextPlayerIndex), so the
-- very next allocation continues right after the highest index backfilled above (0 on a fresh,
-- player-less database).
INSERT INTO server_meta (key, value)
VALUES ('next_player_index', (SELECT COUNT(*) FROM players)::TEXT)
ON CONFLICT (key) DO NOTHING;
