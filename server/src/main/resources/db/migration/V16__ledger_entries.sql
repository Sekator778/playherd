-- The players' ledger history (architecture plan F3): one row per movement of a balance, a PROJECTION of the journal.
--
-- seq is the LedgerBook's own sequence number (1, 2, 3 ... in the order the movements happen); replaying the journal
-- gives every movement the same seq again, which is what makes the projection idempotent (INSERT ... ON CONFLICT (seq) DO
-- NOTHING) and lets a rebuild find and write the rows that are missing (a crash or a failed write leaves a gap).
-- The table is never read to rebuild the engine; GameService#myLedger reads it (a player's page, newest first).
--
-- type is the LedgerEntryType name; delta_mojos the signed change of the balance; round_id is set for stake/settlement
-- entries and NULL otherwise; at_ms the time the movement was booked, epoch ms.
CREATE TABLE ledger_entries (
    seq         BIGINT PRIMARY KEY,
    player_id   TEXT   NOT NULL,
    type        TEXT   NOT NULL,
    delta_mojos BIGINT NOT NULL,
    round_id    TEXT,
    at_ms       BIGINT NOT NULL
);
CREATE INDEX ledger_entries_player_seq ON ledger_entries (player_id, seq DESC);
