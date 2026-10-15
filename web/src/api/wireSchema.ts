/**
 * Test-only: `contract.ts` as RUNTIME checks, so a JSON answer of the real server can be compared with it field by
 * field (finding 14 of the parity review: nothing did, and `roundDurationsSec` came lowercase for as long as nobody
 * looked). One checker per DTO. `Fields<T>` makes the key list EXHAUSTIVE at compile time — add a field to an interface
 * in `contract.ts` and the checker of it stops compiling until it is listed here; a removed or renamed field is a
 * compile error too. Optional fields are marked `opt(...)`: absent, or `null` (the server spells "absent" as `null`),
 * or a valid value. An extra key the contract does not list is allowed (additive changes are the server's to make) —
 * what is NOT allowed is a missing required key, a wrong type, a number that is not finite, a string that is not the
 * decimal it should be, a value outside an enum.
 *
 * Nothing in the app imports this file.
 */
import type {
  AuthSessionDto,
  BetDto,
  BetFeedDto,
  BetFeedEntryDto,
  CancellationDto,
  CategoryDto,
  CrowdAccountDto,
  CrowdHistoryPointDto,
  CrowdOverviewDto,
  CrowdRoundPositionDto,
  CrowdRoundResultDto,
  HomeSummaryDto,
  LedgerEntryDto,
  LiquidationPreviewDto,
  LiveBetDto,
  MarketCrowdBriefDto,
  MarketDto,
  MarketPriceDto,
  MarketReferenceDto,
  MarketSeriesDto,
  MarketsDto,
  MyPositionDto,
  OracleAnchorDto,
  Page,
  PlayerAccountDto,
  PlayerSummaryDto,
  PriceSeriesDto,
  PriceTickDto,
  ProfileRoundEntryDto,
  ProphetEntryDto,
  PublicPositionDto,
  PublicProfileDto,
  RatingsDto,
  RichEntryDto,
  RoundBetting,
  RoundCancelled,
  RoundRunning,
  RoundSettled,
  RoundWindowDto,
  RoundsPairDto,
  SeasonRecordDto,
  SeasonReturnDto,
  ServerInfoDto,
} from "./contract";

/** The list of what is wrong, each entry saying where (`$.markets[3].price.price`) and what. */
export type Problems = string[];
export type Check = (value: unknown, path: string, problems: Problems) => void;

const OPTIONAL = Symbol("optional");
type MaybeOptional = Check & { [OPTIONAL]?: true };

const describe = (v: unknown): string => (v === null ? "null" : Array.isArray(v) ? "an array" : typeof v === "object" ? "an object" : `${typeof v} ${JSON.stringify(v)}`);

export const str: Check = (v, path, problems) => {
  if (typeof v !== "string") problems.push(`${path}: expected a string, got ${describe(v)}`);
};
export const nonEmptyStr: Check = (v, path, problems) => {
  if (typeof v !== "string" || v.trim() === "") problems.push(`${path}: expected a non-empty string, got ${describe(v)}`);
};
export const num: Check = (v, path, problems) => {
  if (typeof v !== "number" || !Number.isFinite(v)) problems.push(`${path}: expected a finite number, got ${describe(v)}`);
};
export const int: Check = (v, path, problems) => {
  if (typeof v !== "number" || !Number.isInteger(v)) problems.push(`${path}: expected an integer, got ${describe(v)}`);
};
export const bool: Check = (v, path, problems) => {
  if (typeof v !== "boolean") problems.push(`${path}: expected a boolean, got ${describe(v)}`);
};
export const lit =
  (...values: string[]): Check =>
  (v, path, problems) => {
    if (typeof v !== "string" || !values.includes(v)) problems.push(`${path}: expected one of ${values.join(" | ")}, got ${describe(v)}`);
  };
/** A decimal string; `digits` = exactly that many fractional digits (or a [min, max] range); none given = any. */
export const decimal =
  (digits?: number | [number, number]): Check =>
  (v, path, problems) => {
    if (typeof v !== "string" || !/^-?\d+(\.\d+)?$/.test(v)) {
      problems.push(`${path}: expected a decimal string, got ${describe(v)}`);
      return;
    }
    if (digits === undefined) return;
    const [min, max] = typeof digits === "number" ? [digits, digits] : digits;
    const fraction = v.includes(".") ? v.length - v.indexOf(".") - 1 : 0;
    if (fraction < min || fraction > max) problems.push(`${path}: expected ${min === max ? min : `${min}–${max}`} fractional digits, got "${v}"`);
  };
/** `PlayAmountStr`: scale 3. `UsdStr`: scale 2. A market price has its market's own scale (checked per market, `priceScaleProblems`). */
export const play: Check = decimal(3);
export const usd: Check = decimal(2);
export const price: Check = decimal();

export const nullable =
  (c: Check): Check =>
  (v, path, problems) => {
    if (v !== null) c(v, path, problems);
  };

/** An optional field: absent, `null` (the server's way of saying absent) or a valid value. */
export const opt = (c: Check): Check => {
  const wrapped: MaybeOptional = (v, path, problems) => {
    if (v !== undefined && v !== null) c(v, path, problems);
  };
  wrapped[OPTIONAL] = true;
  return wrapped;
};

export const arr =
  (c: Check): Check =>
  (v, path, problems) => {
    if (!Array.isArray(v)) {
      problems.push(`${path}: expected an array, got ${describe(v)}`);
      return;
    }
    v.forEach((item, i) => c(item, `${path}[${i}]`, problems));
  };

export type Fields<T> = { [K in keyof Required<T>]: Check };

export function obj<T>(fields: Fields<T>): Check {
  return (v, path, problems) => {
    if (typeof v !== "object" || v === null || Array.isArray(v)) {
      problems.push(`${path}: expected an object, got ${describe(v)}`);
      return;
    }
    const record = v as Record<string, unknown>;
    for (const [key, check] of Object.entries(fields) as Array<[string, MaybeOptional]>) {
      if (!(key in record)) {
        if (!check[OPTIONAL]) problems.push(`${path}.${key}: required field is missing`);
        continue;
      }
      check(record[key], `${path}.${key}`, problems);
    }
  };
}

/** A union discriminated by a field. */
export const variant =
  (key: string, cases: Record<string, Check>): Check =>
  (v, path, problems) => {
    const tag = (v as Record<string, unknown> | null)?.[key];
    const check = typeof tag === "string" ? cases[tag] : undefined;
    if (!check) problems.push(`${path}.${key}: expected one of ${Object.keys(cases).join(" | ")}, got ${describe(tag)}`);
    else check(v, path, problems);
  };

// ---------------------------------------------------------------------------
// The contract, DTO by DTO
// ---------------------------------------------------------------------------

const direction = lit("UP", "DOWN");
const interval = lit("HOURLY", "DAILY");
const kind = lit("PERP", "ODDS");
const source = lit("hyperliquid", "coinbase", "kraken", "polymarket", "synthetic");

export const playerSummary = obj<PlayerSummaryDto>({ playerId: nonEmptyStr, nickname: str, walletAddress: nonEmptyStr, displayName: nonEmptyStr, isBot: opt(bool) });
/** `starterPackGranted` is optional in the contract (the mock and an older server leave it out), but a server of this repo sends it: `wireContract.test.ts` says so. */
export const authSession = obj<AuthSessionDto>({ player: playerSummary, isNewPlayer: bool, starterPackGranted: opt(bool) });

const bet = obj<BetDto>({ playerId: nonEmptyStr, roundId: nonEmptyStr, direction, leverage: int, stake: play, feeHerd: opt(play), feeNetwork: opt(play), maxLeverageForStake: opt(int) });

const window_ = obj<RoundWindowDto>({ interval, startMs: int, endMs: int });

const publicPosition = obj<PublicPositionDto>({
  playerId: nonEmptyStr,
  roundId: nonEmptyStr,
  direction,
  leverage: int,
  size: decimal(8),
  entryPrice: price,
  liquidationPrice: price,
  // USD while the round runs (2 dp), PLAY once settled (3 dp): the one field with two scales
  pnl: nullable(decimal([2, 3])),
  stake: opt(play),
  player: playerSummary,
  liquidated: bool,
  payout: nullable(play),
  directionCorrect: nullable(bool),
});

const crowdRunning = obj<CrowdRoundPositionDto>({ roundId: nonEmptyStr, direction, leverage: int, margin: usd, entryPrice: price, liquidationPrice: price });
const crowdResult = obj<CrowdRoundResultDto>({
  roundId: nonEmptyStr,
  traded: bool,
  direction: nullable(direction),
  leverage: nullable(int),
  margin: nullable(usd),
  entryPrice: nullable(price),
  liquidated: bool,
  capitalBefore: usd,
  capitalAfter: usd,
});

export const cancellation = obj<CancellationDto>({ roundId: nonEmptyStr, marketId: opt(nonEmptyStr), asset: opt(nonEmptyStr), reason: nonEmptyStr, cancelledAtMs: int, refundedBetCount: int });
const oracleAnchor = obj<OracleAnchorDto>({ seq: int, state: nonEmptyStr, height: nullable(int) });
const liveBet = obj<LiveBetDto>({ player: playerSummary, direction, leverage: int, stake: play, atMs: int });

/** What every round carries (`RoundBase`). */
const roundBase = {
  roundId: nonEmptyStr,
  window: window_,
  marketId: nonEmptyStr,
  asset: nonEmptyStr,
  kind,
  priceDecimals: int,
  source,
} as const;

const roundBetting = obj<RoundBetting>({
  ...roundBase,
  phase: lit("BETTING"),
  participantCount: int,
  myBet: nullable(bet),
  upStake: opt(play),
  downStake: opt(play),
  upCount: opt(int),
  downCount: opt(int),
  majorityDirection: opt(nullable(direction)),
  majorityLeverage: opt(nullable(int)),
  recentBets: opt(arr(liveBet)),
});
const roundRunning = obj<RoundRunning>({ ...roundBase, phase: lit("RUNNING"), openPrice: nullable(price), positions: arr(publicPosition), crowd: nullable(crowdRunning) });
const roundSettled = obj<RoundSettled>({
  ...roundBase,
  phase: lit("SETTLED"),
  openPrice: price,
  closePrice: price,
  positions: arr(publicPosition),
  crowd: nullable(crowdResult),
  resultsHash: opt(str),
  oracleAnchor: opt(oracleAnchor),
});
const roundCancelled = obj<RoundCancelled>({ ...roundBase, phase: lit("CANCELLED"), cancellation, resultsHash: opt(str) });

export const round = variant("phase", { BETTING: roundBetting, RUNNING: roundRunning, SETTLED: roundSettled, CANCELLED: roundCancelled });
export const roundsPair = obj<RoundsPairDto>({ interval, running: round, betting: variant("phase", { BETTING: roundBetting, CANCELLED: roundCancelled }) });

export const betFeed = obj<BetFeedDto>({
  roundId: nonEmptyStr,
  marketId: nonEmptyStr,
  asset: nonEmptyStr,
  entries: arr(obj<BetFeedEntryDto>({ seq: int, atMs: int, playerDisplayName: nonEmptyStr, isBot: bool, direction, stake: play, leverage: int })),
  hasMore: bool,
});

export const myPosition = obj<MyPositionDto>({
  marketId: nonEmptyStr,
  symbol: nonEmptyStr,
  roundId: nonEmptyStr,
  interval,
  durationSec: int,
  phase: lit("BETTING", "RUNNING"),
  direction,
  leverage: int,
  stake: play,
  entryPrice: nullable(price),
  liquidationPrice: nullable(price),
  size: opt(decimal(8)),
  liquidated: bool,
  untilMs: int,
});

export const playerAccount = obj<PlayerAccountDto>({ playerId: nonEmptyStr, balance: play, currentSeason: nonEmptyStr, inPlay: opt(play) });

const ledgerEntry = obj<LedgerEntryDto>({
  sequence: int,
  type: lit("STARTER_PACK", "STAKE_DEBIT", "STAKE_REFUND", "SETTLEMENT", "FEE_HERD", "FEE_NETWORK", "FEE_HERD_REFUND", "FEE_NETWORK_REFUND", "DEPOSIT", "WITHDRAWAL", "WITHDRAWAL_REFUND", "OPERATOR_CORRECTION"),
  delta: play,
  roundId: nullable(nonEmptyStr),
  atMs: int,
});
const page = <T>(item: Check) => obj<Page<T>>({ items: arr(item), hasMore: bool });
export const ledgerPage = page<LedgerEntryDto>(ledgerEntry);
export const cancellationsPage = page<CancellationDto>(cancellation);

export const seasonRecord = obj<SeasonRecordDto>({
  season: nonEmptyStr,
  baselineBalance: play,
  deposited: play,
  withdrawn: play,
  roundsPlayed: int,
  directionEligibleRounds: int,
  directionCorrectRounds: int,
});

const historyPoint = obj<CrowdHistoryPointDto>({ roundId: nonEmptyStr, atMs: int, seasonBoundary: nullable(str), crowdCapital: usd, holdCapital: usd, antiCrowdCapital: usd });
export const crowdAccount = obj<CrowdAccountDto>({
  marketId: nonEmptyStr,
  symbol: nonEmptyStr,
  capital: usd,
  history: arr(historyPoint),
  seasonReturns: arr(obj<SeasonReturnDto>({ season: nonEmptyStr, returnPct: num })),
});

const overviewRow = obj<CrowdOverviewDto["markets"][number]>({
  marketId: nonEmptyStr,
  symbol: nonEmptyStr,
  title: nonEmptyStr,
  status: lit("ACTIVE", "CLOSED"),
  category: lit("crypto", "politics", "sports", "stocks", "indices", "commodities"),
  capital: usd,
  returnPct: num,
  roundsTraded: int,
  roundsWon: int,
});
export const crowdOverview = obj<CrowdOverviewDto>({ totalCapital: usd, totalStartingCapital: usd, returnPct: num, markets: arr(overviewRow) });

export const ratings = obj<RatingsDto>({
  season: nonEmptyStr,
  roundsThreshold: int,
  rich: arr(obj<RichEntryDto>({ rank: int, player: playerSummary, growth: play, roundsPlayed: int })),
  prophets: arr(obj<ProphetEntryDto>({ rank: int, player: playerSummary, accuracy: num, eligibleRounds: int, roundsPlayed: int })),
});

export const profileRoundEntry = obj<ProfileRoundEntryDto>({
  roundId: nonEmptyStr,
  interval,
  marketId: nonEmptyStr,
  asset: nonEmptyStr,
  durationSec: opt(int),
  kind: opt(kind),
  settledAtMs: int,
  direction,
  leverage: int,
  stake: play,
  liquidated: bool,
  directionCorrect: nullable(bool),
  payout: play,
});
export const publicProfile = obj<PublicProfileDto>({
  player: playerSummary,
  seasonRecord: nullable(seasonRecord),
  history: arr(profileRoundEntry),
  streak: opt(obj<{ current: number; best: number }>({ current: int, best: int })),
});

export const priceSeries = obj<PriceSeriesDto>({ marketId: nonEmptyStr, priceDecimals: int, ticks: arr(obj<PriceTickDto>({ atMs: int, price })) });
export const marketReference = obj<MarketReferenceDto>({ price, asOfMs: int });
export const liquidationPreview = obj<LiquidationPreviewDto>({ entryPriceUsed: price, liquidationPrice: price, percentToLiquidation: num, feeHerd: opt(play), feeNetwork: opt(play), totalDebit: opt(play), maxLeverageForStake: opt(int) });

export const home = obj<HomeSummaryDto>({
  hourly: roundsPair,
  daily: roundsPair,
  crowd: crowdAccount,
  topRich: arr(obj<RichEntryDto>({ rank: int, player: playerSummary, growth: play, roundsPlayed: int })),
  topProphets: arr(obj<ProphetEntryDto>({ rank: int, player: playerSummary, accuracy: num, eligibleRounds: int, roundsPlayed: int })),
});

const marketSeries = obj<MarketSeriesDto>({
  interval,
  durationSec: int,
  bettingRoundId: nonEmptyStr,
  bettingClosesAtMs: int,
  runningRoundId: nonEmptyStr,
  runningEndsAtMs: int,
  participantCount: int,
  upStake: play,
  downStake: play,
  majorityDirection: nullable(direction),
  maxLeverage: opt(int),
  herdFeeBp: opt(decimal()),
});
const marketCrowd = obj<MarketCrowdBriefDto>({
  capital: usd,
  returnPct: num,
  roundsTraded: int,
  roundsWon: int,
  lastOutcome: nullable(lit("WIN", "LOSS", "LIQUIDATED", "FLAT")),
});
const market = obj<MarketDto>({
  id: nonEmptyStr,
  kind,
  category: lit("crypto", "politics", "sports", "stocks", "indices", "commodities"),
  symbol: nonEmptyStr,
  title: nonEmptyStr,
  subtitle: nullable(str),
  iconUrl: nullable(str),
  maxLeverage: int,
  priceDecimals: int,
  status: lit("ACTIVE", "CLOSED"),
  endsAtMs: nullable(int),
  eventStartsAtMs: opt(int),
  closedAtMs: opt(int),
  closeReason: opt(str),
  oracleIndex: opt(int),
  source,
  backupSource: opt(lit("coinbase", "kraken")),
  price: nullable(
    obj<MarketPriceDto>({ price, asOfMs: int, change24h: nullable(num), stale: bool, venue: opt(lit("hyperliquid", "coinbase", "kraken")) }),
  ),
  series: arr(marketSeries),
  crowd: marketCrowd,
});
export const markets = obj<MarketsDto>({
  serverTimeMs: int,
  categories: arr(obj<CategoryDto>({ id: lit("crypto", "politics", "sports", "stocks", "indices", "commodities"), title: nonEmptyStr, marketCount: int })),
  markets: arr(market),
});

export const serverInfo = obj<ServerInfoDto>({
  mode: lit("live", "replay"),
  priceSource: opt(lit("live", "synthetic", "replay")),
  markets: opt(int),
  replay: opt(obj<NonNullable<ServerInfoDto["replay"]>>({ sourceFromIso: str, sourceToIso: str, speedup: num })),
  roundDurationsSec: obj<ServerInfoDto["roundDurationsSec"]>({ HOURLY: int, DAILY: int }),
  bots: int,
  oracle: obj<ServerInfoDto["oracle"]>({
    network: lit("testnet11"),
    launcherId: nonEmptyStr,
    lastConfirmedSeq: nullable(int),
    lastConfirmedHeight: nullable(int),
    pendingRecords: int,
    operatorBalanceMojos: nullable(decimal(0)),
    firstSeq: opt(nullable(int)),
  }),
  serverTimeMs: int,
  playAssetId: opt(nonEmptyStr),
  testWallet: opt(bool),
  engineReady: opt(bool),
  walletConnectProjectId: opt(str),
});

export const errorEnvelope = obj<{ error: { code: string; message: string } }>({ error: obj<{ code: string; message: string }>({ code: nonEmptyStr, message: str }) });

/** Runs a check; returns the problems (empty = it matches the contract). */
export function problemsOf(check: Check, value: unknown, root = "$"): Problems {
  const problems: Problems = [];
  check(value, root, problems);
  return problems;
}

// ---------------------------------------------------------------------------
// Price scales: every price of a market has exactly that market's `priceDecimals` fractional digits
// ---------------------------------------------------------------------------

const fractionDigits = (v: string): number => (v.includes(".") ? v.length - v.indexOf(".") - 1 : 0);

function expectScale(value: unknown, decimals: number, path: string, problems: Problems): void {
  if (typeof value !== "string") return; // a null price (no price yet) is the schema's business
  if (fractionDigits(value) !== decimals) problems.push(`${path}: "${value}" has ${fractionDigits(value)} fractional digits, the market's price scale is ${decimals}`);
}

/** The prices of one round, in the round's own scale (and, when given, the market's — the two must agree). */
export function roundPriceScaleProblems(round: Record<string, unknown>, path: string, marketDecimals?: number): Problems {
  const problems: Problems = [];
  const decimals = round.priceDecimals;
  if (typeof decimals !== "number") return [`${path}.priceDecimals: missing, so the prices cannot be read`];
  if (marketDecimals !== undefined && marketDecimals !== decimals) problems.push(`${path}.priceDecimals: ${decimals} but market ${String(round.marketId)} says ${marketDecimals}`);
  expectScale(round.openPrice, decimals, `${path}.openPrice`, problems);
  expectScale(round.closePrice, decimals, `${path}.closePrice`, problems);
  for (const [i, p] of ((round.positions as Array<Record<string, unknown>> | undefined) ?? []).entries()) {
    expectScale(p.entryPrice, decimals, `${path}.positions[${i}].entryPrice`, problems);
    expectScale(p.liquidationPrice, decimals, `${path}.positions[${i}].liquidationPrice`, problems);
  }
  const crowd = round.crowd as Record<string, unknown> | null | undefined;
  if (crowd) {
    expectScale(crowd.entryPrice, decimals, `${path}.crowd.entryPrice`, problems);
    expectScale(crowd.liquidationPrice, decimals, `${path}.crowd.liquidationPrice`, problems);
  }
  return problems;
}

export function marketPriceScaleProblems(m: { id: string; priceDecimals: number; price: { price: string } | null }, path: string): Problems {
  const problems: Problems = [];
  expectScale(m.price?.price, m.priceDecimals, `${path}.price.price`, problems);
  return problems;
}

export function seriesPriceScaleProblems(series: { priceDecimals: number; ticks: Array<{ price: string }> }, path: string): Problems {
  const problems: Problems = [];
  series.ticks.forEach((tick, i) => expectScale(tick.price, series.priceDecimals, `${path}.ticks[${i}].price`, problems));
  return problems;
}

export { expectScale };
