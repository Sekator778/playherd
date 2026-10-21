-- Р-107: the event journal moves to a Chronicle Queue on disk; the players table becomes a projection that is filled AFTER the journal
-- (by the journal projector, on its own thread, with a lag). What a player has that is NOT in the journal - the invite code, who invited
-- them, the channel tag and an API bot's key - is written synchronously BEFORE the command that registers the player, into this table, which
-- the projector copies into the players row when it projects the registration event. The reads of the invite columns (InviteRepository) and
-- the key writes (regenerate / revoke) use this table, so they never wait for the projection.
--
-- players.invite_code / referred_by / source / api_key_hash / api_key_hint stay (the directory still loads the key from players, and
-- operator queries on them keep working): they are copies, current once the projector has caught up.
CREATE TABLE player_sidecar (
    player_id    TEXT PRIMARY KEY,
    invite_code  TEXT,
    referred_by  TEXT,
    source       TEXT,
    api_key_hash TEXT,
    api_key_hint TEXT
);

CREATE UNIQUE INDEX player_sidecar_invite_code_key ON player_sidecar (invite_code) WHERE invite_code IS NOT NULL;
CREATE UNIQUE INDEX player_sidecar_api_key_hash_key ON player_sidecar (api_key_hash) WHERE api_key_hash IS NOT NULL;
CREATE INDEX player_sidecar_referred_by_idx ON player_sidecar (referred_by) WHERE referred_by IS NOT NULL;

INSERT INTO player_sidecar (player_id, invite_code, referred_by, source, api_key_hash, api_key_hint)
SELECT player_id, invite_code, referred_by, source, api_key_hash, api_key_hint
  FROM players
 WHERE invite_code IS NOT NULL OR referred_by IS NOT NULL OR source IS NOT NULL OR api_key_hash IS NOT NULL;
