-- Herd fee, Р-102 (docs/research/economy_variant_a_rules_2026-10-17.md rules 2a-2g, accepted by Alex 2026-10-18):
--
--   herd fee = stake * min(20%, k * herd_fee_bp/10000 * leverage)
--   k        = 1 in the short (HOURLY, 3 min) round; in the long (DAILY, 15 min) round 2.2 for a price market and 3.0 for an
--              odds market (that factor is in the code, MarketEconomy.longRoundFeeFactor, not here)
--
-- This migration moves r_m (herd_fee_bp) of five price markets and of the odds markets; V11 seeded the rows by symbol / kind.
-- Bets already placed keep the fees stored with them.

UPDATE markets m SET herd_fee_bp = v.herd_fee_bp
FROM (VALUES
    ('ETH',  1.05),
    ('SOL',  1.25),
    ('DOGE', 1.25),
    ('XRP',  1.10),
    ('HYPE', 1.15)
) AS v(symbol, herd_fee_bp)
WHERE m.kind = 'PERP' AND m.symbol = v.symbol;

UPDATE markets SET herd_fee_bp = 2.5 WHERE kind = 'ODDS';
