# Herd bot API

Write a program that plays Herd. It uses the same calls as the web app, signed with a key. Bots play on every market, with any stake,
next to people, and have a leaderboard of their own. The same text is the page `/developers` of the app (decision Р-106,
`the decision log`).

Money in the game is called PLAY. A bot plays with PLAY it is given as a starter pack and with what it wins; it cannot withdraw PLAY to a wallet.

## 1. Get a key

Sign in to the app, open your profile, find the section "Your bots", type a name and press "Create bot key".

- Each bot is its own player with its own balance and the normal starter pack.
- One person can have up to 3 bots. Revoking a key does not free a place: a bot is never deleted.
- The key looks like `herd_` followed by 43 characters. It is shown once, in the box that opens when it is made. The server keeps only
  its SHA-256, so a lost key cannot be shown again: generate a new one ("Regenerate key"), which stops the old one at once.
- "Revoke key" stops a key and leaves the bot without one until you generate a new key.

## 2. Send the key with every request

Every call is an HTTP `POST /api/rpc/<method>` with a JSON body `{"args": [...]}` (positional arguments, omitted optional ones as
`null`) and the header `Authorization: Bearer herd_<your key>`:

```
POST /api/rpc/markets
Authorization: Bearer herd_<your key>
Content-Type: application/json

{"args": []}
```

A success is HTTP 200 with the answer as JSON. A failure is HTTP 4xx or 5xx with `{"error": {"code": "...", "message": "..."}}`.
A wrong, malformed or revoked key answers `401 API_KEY_INVALID`, also on a public call. With a key a request acts as that bot only;
a session cookie sent along is ignored.

## 3. The calls a bot may make

Only the calls below work with a key. Everything else - creating bots, keys, withdrawals and deposits, the nickname, sign-in and
sign-out, feedback, the "while you were away" results - is for a signed-in person and answers `403 API_CALL_NOT_ALLOWED`.
The answers shown are excerpts of what the server's own tests recorded (`web/src/api/wire/*.json`): a key left out is a field not
shown, the values are real. Amounts and prices are decimal strings; times are milliseconds since the epoch.

### `markets`

Every market with its price and, per series (HOURLY, DAILY), the round open for betting and when it closes.

Request body:

```json
{"args": []}
```

Response (excerpt):

```json
{
  "serverTimeMs": 1792310664231,
  "markets": [
    {
      "id": "BTC",
      "kind": "PERP",
      "symbol": "BTC",
      "maxLeverage": 40,
      "priceDecimals": 2,
      "status": "ACTIVE",
      "price": {
        "price": "84000.50",
        "asOfMs": 1792310663270,
        "stale": false
      },
      "series": [
        {
          "interval": "HOURLY",
          "durationSec": 60,
          "bettingRoundId": "1h-1792310280000",
          "bettingClosesAtMs": 1792310280000,
          "runningRoundId": "1h-1792310220000",
          "runningEndsAtMs": 1792310280000,
          "maxLeverage": 1200,
          "herdFeeBp": "0.92",
          "feeFactor": "1"
        }
      ]
    }
  ],
  "economy": {
    "networkFeePlay": "0.1",
    "herdFeeGuardPercent": "20"
  }
}
```

durationSec is 60 in the server's tests; read the real length of a round from it.

### `rounds`

The running round and the round open for betting of one series of one market.

Request body:

```json
{"args": ["HOURLY","T_WIRE_PERP"]}
```

Response (excerpt):

```json
{
  "roundId": "T_WIRE_PERP-1h-1792058880000",
  "window": {
    "interval": "HOURLY",
    "startMs": 1792058880000,
    "endMs": 1792058940000
  },
  "marketId": "T_WIRE_PERP",
  "phase": "BETTING",
  "myBet": null,
  "upStake": "150.000",
  "downStake": "400.000"
}
```

The excerpt is the betting half of the answer ({running, betting}).

### `round`

One round by id (betting, running, settled or cancelled).

Request body:

```json
{"args": ["T_WIRE_PERP-1h-1792058880000"]}
```

### `betFeed`

The public feed of the bets of one round, oldest first; pass the last seq to page on.

Request body:

```json
{"args": ["T_WIRE_PERP-1h-1792058880000",null]}
```

Response (excerpt):

```json
{
  "roundId": "T_WIRE_PERP-1h-1792058880000",
  "entries": [
    {
      "seq": 18,
      "atMs": 1792058879999,
      "playerDisplayName": "Vera_Wire (txch1v…d4wn)",
      "isBot": false,
      "direction": "DOWN",
      "stake": "400.000",
      "leverage": 3
    }
  ],
  "hasMore": false
}
```

### `placeBet`

Bets on the round open for betting. Placing again on the same round replaces the first bet.

Request body:

```json
{"args": [{"roundId":"1h-1792058880000","direction":"UP","leverage":5,"stake":"250.000","clientRequestId":"3f1c2b9e-6d54-4c0a-9a43-0d2f7a7e8b11"}]}
```

Response (excerpt):

```json
{
  "playerId": "4494220a-da20-40a8-9a2f-b95edf3714ab",
  "roundId": "1h-1792058880000",
  "direction": "UP",
  "leverage": 5,
  "stake": "250.000",
  "feeHerd": "0.115",
  "feeNetwork": "0.100",
  "maxLeverageForStake": 1200
}
```

Optional "mode" field: see Payout modes. Left out, the bet pays in the percentage mode.

Optional "clientRequestId" field: an id you make up for one bet (1-64 characters of `A-Z a-z 0-9 - _`; a UUID is the usual choice). If the answer is lost on the way back (a timeout, a dropped connection), send the SAME request again with the SAME id: the server recognises it, places no second bet and answers with the first answer. An id is remembered for 10 minutes, per key; the same id with a different bet (another round, side, stake, leverage or mode) is refused with `400 BAD_REQUEST`, and a malformed id too. A request that was refused is not remembered - fix it and send it again. Left out, nothing changes: placing again on the round replaces your bet, as above.

### `cancelBet`

Takes back your bet on a round that is still open for betting; stake and fees are refunded.

Request body:

```json
{"args": ["1h-1792058880000"]}
```

Response (excerpt):

```json
{}
```

### `previewLiquidation`

The liquidation price of a bet before you place it; with "interval" it also returns the fees and the highest leverage for the stake.

Request body:

```json
{"args": [{"marketId":"T_WIRE_PERP","direction":"UP","leverage":10,"stake":"250.000"}]}
```

Response (excerpt):

```json
{
  "entryPriceUsed": "2412.000",
  "liquidationPrice": "2215.102",
  "percentToLiquidation": 0.08163266998341626
}
```

### `myPositions`

Your bets that are waiting or running, every market.

Request body:

```json
{"args": []}
```

Response (excerpt):

```json
{
  "marketId": "BTC",
  "roundId": "1h-1792058820000",
  "interval": "HOURLY",
  "durationSec": 60,
  "phase": "RUNNING",
  "direction": "UP",
  "leverage": 5,
  "stake": "250.000",
  "entryPrice": "84000.50",
  "liquidationPrice": "67206.00",
  "liquidated": false,
  "untilMs": 1792058880000
}
```

The answer is an array; the excerpt is its first element.

### `myAccount`

Your balance, what is in play, and what could be withdrawn (nothing, for a bot).

Request body:

```json
{"args": []}
```

Response (excerpt):

```json
{
  "balance": "9156.245",
  "currentSeason": "2026-10",
  "inPlay": "750.000"
}
```

### `mySeasonRecord`

Your record in a season (the current one when the argument is null).

Request body:

```json
{"args": [null]}
```

Response (excerpt):

```json
{
  "season": "2026-10",
  "roundsPlayed": 2,
  "directionEligibleRounds": 2,
  "directionCorrectRounds": 1
}
```

### `myLedger`

Your ledger, newest first, paged with a limit.

Request body:

```json
{"args": [{"limit":50}]}
```

Response (excerpt):

```json
{
  "sequence": 85,
  "type": "FEE_NETWORK_REFUND",
  "delta": "0.100",
  "roundId": "T_WIRE_GAME-1h-1792058880000",
  "atMs": 1792059249966
}
```

The answer is {items, hasMore}; the excerpt is the first item.

### `myProfile`

Your profile: player, season record, round history and win streak.

Request body:

```json
{"args": []}
```

Response (excerpt):

```json
{
  "streak": {
    "current": 1,
    "best": 1
  }
}
```

Same shape as publicProfile; only the streak is shown here.

### `publicProfile`

Another player's profile (only that player's wins are shown).

Request body:

```json
{"args": ["<playerId>"]}
```

### `currentPlayer`

Who the key is: the bot as a player.

Request body:

```json
{"args": []}
```

Response (excerpt):

```json
{
  "nickname": "Alice_Wire",
  "isBot": false
}
```

For a bot, isBot is true.

### `ratings`

The people's boards (richest and prophets) of a season.

Request body:

```json
{"args": [null]}
```

Response (excerpt):

```json
{
  "season": "2026-10",
  "roundsThreshold": 10,
  "rich": [
    {
      "rank": 1,
      "growth": "-517.598",
      "roundsPlayed": 10
    }
  ]
}
```

### `botRatings`

The same two boards for API bots only.

Request body:

```json
{"args": [null]}
```

Response (excerpt):

```json
{
  "season": "2026-10",
  "roundsThreshold": 10
}
```

Same shape as ratings; the boards fill as bots play the minimum number of rounds.

### `priceSeries`

Recent price ticks of a market (at most 600).

Request body:

```json
{"args": ["T_WIRE_PERP",null]}
```

Response (excerpt):

```json
{
  "marketId": "T_WIRE_PERP",
  "priceDecimals": 3,
  "ticks": [
    {
      "atMs": 1792058768995,
      "price": "2400.000"
    }
  ]
}
```

### `candles`

Finished 1-minute candles of a market.

Request body:

```json
{"args": ["BTC",120]}
```

### `referencePrice`

The latest price of a market.

Request body:

```json
{"args": ["T_WIRE_PERP"]}
```

Response (excerpt):

```json
{
  "price": "2412.000",
  "asOfMs": 1792059249575
}
```

### `btcReferencePrice`

referencePrice of BTC.

Request body:

```json
{"args": []}
```

### `crowdOverview`

The crowd's paper account across markets.

Request body:

```json
{"args": []}
```

### `crowdAccount`

The crowd's paper account of one market.

Request body:

```json
{"args": ["BTC"]}
```

### `cancellations`

The public log of cancelled rounds.

Request body:

```json
{"args": [null]}
```

### `serverInfo`

Mode, round lengths, oracle status, whether the engine is ready.

Request body:

```json
{"args": []}
```

Response (excerpt):

```json
{
  "mode": "live",
  "roundDurationsSec": {
    "HOURLY": 60,
    "DAILY": 120
  },
  "engineReady": true
}
```

### `supply`

The PLAY supply and where it sits.

Request body:

```json
{"args": []}
```

## 4. Rounds and the betting window

Every market has two series of rounds, `HOURLY` and `DAILY` (the names do not give the length). On the stand they last 3 minutes and
15 minutes; read the real length of a round from `durationSec` in `markets` (or `roundDurationsSec` in `serverInfo`).

- The round open for betting in a series is `bettingRoundId` in `markets`; `bettingClosesAtMs` is when betting on it ends (the moment the
  round starts). A bet is accepted until then; after it, `ROUND_NOT_OPEN_FOR_BETTING`.
- A bet placed again on the same round replaces the first one (the first is refunded with its fees). `cancelBet` takes a bet back, with
  its fees, while betting is open.
- A round is then `RUNNING` until its end and `SETTLED` after it. The result shows in `myLedger`, `myProfile` and the round itself.
  A round that could not be priced is cancelled and every bet refunded.
- Round ids: BTC's look like `1h-1792058880000`, other markets' like `T_WIRE_PERP-1h-1792058880000`. Take them from the answers, do not build them.

## 5. Stakes, leverage, fees

- The smallest stake is 100 PLAY. Leverage starts at 2, is capped per market (`maxLeverage` of the series) and is lowered for large stakes
  (`economy.stakeTiers` in `markets`: the cap is divided by 1, 2, 5 or 10 for a stake of up to 250, 500, 1 200 or more PLAY).
- A bet costs the stake plus a herd fee and a flat network fee (`economy.networkFeePlay`). `placeBet` returns both (`feeHerd`,
  `feeNetwork`) and the highest leverage allowed for the stake (`maxLeverageForStake`); `previewLiquidation` with an `interval` in the
  request shows them before you bet. The page "All fees" in the app lists them for every market.
- The open stakes on one side of one round are limited by the treasury (`EXPOSURE_LIMIT_EXCEEDED`).

## 6. Limits

- Each key has its own request budget, separate from the per-address one: a burst of 60 calls, then 5 a second (300 a minute), every call
  counting one. Past it the server answers `429 RATE_LIMITED`. These are the server's defaults (`herd.ratelimit.bot.capacity` and
  `herd.ratelimit.bot.refill-per-sec`); the operator may change them.
- Wrong keys are counted against the sender's address with the same kind of bucket, so guessing keys gets throttled.
- At most 3 bots per person.
- A bot cannot withdraw PLAY to a wallet (`BOT_WITHDRAWAL_NOT_ALLOWED`; the call is not available to a key at all).
- A bot gets the starter pack of a season on its first call in that season, the same as a person's first sign-in of a month.

## 7. Errors

| Code | HTTP | Meaning |
| --- | --- | --- |
| `API_KEY_INVALID` | 401 | The key is unknown, malformed or revoked. |
| `API_CALL_NOT_ALLOWED` | 403 | A call keys may not make (creating bots, withdrawals, profile changes, ...). |
| `RATE_LIMITED` | 429 | The key's request budget is used up; wait and retry. |
| `BOT_WITHDRAWAL_NOT_ALLOWED` | 403 | An API bot's PLAY cannot be withdrawn to a wallet. |
| `ROUND_NOT_OPEN_FOR_BETTING` | 409 | The round has started, is not the open one, or was cancelled. |
| `STAKE_BELOW_MINIMUM` | 422 | The stake is under 100 PLAY. |
| `INSUFFICIENT_BALANCE` | 422 | Stake plus fees is more than the balance. |
| `INVALID_LEVERAGE` | 422 | Leverage is outside the range for this market and stake; the message names the highest allowed. |
| `EXPOSURE_LIMIT_EXCEEDED` | 422 | The open stakes on this side of this round already reach the treasury's limit. |
| `MARKET_NOT_FOUND` | 404 | No such market (or a round id of one). |
| `MARKET_CLOSED` | 409 | The market is closed and takes no bets. |
| `PRICE_UNAVAILABLE` | 503 | No price for the market right now; retry. |
| `ENGINE_NOT_READY` | 503 | The server is recovering; nothing was changed, retry shortly. |
| `BAD_REQUEST` | 400 | A malformed body or value (including an unknown "mode"). |

## 8. Payout modes

A bet pays in one of two modes (decision Р-106), chosen by the optional `"mode"` field of the `placeBet` request:

- **`"PERCENTAGE"`** (or no `mode` at all - every request written before the field existed): the payout follows the stake, the
  leverage and the size of the price move, as described above (liquidation price, fees by leverage).
- **`"FIXED"`** ("Win or lose"): no leverage (the `leverage` field is ignored) and no liquidation. At the close of the round, against
  its open: the right direction pays 2 x stake, the wrong one pays 0, an unchanged price gives the stake back. On top of the stake you
  pay a platform fee of 3 % of the stake (rounded up to 0.001 PLAY) and the 0.1 PLAY network fee; both are kept whatever the outcome.
  Not available on Polymarket odds markets (kind `ODDS`): `placeBet` answers `400 BAD_REQUEST`. The treasury limits of this mode:
  the fixed stakes on one side of a market in one round at most 0.1 % of the treasury, the crypto markets together at most 0.4 %;
  over that, `EXPOSURE_LIMIT_EXCEEDED` with the room left in PLAY. The fee rate is in the `economy` block of `markets`
  (`fixedPlatformFeePercent`). Rules: `docs/product/fixed_payout_rules.md`.

Any other value of `mode` is refused with `400 BAD_REQUEST`, never ignored, so a request never produces a bet in a mode you did not choose.

## 9. The leaderboard

API bots have their own boards - `botRatings(season)`, the "Bots" tab of the ratings page - next to the people's `ratings`, which leave
them out. Same season, same two rankings (richest, prophets) and the same minimum of 10 rounds in the season (decision Р-28).

## 10. A minimal bot (Python, standard library only)

It reads the markets and, once per round, places one 100 PLAY bet on BTC: UP if the last price is above the one before, else DOWN.
Put the key in `HERD_API_KEY` (and the server's address in `HERD_URL` if it is not the default).

```python
import json
import os
import time
import uuid
import urllib.error
import urllib.request

BASE = os.environ.get("HERD_URL", "https://playherd.app")
KEY = os.environ["HERD_API_KEY"]  # herd_..., from your profile
MARKET = "BTC"
STAKE = "100.000"
LEVERAGE = 2


class HerdError(Exception):
    pass


def call(method, *args):
    request = urllib.request.Request(
        f"{BASE}/api/rpc/{method}",
        data=json.dumps({"args": list(args)}).encode(),
        headers={"Content-Type": "application/json", "Authorization": f"Bearer {KEY}"},
    )
    try:
        with urllib.request.urlopen(request, timeout=15) as response:
            return json.load(response)
    except urllib.error.HTTPError as err:
        error = json.load(err).get("error", {})
        raise HerdError(f'{error.get("code")}: {error.get("message")}') from None


last_price = None
bet_rounds = set()
request_ids = {}  # one id per round: a retry of the same bet reuses it, so it is never placed twice
while True:
    try:
        answer = call("markets")
        market = next(m for m in answer["markets"] if m["id"] == MARKET)
        series = next(s for s in market["series"] if s["interval"] == "HOURLY")
        round_id = series["bettingRoundId"]
        price = float(market["price"]["price"]) if market["price"] else None
        time_left_ms = series["bettingClosesAtMs"] - answer["serverTimeMs"]
        if price is not None and last_price is not None and round_id not in bet_rounds and time_left_ms > 5000:
            direction = "UP" if price > last_price else "DOWN"
            request_id = request_ids.setdefault(round_id, str(uuid.uuid4()))
            bet = call("placeBet", {"roundId": round_id, "direction": direction, "leverage": LEVERAGE, "stake": STAKE,
                                    "clientRequestId": request_id})
            bet_rounds.add(round_id)
            print(f'{round_id}: {direction} {bet["stake"]} PLAY, fees {bet["feeHerd"]} + {bet["feeNetwork"]}')
        if price is not None:
            last_price = price
    except (HerdError, urllib.error.URLError, TimeoutError) as err:
        print("failed:", err)
    time.sleep(5)
```
