-- In-app feedback (Р-81, docs/product/decisions.md; docs/research/server_design.md §22): the one message a visitor
-- leaves through the banner / the form, with what the server learned about them on the way in.
--
-- Not a game event: a plain table, never part of game_events and never touched by the replay (EngineRebuildService), so a
-- rebuild of the engine leaves it as it is, and a message can neither change nor be changed by the game.
--
-- What is NOT stored, on purpose: the IP address (the server derives `country` from it and drops it), a name, an e-mail.
--
--   received_at_ms  server clock, epoch ms (the *_ms naming of V1)
--   message         the text, trimmed, 1..2000 characters (Java String.length(), the unit of a JS string's length) and
--                   without U+0000, which a Postgres TEXT cannot hold; the limits are checked by the server, not here
--   player_id       the signed-in player's id (from the session cookie), NULL for a visitor who is not signed in.
--                   No foreign key to players(player_id), a decision of 2026-10-11. Checked: nothing in the server
--                   deletes a players row or rebuilds the table by replay (PlayerDirectory only inserts and sets a
--                   nickname), so an FK would not break on the game's own account. It is left out because a message
--                   must be accepted whatever state players is in -- an integrity error would be the one way for "send
--                   feedback" to answer 500 -- and sessions, withdrawal_requests, deposit_requests and
--                   player_round_history already keep player_id as just such a soft reference. The operator's listing
--                   LEFT JOINs players, so a row whose player is gone still shows.
--   country         ISO 3166-1 alpha-2 from the IP (DB-IP "IP to Country Lite"), NULL when unknown or not loaded yet
--   time_zone       the browser's IANA time zone as the client sent it (sanitised, at most 64 characters), or NULL
--   language        the browser's language tag (at most 35 characters), or NULL
--   page            the route the visitor was on (starts with "/", at most 200 characters), or NULL
--   client          web | app | pwa, or NULL
--
-- There is no server_version column: the server has no build version or commit to record (checked 2026-10-11: nothing of
-- the kind reaches the process; the only version is the POM's constant 0.1.0-SNAPSHOT).
CREATE TABLE feedback (
    id             BIGSERIAL PRIMARY KEY,
    received_at_ms BIGINT NOT NULL,
    message        TEXT   NOT NULL,
    player_id      TEXT,
    country        TEXT,
    time_zone      TEXT,
    language       TEXT,
    page           TEXT,
    client         TEXT
);

-- The operator's list (newest first, from a date) and the daily cap (how many messages came in the last 24 hours).
CREATE INDEX feedback_received_at_idx ON feedback (received_at_ms);
