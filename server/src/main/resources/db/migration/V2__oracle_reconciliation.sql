-- Task item 1c (FIXED INTERFACE b/c durable-delivery reconciliation, Р-40/41/47): this server's
-- own last-known copy of chainsvc's per-record state, polled every 60s by
-- OracleReconciliationService via GET /v1/oracle/records/{seq} and exposed as the optional
-- `oracleAnchor` on settled/cancelled round DTOs (RoundResultRepository.findAnchorByRoundId).
--
-- `state` defaults to 'unknown' — the honest answer for "we have not asked chainsvc about this
-- record yet", and also one of chainsvc's own possible answers (FIXED INTERFACE b:
-- "queued"|"in_flight"|"confirmed"|"unknown"), so a fresh row and a row chainsvc itself reports as
-- unknown look identical until the first successful poll either way.
ALTER TABLE oracle_records
    ADD COLUMN state TEXT NOT NULL DEFAULT 'unknown',
    ADD COLUMN height BIGINT,
    ADD COLUMN coin_id TEXT,
    ADD COLUMN state_checked_at_ms BIGINT;
