-- Game economy, stage A (Р-92, docs/product/economy_implementation_plan.md A1, A2, A4): the leverage cap of each round
-- length, the maintenance constant kappa and the herd-fee rate r_m become per-market columns.
--
-- Round lengths are the engine's Interval names: HOURLY is the SHORT round (herd.round-durations.hourly-sec = 180 s, 3 min),
-- DAILY the LONG one (daily-sec = 900 s, 15 min). So cap_hourly is the plan's "3 min" cap and cap_daily its "15 min" cap.
--
--   l (maintenance rate) = kappa / cap(market, interval)      kappa 0.1 for price markets; 0.5 for odds markets, which keeps
--                                                             their l = 1/(2*max_leverage) as before
--   herd fee             = stake * min(1%, herd_fee_bp/10000 * leverage)
--   stress_move          = 99.9th percentile move of a round, a fraction of price; NULL until stage B/D fill it
--
-- max_leverage keeps its meaning for every bet placed before this change (it is replayed with the original rules).

ALTER TABLE markets ADD COLUMN cap_hourly   INT;
ALTER TABLE markets ADD COLUMN cap_daily    INT;
ALTER TABLE markets ADD COLUMN kappa        NUMERIC(6, 4);
ALTER TABLE markets ADD COLUMN herd_fee_bp  NUMERIC(8, 3);
ALTER TABLE markets ADD COLUMN stress_move  NUMERIC(10, 6);

-- every market first gets its own single cap for both lengths and the defaults ...
UPDATE markets SET cap_hourly = max_leverage, cap_daily = max_leverage,
                   kappa = CASE WHEN kind = 'ODDS' THEN 0.5 ELSE 0.1 END,
                   herd_fee_bp = 1.0;

-- ... then the twelve price markets of the plan get their starting values (by symbol: the ids differ by price source,
-- BTC / spot_BTC / xyz_NVDA ...). xyz 3-min caps are the lower first-pass values of the plan.
UPDATE markets m SET cap_hourly = v.cap_hourly, cap_daily = v.cap_daily, herd_fee_bp = v.herd_fee_bp
FROM (VALUES
    ('BTC',      1200,  500, 0.92),
    ('ETH',      1000,  400, 1.0),
    ('SOL',       600,  300, 1.0),
    ('DOGE',      600,  300, 1.0),
    ('XRP',       600,  300, 1.0),
    ('HYPE',      600,  250, 1.0),
    ('NVDA',     1500,  800, 0.75),
    ('TSLA',     1500, 1000, 0.70),
    ('BRENTOIL', 1000,  500, 0.83),
    ('GOLD',     2000, 1200, 0.48),
    ('XYZ100',   2000, 1500, 0.45),
    ('SP500',    4000, 2000, 0.24)
) AS v(symbol, cap_hourly, cap_daily, herd_fee_bp)
WHERE m.kind = 'PERP' AND m.symbol = v.symbol;

ALTER TABLE markets ALTER COLUMN cap_hourly  SET NOT NULL;
ALTER TABLE markets ALTER COLUMN cap_daily   SET NOT NULL;
ALTER TABLE markets ALTER COLUMN kappa       SET NOT NULL;
ALTER TABLE markets ALTER COLUMN herd_fee_bp SET NOT NULL;
