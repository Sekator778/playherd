-- Fees in the player's round history (Р-102, fee display): the bet's total fees (herd + network, as stored with the bet when it was placed),
-- so the result tile can say "staked 100 -> returned 109.20, fees 1.07".
--
-- Written at settlement, from the fees stored with the bet. A tie settles with the stake back and the fees KEPT (economy_implementation_plan A4),
-- so the value is what was kept. A cancelled round / a cancelled bet writes no history row (the fees are refunded; V1 keeps no row for them).
-- NULL: a row written before this migration, or a legacy bet that was charged no fees - the client shows no fees line for it.
ALTER TABLE player_round_history ADD COLUMN fees_mojos BIGINT;
