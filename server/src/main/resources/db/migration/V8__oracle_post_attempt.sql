-- A record this server has POSTed to chainsvc at least once (second adversarial review, finding F2).
--
-- "chainsvc holds seq N" is not "chainsvc holds MY record N": chainsvc keeps the first record it saw for a seq
-- and answers a second one with an idempotent no-op (QueueFile::enqueue), whatever its content. When the server
-- issued a seq the chain service already had (a seq floor that arrived late, a second writer on the same
-- singleton), the record was marked "confirmed at height H" for a record chainsvc never took.
--
-- OraclePublisherService now writes this column BEFORE the POST of a row, and only a row with a value here may
-- be marked as already known to chainsvc (OracleRecordRepository.markKnown). A row that was never POSTed and
-- that chainsvc nevertheless holds is a seq collision: it gets state 'conflict' and is not sent.
--
-- Rows that exist now were all handled by the previous publisher, which POSTed without writing anything: they
-- count as attempted at the time they were queued.
ALTER TABLE oracle_records ADD COLUMN post_attempted_at_ms BIGINT;
UPDATE oracle_records SET post_attempted_at_ms = queued_at_ms;
