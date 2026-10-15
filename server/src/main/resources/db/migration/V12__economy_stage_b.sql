-- Game economy, stage B (Р-92, docs/product/economy_implementation_plan.md B3-B5): the stress move of each market, the retirement
-- of bots, and the registration counter per IP.
--
-- stress_move is the 99.9th-percentile move of a 3-minute round, a fraction of price, from docs/research/game_economy_math.md
-- section 1 (table "p99.9 |r| 3 min", Coinbase/Hyperliquid candles of 2026-10: 3.6 days, the figures of the plan). The 15-minute round
-- uses it multiplied by 2.2 (the median sigma(15 min) / sigma(3 min) of the same table, 2.0-2.4); that factor is in the code
-- (MarketEconomy.LONG_ROUND_STRESS_FACTOR), not here. Weekly recalculation (stage D) overwrites the column.
--
-- A price market the plan has no figure for gets 0.016 (the largest of the twelve, SOL), an odds market 0.20 (UNVERIFIED: there is no
-- candle data for odds markets; a proposal for the first pass, to be replaced when there is).

UPDATE markets m SET stress_move = v.stress_move
FROM (VALUES
    ('BTC',      0.008170),
    ('ETH',      0.013080),
    ('SOL',      0.016010),
    ('DOGE',     0.014560),
    ('XRP',      0.014780),
    ('HYPE',     0.012710),
    ('NVDA',     0.005270),
    ('TSLA',     0.007580),
    ('BRENTOIL', 0.004300),
    ('GOLD',     0.004380),
    ('XYZ100',   0.003250),
    ('SP500',    0.001610)
) AS v(symbol, stress_move)
WHERE m.kind = 'PERP' AND m.symbol = v.symbol;

UPDATE markets SET stress_move = 0.016 WHERE kind = 'PERP' AND stress_move IS NULL;
UPDATE markets SET stress_move = 0.20 WHERE kind = 'ODDS' AND stress_move IS NULL;

-- A retired bot (plan B4): retired_at_ms is when, replaced says that a new bot has taken its place (the new one comes at least an
-- hour after the retirement, and exactly one per retired bot).
ALTER TABLE players ADD COLUMN retired_at_ms BIGINT;
ALTER TABLE players ADD COLUMN replaced BOOLEAN NOT NULL DEFAULT FALSE;

-- Plan B3: new players per IP per 24 hours. Only a salted hash of the address and the time of the registration; rows older than a
-- day are deleted. The salt is a random value kept in server_meta.
CREATE TABLE registration_ips (
    id      BIGSERIAL PRIMARY KEY,
    ip_hash TEXT   NOT NULL,
    at_ms   BIGINT NOT NULL
);
CREATE INDEX registration_ips_hash_idx ON registration_ips (ip_hash, at_ms);
