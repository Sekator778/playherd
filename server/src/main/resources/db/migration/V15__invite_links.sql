-- Personal invite link playherd.app/?ref=<code> and acquisition source. No rewards of any kind: the columns only
-- answer "who came through whose link" and "from which channel" (docs/research/features_growth_2026-10-18.md, item 1).
--   invite_code  the player's own short public code (8 lowercase hex chars); NULL for bots. Not the player id and not the wallet.
--   referred_by  player_id of the player whose link brought this one; written once, at registration, never changed.
--   source       the channel tag of a link we post ourselves (?src=discord ...); written once, at registration.
ALTER TABLE players ADD COLUMN invite_code TEXT;
ALTER TABLE players ADD COLUMN referred_by TEXT;
ALTER TABLE players ADD COLUMN source TEXT;

-- Existing real players: a deterministic code (first 8 hex chars of md5 over the id and a fixed label), so replaying the
-- migration on any copy gives the same codes. On the rare clash the later row keeps NULL and is given a code on first use.
UPDATE players p SET invite_code = d.code
FROM (
    SELECT DISTINCT ON (code) player_id, code
    FROM (SELECT player_id, substr(md5(player_id || ':herd-invite'), 1, 8) AS code, created_at_ms
          FROM players WHERE NOT is_bot) c
    ORDER BY code, created_at_ms, player_id
) d
WHERE p.player_id = d.player_id;

CREATE UNIQUE INDEX players_invite_code_key ON players (invite_code) WHERE invite_code IS NOT NULL;
CREATE INDEX players_referred_by_idx ON players (referred_by) WHERE referred_by IS NOT NULL;
CREATE INDEX players_source_idx ON players (source) WHERE source IS NOT NULL;
