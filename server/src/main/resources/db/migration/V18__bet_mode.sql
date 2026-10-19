-- How the bet was paid (Р-106, docs/product/fixed_payout_rules.md point 8): PERCENTAGE (stake x leverage x the price move, the mode of every row
-- written so far) or FIXED (win 2 x stake, lose the stake, a tie gives it back; no leverage, no liquidation - the row's leverage is the
-- lowest value as a placeholder). The result of a round for the ratings is still payout - stake - fees, whatever the mode.
ALTER TABLE player_round_history ADD COLUMN mode TEXT NOT NULL DEFAULT 'PERCENTAGE';
