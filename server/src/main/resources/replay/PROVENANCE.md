# PROVENANCE — `btc_1m.csv` (replay price source, Р-46 test mode)

Fetched **2026-09-25, ~17:18 UTC**, by a one-off script (same request shape and CSV layout
as [`tools/fetch_hl_candles.py`](../../../../../tools/fetch_hl_candles.py), extended to the
`1m` interval — not a modification of that file, which is outside this module's assigned
paths).

## Endpoint and request

```
POST https://api.hyperliquid.xyz/info
Content-Type: application/json

{"type":"candleSnapshot","req":{"coin":"BTC","interval":"1m","startTime":0,"endTime":<now_ms>}}
```

Public, read-only, no auth, no state change — same endpoint documented in
`engine/src/test/resources/fixtures/PROVENANCE.md` and
`docs/research/hl_liquidation_math.md` §1.1/§8 ("Only the most recent 5000 candles are
available"). Requesting the full `[0, now]` range against a capped endpoint returns
exactly the most recent ≤5000 candles in one call — confirmed here: `startTime=0` still
returned only the tail, no pagination was needed.

## What was fetched

- Rows returned by the API: 5130; after excluding the still-open (incomplete) candle at
  fetch time (`close_time_ms < now_ms` filter, same rule as `fetch_hl_candles.py`): **5129
  closed 1-minute candles**.
- First candle: `open_time_ms=1790048940000` → **2026-09-22T03:49:00Z**.
- Last candle: `open_time_ms=1790356620000` → **2026-09-25T17:17:00Z**.
- Fetched at: **2026-09-25T17:18:22Z** (wall clock at request time).
- Span: ~3 days 13h 28m of real BTC 1-minute price history.
- Gap check: every consecutive pair of rows steps by exactly 60 000 ms — **0 gaps** in the
  5129-row series (verified by direct diff over `open_time_ms`, this session).
- SHA-256 of `btc_1m.csv`: `d3872e590dab39e6ba82c607bdd85329a2cad18f625f27ffbd77ccbcb53bb009`

## CSV format

Identical to `engine/src/test/resources/fixtures/*.csv`:
`open_time_ms,close_time_ms,open,high,low,close,volume,trades` — decimal price/volume
strings written exactly as the API returned them (no float round-trip), ascending by
`open_time_ms`, de-duplicated.

## How the server uses this file (предложение — see `docs/research/server_design.md`)

`mode=replay` (default for the local stand, Р-46) walks this 5129-candle series at
**20× speed** (one source minute of real BTC history per 3 wall-clock seconds — chosen so
that a Р-46 test-mode HOURLY round, 180 s wall clock, replays exactly 60 source minutes,
i.e. one real historical hour), looping back to the first row once the series is
exhausted. The mapping is by **array index**, not by absolute timestamp, so the 0-gap
property above is a nice-to-have, not a correctness requirement — see
`ReplayRoundPrices` and `docs/research/server_design.md` §"Replay mode" for the exact
index arithmetic and the documented loop-boundary caveat (a synthetic price discontinuity
where the series wraps).

Re-running the fetch script produces a different (newer) window of history; this
particular file is a point-in-time snapshot checked into the repo so the local Docker
stand's replay is reproducible without a live network call at container build/start time.
