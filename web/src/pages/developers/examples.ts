/**
 * The content of the developers' page (Р-106): the calls an API key may make, one example each, the error codes, and a minimal bot.
 * The same material is `docs/api/bots.md`. Two tests keep it honest (`examples.test.ts`): the list of calls is the server's own
 * (`RpcResource.API_KEY_METHODS`), and every response here is an EXCERPT of an answer the server's tests really recorded
 * (`api/wire/<fixture>.json`, optionally at a path inside it) - the same keys with values of the same kind, nothing written from
 * the contract (the values themselves are those of one recording; ids, times and amounts differ from one recording to the next).
 */

export interface CallExample {
  /** The RPC method: `POST /api/rpc/<method>`. */
  method: string;
  /** One line: what the call does. English on purpose - this is a technical reference. */
  what: string;
  /** The positional `args` of the request body `{"args": [...]}`. */
  args: unknown[];
  /** An excerpt of the answer (a key left out is a key not shown); absent for calls with no example here. */
  response?: unknown;
  /** The wire fixture (`api/wire/<fixture>.json`) the excerpt is taken from, and where inside its `response`. */
  fixture?: string;
  at?: string;
  /** A remark that belongs to the row. */
  note?: string;
}

export const CALL_EXAMPLES: CallExample[] = [
  {
    method: "markets",
    what: "Every market with its price and, per series (HOURLY, DAILY), the round open for betting and when it closes.",
    args: [],
    fixture: "markets",
    response: {
      serverTimeMs: 1792310664231,
      markets: [
        {
          id: "BTC",
          kind: "PERP",
          symbol: "BTC",
          maxLeverage: 40,
          priceDecimals: 2,
          status: "ACTIVE",
          price: { price: "84000.50", asOfMs: 1792310663270, stale: false },
          series: [
            {
              interval: "HOURLY",
              durationSec: 60,
              bettingRoundId: "1h-1792310280000",
              bettingClosesAtMs: 1792310280000,
              runningRoundId: "1h-1792310220000",
              runningEndsAtMs: 1792310280000,
              maxLeverage: 1200,
              herdFeeBp: "0.92",
              feeFactor: "1",
            },
          ],
        },
      ],
      economy: { networkFeePlay: "0.1", herdFeeGuardPercent: "20" },
    },
    note: "durationSec is 60 in the server's tests; read the real length of a round from it.",
  },
  {
    method: "rounds",
    what: "The running round and the round open for betting of one series of one market.",
    args: ["HOURLY", "T_WIRE_PERP"],
    fixture: "rounds.hourly",
    at: "betting",
    response: {
      roundId: "T_WIRE_PERP-1h-1792058880000",
      window: { interval: "HOURLY", startMs: 1792058880000, endMs: 1792058940000 },
      marketId: "T_WIRE_PERP",
      phase: "BETTING",
      myBet: null,
      upStake: "150.000",
      downStake: "400.000",
    },
    note: "The excerpt is the betting half of the answer ({running, betting}).",
  },
  { method: "round", what: "One round by id (betting, running, settled or cancelled).", args: ["T_WIRE_PERP-1h-1792058880000"] },
  {
    method: "betFeed",
    what: "The public feed of the bets of one round, oldest first; pass the last seq to page on.",
    args: ["T_WIRE_PERP-1h-1792058880000", null],
    fixture: "betFeed",
    response: {
      roundId: "T_WIRE_PERP-1h-1792058880000",
      entries: [{ seq: 18, atMs: 1792058879999, playerDisplayName: "Vera_Wire (txch1v…d4wn)", isBot: false, direction: "DOWN", stake: "400.000", leverage: 3 }],
      hasMore: false,
    },
  },
  {
    method: "placeBet",
    what: "Bets on the round open for betting. Placing again on the same round replaces the first bet.",
    args: [{ roundId: "1h-1792058880000", direction: "UP", leverage: 5, stake: "250.000", clientRequestId: "3f1c2b9e-6d54-4c0a-9a43-0d2f7a7e8b11" }],
    fixture: "home",
    at: "hourly.betting.myBet",
    response: {
      playerId: "4494220a-da20-40a8-9a2f-b95edf3714ab",
      roundId: "1h-1792058880000",
      direction: "UP",
      leverage: 5,
      stake: "250.000",
      feeHerd: "0.115",
      feeNetwork: "0.100",
      maxLeverageForStake: 1200,
    },
    note: "Optional \"mode\" field: see Payout modes. Left out, the bet pays in the percentage mode. Optional \"clientRequestId\" field: an id you make up for one bet (1-64 characters of `A-Z a-z 0-9 - _`; a UUID is the usual choice). If the answer is lost on the way back (a timeout, a dropped connection), send the SAME request again with the SAME id: the server recognises it, places no second bet and answers with the first answer. An id is remembered for 10 minutes, per key; the same id with a different bet (another round, side, stake, leverage or mode) is refused with `400 BAD_REQUEST`, and a malformed id too. A request that was refused is not remembered - fix it and send it again. Left out, nothing changes: placing again on the round replaces your bet, as above.",
  },
  { method: "cancelBet", what: "Takes back your bet on a round that is still open for betting; stake and fees are refunded.", args: ["1h-1792058880000"], response: {} },
  {
    method: "previewLiquidation",
    what: "The liquidation price of a bet before you place it; with \"interval\" it also returns the fees and the highest leverage for the stake.",
    args: [{ marketId: "T_WIRE_PERP", direction: "UP", leverage: 10, stake: "250.000" }],
    fixture: "previewLiquidation",
    response: { entryPriceUsed: "2412.000", liquidationPrice: "2215.102", percentToLiquidation: 0.08163266998341626 },
  },
  {
    method: "myPositions",
    what: "Your bets that are waiting or running, every market.",
    args: [],
    fixture: "myPositions",
    at: "0",
    response: {
      marketId: "BTC",
      roundId: "1h-1792058820000",
      interval: "HOURLY",
      durationSec: 60,
      phase: "RUNNING",
      direction: "UP",
      leverage: 5,
      stake: "250.000",
      entryPrice: "84000.50",
      liquidationPrice: "67206.00",
      liquidated: false,
      untilMs: 1792058880000,
    },
    note: "The answer is an array; the excerpt is its first element.",
  },
  {
    method: "myAccount",
    what: "Your balance, what is in play, and what could be withdrawn (nothing, for a bot).",
    args: [],
    fixture: "myAccount",
    response: { balance: "9156.245", currentSeason: "2026-10", inPlay: "750.000" },
  },
  {
    method: "mySeasonRecord",
    what: "Your record in a season (the current one when the argument is null).",
    args: [null],
    fixture: "mySeasonRecord",
    response: { season: "2026-10", roundsPlayed: 2, directionEligibleRounds: 2, directionCorrectRounds: 1 },
  },
  {
    method: "myLedger",
    what: "Your ledger, newest first, paged with a limit.",
    args: [{ limit: 50 }],
    fixture: "myLedger",
    at: "items.0",
    response: { sequence: 85, type: "FEE_NETWORK_REFUND", delta: "0.100", roundId: "T_WIRE_GAME-1h-1792058880000", atMs: 1792059249966 },
    note: "The answer is {items, hasMore}; the excerpt is the first item.",
  },
  {
    method: "myProfile",
    what: "Your profile: player, season record, round history and win streak.",
    args: [],
    fixture: "publicProfile",
    response: { streak: { current: 1, best: 1 } },
    note: "Same shape as publicProfile; only the streak is shown here.",
  },
  { method: "publicProfile", what: "Another player's profile (only that player's wins are shown).", args: ["<playerId>"] },
  { method: "currentPlayer", what: "Who the key is: the bot as a player.", args: [], fixture: "currentPlayer", response: { nickname: "Alice_Wire", isBot: false }, note: "For a bot, isBot is true." },
  {
    method: "ratings",
    what: "The people's boards (richest and prophets) of a season.",
    args: [null],
    fixture: "ratings",
    response: { season: "2026-10", roundsThreshold: 10, rich: [{ rank: 1, growth: "-517.598", roundsPlayed: 10 }] },
  },
  {
    method: "botRatings",
    what: "The same two boards for API bots only.",
    args: [null],
    fixture: "ratings",
    response: { season: "2026-10", roundsThreshold: 10 },
    note: "Same shape as ratings; the boards fill as bots play the minimum number of rounds.",
  },
  {
    method: "priceSeries",
    what: "Recent price ticks of a market (at most 600).",
    args: ["T_WIRE_PERP", null],
    fixture: "priceSeries",
    response: { marketId: "T_WIRE_PERP", priceDecimals: 3, ticks: [{ atMs: 1792058768995, price: "2400.000" }] },
  },
  { method: "candles", what: "Finished 1-minute candles of a market.", args: ["BTC", 120] },
  { method: "referencePrice", what: "The latest price of a market.", args: ["T_WIRE_PERP"], fixture: "referencePrice", response: { price: "2412.000", asOfMs: 1792059249575 } },
  { method: "btcReferencePrice", what: "referencePrice of BTC.", args: [] },
  { method: "crowdOverview", what: "The crowd's paper account across markets.", args: [] },
  { method: "crowdAccount", what: "The crowd's paper account of one market.", args: ["BTC"] },
  { method: "cancellations", what: "The public log of cancelled rounds.", args: [null] },
  { method: "serverInfo", what: "Mode, round lengths, oracle status, whether the engine is ready.", args: [], fixture: "serverInfo", response: { mode: "live", roundDurationsSec: { HOURLY: 60, DAILY: 120 }, engineReady: true } },
  { method: "supply", what: "The PLAY supply and where it sits.", args: [] },
];

export interface ErrorRow {
  code: string;
  http: number;
  meaning: string;
}

export const ERROR_ROWS: ErrorRow[] = [
  { code: "API_KEY_INVALID", http: 401, meaning: "The key is unknown, malformed or revoked." },
  { code: "API_CALL_NOT_ALLOWED", http: 403, meaning: "A call keys may not make (creating bots, withdrawals, profile changes, ...)." },
  { code: "RATE_LIMITED", http: 429, meaning: "The key's request budget is used up; wait and retry." },
  { code: "BOT_WITHDRAWAL_NOT_ALLOWED", http: 403, meaning: "An API bot's PLAY cannot be withdrawn to a wallet." },
  { code: "ROUND_NOT_OPEN_FOR_BETTING", http: 409, meaning: "The round has started, is not the open one, or was cancelled." },
  { code: "STAKE_BELOW_MINIMUM", http: 422, meaning: "The stake is under 100 PLAY." },
  { code: "INSUFFICIENT_BALANCE", http: 422, meaning: "Stake plus fees is more than the balance." },
  { code: "INVALID_LEVERAGE", http: 422, meaning: "Leverage is outside the range for this market and stake; the message names the highest allowed." },
  { code: "EXPOSURE_LIMIT_EXCEEDED", http: 422, meaning: "The open stakes on this side of this round already reach the treasury's limit." },
  { code: "MARKET_NOT_FOUND", http: 404, meaning: "No such market (or a round id of one)." },
  { code: "MARKET_CLOSED", http: 409, meaning: "The market is closed and takes no bets." },
  { code: "PRICE_UNAVAILABLE", http: 503, meaning: "No price for the market right now; retry." },
  { code: "ENGINE_NOT_READY", http: 503, meaning: "The server is recovering; nothing was changed, retry shortly." },
  { code: "BAD_REQUEST", http: 400, meaning: "A malformed body or value (including an unknown \"mode\")." },
];

/** A minimal bot: standard library only, one bet per round on BTC. */
export const PYTHON_BOT = `import json
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
`;
