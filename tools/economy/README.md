# tools/economy — weekly recompute of caps, fees and stress moves (economy stage D)

`recompute_caps.py` reads the last weeks of real candles and writes a proposal for the `markets` columns
`cap_hourly` (3-minute round), `cap_daily` (15-minute round), `kappa`, `herd_fee_bp` and `stress_move`.
Python 3 standard library only. It writes to no database and touches no server; the only network access is to the public
candle APIs of Coinbase Exchange and Hyperliquid (`candleSnapshot`).

Method (`docs/research/game_economy_math.md` sections 1-3.2 and 9; plan: `docs/product/economy_implementation_plan.md`, stage D):

- Candles: Coinbase 1-minute for BTC, ETH, SOL, DOGE, XRP, HYPE; Hyperliquid for them as a fallback and for the xyz perps
  NVDA, TSLA, GOLD, BRENTOIL, SP500, XYZ100. Hyperliquid serves only the latest ~5,000 candles per interval, so the tool
  fetches 1m, 3m and 15m and, per market and round length, takes the longest history (within 10 % of the longest, the finest
  interval). In practice the xyz 3-minute rounds get ~10 days (3m candles); everything else the full period. The depth used
  is listed per market in the proposal.
- Per (market, round length): sigma and excess kurtosis of the round's log return; exact cap = the leverage L at which
  P(liquidation) = 35 % with kappa = 0.1 and maintenance rate l = kappa/L, from the real low/high of each round (long and
  short averaged); proposed cap = 0.8 x exact, rounded down to 1 / 1.2 / 1.5 / 2 / 2.5 / 3 / 4 / 5 / 6 / 8 x 10^k; exact cap
  per 7-day block (regime spread); P(liquidation) at the proposed cap overall and per week.
- `herd_fee_bp` = clip(0.10 x sigma3 in bp, 0.2, 1.0). `stress_move` = max(99.9th percentile of |log return|, 5 sigma),
  larger of the two round lengths (per-length values are in the JSON/MD).
- RTP and liquidation share by leverage band (2-10, 11-100, 101-cap/4, cap/4-cap) at the proposed cap: RTP = 1 - herd fee - remainder
  gap, no skill (long and short averaged); the remainder on liquidation is returned at the candle extreme, as in the engine.
  The 0.1 PLAY network fee is excluded.
- Rule from the plan: the tool never raises anything above the current value by itself. Every proposed increase (cap or
  fee) is listed under `needs_operator_approval` and held back in `markets_apply_without_approval`.

## Run

```
python3 -I tools/economy/recompute_caps.py --weeks 4 --out <dir>            # fetches (about 3 minutes), writes <dir>/candles/*, caps_proposal.{json,md}
python3 -I tools/economy/recompute_caps.py --from-cache <dir>/candles --out <dir2>   # same candles, no network
python3 -I tools/economy/recompute_caps.py --weeks 4 --out <dir> --markets BTC,ETH   # a subset
```

`--out` must be outside the repo (or gitignored): the candle cache is ~60 MB. `--current file.json` replaces the built-in
current values (those seeded by `V11__economy_stage_a.sql`) with `{"BTC": {"cap_hourly": ..., "cap_daily": ..., "herd_fee_bp": ...}, ...}`
read from the live `markets` table.

Output: `caps_proposal.json` (`markets.<SYMBOL>` has exactly the column names; `markets_apply_without_approval`, `needs_operator_approval`,
`notes`, `details` with depth, sigma, kurtosis, stress, weekly caps and the RTP bands) and `caps_proposal.md` (tables for a human).

Tests: `cd tools/economy && python3 -I -m unittest`.

## Applying a proposal (operator only, on the operator's word)

Read `caps_proposal.md`, decide, then run on the stand's database. Never run it from this tool or from an unattended job. Template
(values from `markets_apply_without_approval`; values listed in `needs_operator_approval` only if the operator approves them):

```sql
UPDATE markets SET cap_hourly = :cap_hourly, cap_daily = :cap_daily, kappa = :kappa,
                   herd_fee_bp = :herd_fee_bp, stress_move = :stress_move
WHERE kind = 'PERP' AND symbol = :symbol;      -- one statement per symbol, the symbol keys of the JSON
```

Example for one market:

```sql
UPDATE markets SET cap_hourly = 1200, cap_daily = 500, kappa = 0.1, herd_fee_bp = 0.83, stress_move = 0.014465
WHERE kind = 'PERP' AND symbol = 'BTC';
```

Market ids differ by price source (`BTC`, `spot_BTC`, `xyz_NVDA`), the symbol does not; V11 matches on `kind = 'PERP' AND symbol`
for the same reason. A cap change applies to bets placed afterwards (the cap sets the maintenance rate l = kappa / cap).
