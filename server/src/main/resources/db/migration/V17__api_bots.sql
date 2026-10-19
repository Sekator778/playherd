-- Bots of outside developers (Р-106): a signed-in person creates up to three API bots; each is its own player with its own
-- balance and plays through /api/rpc with `Authorization: Bearer <key>`.
--
--   kind             HUMAN | HOUSE_BOT | API_BOT. `is_bot` keeps its meaning (the house's own bots of the `bots/` module, driven by
--                    BotService) and stays FALSE for an API bot, so nothing that selects the house bots can ever pick an API bot up.
--   owner_player_id  the person who created the bot (API_BOT only).
--   api_key_hash     SHA-256 (hex) of the bot's key; the key itself is shown once at creation and never stored. NULL once revoked.
--   api_key_hint     what the owner sees to tell keys apart: the prefix and the last four characters, never enough to use the key.
-- The key hash lives here and not in `game_events`: the journal is replayed and may be exported, a credential does not belong in it.
-- The bot's existence in the journal is the ordinary PlayerRegistered event (the starter pack), exactly as for any player.
ALTER TABLE players ADD COLUMN kind TEXT NOT NULL DEFAULT 'HUMAN';
ALTER TABLE players ADD COLUMN owner_player_id TEXT;
ALTER TABLE players ADD COLUMN api_key_hash TEXT;
ALTER TABLE players ADD COLUMN api_key_hint TEXT;

UPDATE players SET kind = 'HOUSE_BOT' WHERE is_bot;

ALTER TABLE players ADD CONSTRAINT players_kind_check CHECK (kind IN ('HUMAN', 'HOUSE_BOT', 'API_BOT'));
CREATE UNIQUE INDEX players_api_key_hash_key ON players (api_key_hash) WHERE api_key_hash IS NOT NULL;
CREATE INDEX players_owner_idx ON players (owner_player_id) WHERE owner_player_id IS NOT NULL;
