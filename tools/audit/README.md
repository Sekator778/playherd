# tools/audit

`settlement_audit.py` re-computes every settled position of the stand from the stored facts and compares it with what was paid.
Python 3, standard library only. Read-only: the database is queried over ssh inside a `begin read only` transaction; Coinbase is
read through its public candles endpoint.

```
tools/audit/settlement_audit.py --since 72h          # last 72 hours (default 24h); also 7d, an ISO time (UTC), epoch ms
tools/audit/settlement_audit.py --since 2026-10-18T07:00Z --until 2026-10-18T12:00Z
tools/audit/settlement_audit.py --since 24h --no-coinbase
tools/audit/settlement_audit.py --since 72h --save-data /tmp/audit.json     # keep the fetched data
tools/audit/settlement_audit.py --load-data /tmp/audit.json                 # re-run offline on it
```

Connection settings come from `.local/vps.env` (`HERD_VPS_HOST`, `HERD_VPS_KEY`, `HERD_VPS_SSH_PORT`), like `tools/vps`; the host
key is checked against `~/.ssh/known_hosts.herd-vps` (`HERD_KNOWN_HOSTS` overrides), the database container is `herd-db-1`
(`HERD_DB_CONTAINER`). Nothing about the host is written to the output.

## What is checked (people and bots alike)

| code | check |
|---|---|
| `size`, `liq-price` | position size `round8(S*L/P0)` and liquidation price `round2((Q*P0 - s*M) / (Q*(1 - l*s)))` with `l = kappa/cap` from `liqCap`/`liqKappa` of the BetPlaced event (a legacy bet: `l = 1/(2*maxLeverage)`; a bet without the parameters: the market's current ones, reported as a warning only) |
| `liquidation-flag` | the stored RoundResolved path against the stored liquidation price (long: `low <= liq`, short: `high >= liq`) |
| `payout` | not liquidated: `max(0, M + s*Q*(close - P0))`; liquidated: the remainder at the extreme of the triggering candle (a legacy bet: 0); capped at `min(20*S, 0.1 % of the treasury)` (not for legacy bets). The treasury is `--treasury` (default the engine constant 99 998 100 PLAY): a payout above 99 998 PLAY needs the real balance |
| `fee` | herd fee `ceil3(S*min(guard, k*r_m*L))`, at least 0.001, network fee 0.1; k = 1 / 2.2 (price) / 3.0 (odds) for the short / long round; before the switch of 2026-10-18 ~07:53 UTC k = 1 and guard 1 % and r_m = 1.0 bp for ETH SOL DOGE XRP HYPE and the odds markets (V13). A bet inside the gap 07:53:00-07:53:27 may carry either set |
| `leverage-tier` | leverage inside the stake tier (`cap`, `cap/2`, `cap/5`, `cap/10`) |
| `bet-vs-position`, `bet-after-start`, `no-bet-event` | the BetPlaced event agrees with the position and was placed before the round started |
| `entry-vs-open`, `path-vs-round`, `candle-shape`, `no-path` | entry price = round open; path first open / last close = round open / close; candles are well-formed; every round with bets has a path |
| `coinbase-price`, `coinbase-range` | for `spot_*` with venue coinbase: the round open (close) lies within the band of the Coinbase 1-minute candles around the boundary (`--tol-bp`, default 10); the engine path range sticks out of the Coinbase range by at most `--range-tol-bp` (default 25: the engine uses the bid/ask mid, Coinbase candles are trades, they differ most on DOGE-USD) |

Exit status 1 when a hard mismatch is found (each is listed with the round id and the player id); warnings do not change it.

The summary tables give the liquidation rate and the win rate by market x round length x leverage bucket (`cap` = the tier
maximum of the stake, `near` = 80-99 % of it, `low`), the win rate of people and bots, and the distance of the round prices from
Coinbase. "Win" is a payout above the stake; "net return" is payout over stake plus fees.

Rules are mirrored from `engine/.../economy/EconomyRules.java`, `margin/LiquidationCalculator.java`,
`round/PositionSimulator.java` and `game/GameEngine.java`; if they change, change this tool with them.
