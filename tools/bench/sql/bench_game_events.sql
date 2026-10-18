-- Copy of game_events (V1__init.sql + V3__event_schema_version.sql) for the Postgres ceiling, S-F.
-- Created in the throwaway database bench_pg, never in the stand's `herd` database.
DROP TABLE IF EXISTS bench_game_events;
CREATE TABLE bench_game_events (
    id             BIGSERIAL PRIMARY KEY,
    event_type     TEXT NOT NULL,
    payload        JSONB NOT NULL,
    at_ms          BIGINT NOT NULL,
    schema_version INT NOT NULL DEFAULT 1
);
