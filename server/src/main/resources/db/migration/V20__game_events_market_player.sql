-- The journal can be read per market and per player without parsing its JSON (architecture plan, docs/research/lmax_roadmap.md step 1).
-- market / player_id are copies of what the payload says, filled by EventStore on every append (EventStore.marketOf / playerOf) and, for the rows
-- written before this migration, by the UPDATE statements below. The payload is not touched: the journal bytes and the replay are the same.
-- NULL means the event has no market (or no player): a player, ledger or treasury event has no market, a round or market event no player.
--
-- The backfill runs here, in the migration, and not as a startup job: it is one pass over the table, the columns are complete the moment the
-- server starts (a reader never has to know about a half-filled column), and there is no second code path to keep. The cost is a start
-- that waits for it; the run time on the live journal (about 330 000 rows) is not measured.
ALTER TABLE game_events ADD COLUMN market    TEXT NULL;
ALTER TABLE game_events ADD COLUMN player_id TEXT NULL;

-- A round or bet event written before markets existed has no market key: it is BTC (the record's constructor says the same).
UPDATE game_events
   SET market = COALESCE(payload->>'market', 'BTC')
 WHERE event_type IN ('BetPlaced', 'BetCancelled', 'RoundResolved', 'RoundCancelled');
UPDATE game_events
   SET market = payload->>'marketId'
 WHERE event_type IN ('MarketListed', 'MarketClosed');
UPDATE game_events
   SET player_id = payload->>'playerId'
 WHERE event_type IN ('PlayerRegistered', 'NicknameChanged', 'BotStrategyAssigned', 'BetPlaced', 'BetCancelled', 'BotRetired',
                      'WithdrawalRequested', 'DepositRequested', 'WithdrawalReversed', 'DepositReversed', 'DepositConfirmedOnChain');

-- Built after the backfill (cheaper than maintaining them through it). Partial: a row with no market / no player is never asked for by them.
CREATE INDEX game_events_market_idx ON game_events (market, id) WHERE market IS NOT NULL;
CREATE INDEX game_events_player_idx ON game_events (player_id, id) WHERE player_id IS NOT NULL;
