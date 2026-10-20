-- Snapshots of the engine state (architecture plan F5). seq is the id of the last journal row (game_events.id) the state includes;
-- a restart loads the newest snapshot that verifies and replays only the journal rows after it. The table is a CACHE of the journal:
-- dropping every row costs a full replay at the next start, never data.
--
-- format_version is the version of the text format of the body (StateCodec.FORMAT_VERSION); state_sha256 the SHA-256 of the
-- canonical state text (StateDigest), which a loader recomputes from the body before it trusts it; body the gzip-compressed text.
CREATE TABLE engine_snapshots (
    seq            BIGINT PRIMARY KEY,
    format_version INT    NOT NULL,
    taken_at_ms    BIGINT NOT NULL,
    state_sha256   TEXT   NOT NULL,
    body           BYTEA  NOT NULL
);
