-- Task item 4 (event schema evolution, per docs/reviews/2026-09-26-independent-critique.md):
-- game_events had no discriminator for the *shape* a given event_type's payload was written in,
-- separate from the type name itself — this column is that hook for the day a GameEvent record's
-- fields actually change shape (not just get renamed, which TYPE_ALIASES in EventStore.java
-- handles instead). DEFAULT 1 backfills every already-persisted row as version 1, matching what
-- EventStore.CURRENT_SCHEMA_VERSION already assumes for everything written so far.
ALTER TABLE game_events
    ADD COLUMN schema_version INT NOT NULL DEFAULT 1;
