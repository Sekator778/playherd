/**
 * The mock's market catalog (D-69): 6 categories, 19 markets — the six crypto markets, two stocks,
 * two indices, two commodities (the `herd.markets.*` defaults of `docs/research/markets_architecture.md`
 * §10) and four politics + three sports `ODDS` markets (T-10), one of them listed "moments ago" so the
 * no-opening-price state exists (`MOCK_NEW_LISTING_ID`). Static data only; the price paths
 * are `mockPrices.ts`, the round/crowd builders `mockRounds.ts`/`mockCrowdHistory.ts`.
 *
 * The sources are the ones the server names (D-74, D-76, `the decision log`): crypto is priced by the
 * exchange of the asset itself with a backup exchange behind it — BTC, ETH, SOL, XRP and DOGE on Coinbase with Kraken
 * behind them, HYPE on Hyperliquid's spot market with Coinbase behind it — stocks, indices and commodities by
 * Hyperliquid, `ODDS` by Polymarket. The mock never switches to a backup (no `MarketPriceDto.venue`, every round on its
 * main exchange): there is no exchange here to go silent — `/lab/states` draws those states from fixed data.
 *
 * Every parameter that the server fixes at listing time (T-3, T-4) follows the same rule here:
 * `maxLeverage` is `min(40, Hyperliquid's tier-0 maxLeverage)` for a `PERP` (the six crypto caps are also the server's
 * `herd.markets.spot.max-leverage` defaults) and 10 for an `ODDS`
 * market; `priceDecimals` is `2 + lotExp` with `lotExp = clamp(3 − floor(log10(price)), 0, 8)` at
 * the listing price (BTC is fixed at 2, an `ODDS` market at 6). The starting prices and leverage
 * caps are the Hyperliquid values measured 2026-10-01 (`docs/research/hl_multi_asset_live.md` §2.1
 * and §7.3) — only the starting point of a synthetic random walk, not live data (BTC keeps the
 * mock's older 83 451 anchor, `docs/research/hl_liquidation_math.md` §3). The `ODDS` questions have
 * the shapes seen in `docs/research/polymarket_data_api.md` §2.4/§3.2 (2026-10-01), with invented
 * ids (`PM9001xx`) and invented prices — do not read them as real odds.
 */
import type { CategoryId, EpochMs, MarketDto, MarketId, MarketKind } from "./contract";
import { HOUR_MS, SIM_BASE_MS } from "./clock";

export interface MockMarketSpec {
  id: MarketId;
  kind: MarketKind;
  category: CategoryId;
  symbol: string;
  title: string;
  subtitle: string | null;
  iconUrl: string | null;
  source: MarketDto["source"];
  /** The exchange behind `source` for a crypto market (`MarketDto.backupSource`, D-76); null for every other. */
  backupSource: MarketDto["backupSource"];
  maxLeverage: number;
  priceDecimals: number;
  /** The level the price path is centred on at {@link SIM_BASE_MS} (its own noise moves it a little): USD for a
   *  `PERP`, a probability for an `ODDS` market. */
  startPrice: number;
  /** `PERP`: standard deviation of the price over one simulated hour, as a fraction of it.
   *  `ODDS`: the same in logit space (a fraction of the log-odds). */
  hourlyVol: number;
  /** `ODDS`: when the underlying market is scheduled to end. Null for a sports game (its source "end
   *  date" is the kick-off, `eventStartsAtMs`) — as the contract says of `MarketDto.endsAtMs`. */
  endsAtMs: EpochMs | null;
  /** `ODDS` sports game: kick-off (`MarketDto.eventStartsAtMs`). */
  eventStartsAtMs: EpochMs | null;
  /** Mock only: when this market is closed (`ODDS`: "less than 5 minutes to the end", T-10). */
  closesAtMs: EpochMs | null;
  /** Mock only: why it closes, in the words a source would use (`MarketDto.closeReason`). */
  closeReason: string | null;
  /** First day of this market's crowd account (D-25, T-6). */
  listedAtMs: EpochMs;
  /** Mock only: the first instant the mock has a price for this market (null = from the start of time).
   *  A round that STARTED before it has no opening price (`RoundRunning.openPrice === null`) — the state of
   *  a market listed moments ago, which the server hands the window "now" lies in (markets_architecture.md
   *  §4 item 2). Also no 24 h change until a day of prices exists. */
  priceFromMs: EpochMs | null;
  /** The market's index in the on-chain oracle record (`MarketDto.oracleIndex`; 0 = BTC, the rest in
   *  listing order, T-9). */
  oracleIndex: number;
  /** Bots in a round of this market: `[min, max]` (T-8). */
  bots: readonly [number, number];
}

/** T-3: `priceDecimals = 2 + lotExp`, `lotExp = clamp(3 − floor(log10(price)), 0, 8)` at listing. */
export function perpPriceDecimals(listingPrice: number): number {
  return 2 + Math.min(8, Math.max(0, 3 - Math.floor(Math.log10(listingPrice))));
}

/** T-4: an `ODDS` market's leverage cap (`herd.markets.odds.max-leverage`). */
const ODDS_MAX_LEVERAGE = 10;
/** T-3: an `ODDS` price (0..1) always has `lotExp = 4`. */
const ODDS_PRICE_DECIMALS = 6;

const DAY_ONE = Date.UTC(2026, 0, 1);

type MockMarketBase = Omit<MockMarketSpec, "oracleIndex">;

/** Who prices a `PERP` market and who stands behind it (D-76): stocks, indices and commodities are Hyperliquid's own
 *  perpetuals with no backup; the crypto markets are spot markets with a backup exchange. */
type Venues = Pick<MockMarketBase, "source" | "backupSource">;
const HYPERLIQUID_PERP: Venues = { source: "hyperliquid", backupSource: null };
const COINBASE_KRAKEN: Venues = { source: "coinbase", backupSource: "kraken" };
const HYPERLIQUID_SPOT_COINBASE: Venues = { source: "hyperliquid", backupSource: "coinbase" };

function perp(
  id: MarketId,
  category: CategoryId,
  symbol: string,
  title: string,
  startPrice: number,
  hlMaxLeverage: number,
  hourlyVol: number,
  listedAtMs: EpochMs,
  bots: readonly [number, number],
  priceDecimals = perpPriceDecimals(startPrice),
  venues: Venues = HYPERLIQUID_PERP,
): MockMarketBase {
  return {
    id,
    kind: "PERP",
    category,
    symbol,
    title,
    subtitle: null,
    iconUrl: null,
    ...venues,
    maxLeverage: Math.min(40, hlMaxLeverage),
    priceDecimals,
    startPrice,
    hourlyVol,
    endsAtMs: null,
    eventStartsAtMs: null,
    closesAtMs: null,
    closeReason: null,
    listedAtMs,
    priceFromMs: null,
    bots,
  };
}

/** What only some `ODDS` markets have: a kick-off (sports), a closing moment and its reason, a first price. */
interface OddsExtras {
  eventStartsAtMs?: EpochMs;
  closesAtMs?: EpochMs;
  closeReason?: string;
  priceFromMs?: EpochMs;
}

/** `endsAtMs`: the source market's scheduled end (politics); a sports game has none — its source "end date" is
 *  the kick-off, which goes in `extras.eventStartsAtMs` instead (`MarketDto.endsAtMs`). */
function odds(
  id: MarketId,
  category: CategoryId,
  symbol: string,
  title: string,
  subtitle: string,
  startPrice: number,
  hourlyVol: number,
  endsAtMs: EpochMs | null,
  listedAtMs: EpochMs,
  bots: readonly [number, number],
  extras: OddsExtras = {},
): MockMarketBase {
  return {
    id,
    kind: "ODDS",
    category,
    symbol,
    title,
    subtitle,
    iconUrl: null,
    source: "polymarket",
    backupSource: null,
    maxLeverage: ODDS_MAX_LEVERAGE,
    priceDecimals: ODDS_PRICE_DECIMALS,
    startPrice,
    hourlyVol,
    endsAtMs,
    eventStartsAtMs: extras.eventStartsAtMs ?? null,
    closesAtMs: extras.closesAtMs ?? null,
    closeReason: extras.closeReason ?? null,
    listedAtMs,
    priceFromMs: extras.priceFromMs ?? null,
    bots,
  };
}

/** When the closing `ODDS` market closes: 90 simulated minutes (= 90 real seconds) after the demo
 *  starts, so a session sees it open first and then watch it go (T-10, "less than 5 minutes to the end"). */
export const MOCK_CLOSING_AT_MS = SIM_BASE_MS + 90 * 60_000;

/** The politics markets were listed a week before the demo anchor. */
const POLITICS_LISTED_AT = Date.UTC(2026, 8, 18);

/** The market listed "moments ago": the mock has a price for it only from two simulated minutes before the demo
 *  starts, so the round window the session opens in began before there was a price — that running round has no
 *  opening price (`RoundRunning.openPrice === null`, the "waiting for the opening price" state) until it ends:
 *  about 37 s into a session on the shorter series, about 9.6 minutes on the longer one. */
export const MOCK_NEW_LISTING_ID: MarketId = "PM900107";
const NEW_LISTING_PRICE_FROM = SIM_BASE_MS - 2 * 60_000;

/** Listing order = the order `markets()` returns them in; the oracle index follows it (BTC is 0, T-9). */
const CATALOG: readonly MockMarketBase[] = [
  // crypto (`herd.markets.crypto` default): spot markets with a backup exchange (D-76)
  perp("BTC", "crypto", "BTC", "Bitcoin", 83_451, 40, 0.0045, DAY_ONE, [12, 32], 2, COINBASE_KRAKEN),
  perp("ETH", "crypto", "ETH", "Ethereum", 2_696.8, 25, 0.007, DAY_ONE, [10, 28], undefined, COINBASE_KRAKEN),
  perp("SOL", "crypto", "SOL", "Solana", 117.84, 20, 0.01, DAY_ONE, [8, 22], undefined, COINBASE_KRAKEN),
  perp("XRP", "crypto", "XRP", "XRP", 1.4964, 20, 0.009, Date.UTC(2026, 1, 15), [6, 18], undefined, COINBASE_KRAKEN),
  perp("DOGE", "crypto", "DOGE", "Dogecoin", 0.094534, 10, 0.012, Date.UTC(2026, 2, 1), [6, 18], undefined, COINBASE_KRAKEN),
  perp("HYPE", "crypto", "HYPE", "Hyperliquid", 87.235, 10, 0.014, Date.UTC(2026, 3, 10), [6, 18], undefined, HYPERLIQUID_SPOT_COINBASE),
  // stocks (`herd.markets.stocks`, dex `xyz`)
  perp("xyz_NVDA", "stocks", "NVDA", "NVIDIA", 231.7, 20, 0.0035, Date.UTC(2026, 5, 1), [6, 16]),
  perp("xyz_TSLA", "stocks", "TSLA", "Tesla", 357.74, 20, 0.0055, Date.UTC(2026, 5, 1), [6, 16]),
  // indices
  perp("xyz_SP500", "indices", "SP500", "S&P 500", 7_676.3, 50, 0.0012, Date.UTC(2026, 5, 1), [3, 10]),
  perp("xyz_XYZ100", "indices", "XYZ100", "XYZ 100", 30_557, 30, 0.0018, Date.UTC(2026, 5, 1), [0, 7]),
  // commodities
  perp("xyz_GOLD", "commodities", "GOLD", "Gold", 4_176.3, 25, 0.002, Date.UTC(2026, 5, 1), [3, 10]),
  perp("xyz_BRENTOIL", "commodities", "BRENTOIL", "Brent crude oil", 102.37, 20, 0.005, Date.UTC(2026, 5, 1), [0, 6]),
  // politics (Polymarket tag 2)
  odds(
    "PM900101",
    "politics",
    "RUNOFF",
    "Will Brazil's 2026 presidential election go to a runoff?",
    "Yes · Brazil election",
    0.58,
    0.01,
    Date.UTC(2026, 9, 5, 12),
    POLITICS_LISTED_AT,
    [3, 12],
  ),
  odds(
    "PM900102",
    "politics",
    "SHUTDOWN",
    "Will the US federal government shut down before November 1?",
    "Yes · US Congress",
    0.34,
    0.014,
    Date.UTC(2026, 9, 31, 23, 59),
    POLITICS_LISTED_AT,
    [3, 12],
  ),
  odds(
    "PM900103",
    "politics",
    "TURNOUT",
    "Will turnout in the 2026 US midterms exceed 50%?",
    "Yes · US midterms 2026",
    0.47,
    0.008,
    Date.UTC(2026, 10, 4),
    Date.UTC(2026, 8, 21),
    [3, 10],
  ),
  // sports (Polymarket tag 1) — a game has a kick-off and no end date; the first one is the market that
  // kicks off and then closes during a session
  odds(
    "PM900104",
    "sports",
    "GER",
    "Will Germany win against Serbia?",
    "Germany · UEFA Nations League",
    0.64,
    0.016,
    null,
    Date.UTC(2026, 8, 22),
    [4, 14],
    { eventStartsAtMs: SIM_BASE_MS + 60 * 60_000, closesAtMs: MOCK_CLOSING_AT_MS, closeReason: "The source market ended" },
  ),
  odds(
    "PM900105",
    "sports",
    "POR",
    "Will Portugal win against Denmark?",
    "Portugal · UEFA Nations League",
    0.46,
    0.016,
    null,
    Date.UTC(2026, 8, 22),
    [4, 14],
    { eventStartsAtMs: SIM_BASE_MS + 30 * HOUR_MS },
  ),
  odds(
    "PM900106",
    "sports",
    "NOR",
    "Will Norway win against Wales?",
    "Norway · UEFA Nations League",
    0.41,
    0.016,
    null,
    Date.UTC(2026, 8, 23),
    [4, 14],
    { eventStartsAtMs: SIM_BASE_MS + 52 * HOUR_MS },
  ),
  // politics, listed moments ago — see `MOCK_NEW_LISTING_ID`
  odds(
    MOCK_NEW_LISTING_ID,
    "politics",
    "FED",
    "Will the Fed cut interest rates at its next meeting?",
    "Yes · US Federal Reserve",
    0.43,
    0.01,
    Date.UTC(2026, 10, 6, 19),
    SIM_BASE_MS - 3 * 60_000,
    [3, 10],
    { priceFromMs: NEW_LISTING_PRICE_FROM },
  ),
];

export const MOCK_MARKETS: readonly MockMarketSpec[] = CATALOG.map((spec, index) => ({ ...spec, oracleIndex: index }));

const BY_ID = new Map(MOCK_MARKETS.map((m) => [m.id, m] as const));

export function mockMarketById(id: MarketId): MockMarketSpec | undefined {
  return BY_ID.get(id);
}

/** The legacy BTC market — what every call that omits a `marketId` means (`rounds`, `crowdAccount`, …). */
export const MOCK_BTC: MockMarketSpec = BY_ID.get("BTC")!;

/** Whether the mock has a price for this market at `atMs` (a market listed moments ago has none before that). */
export function hasMockPrice(spec: MockMarketSpec, atMs: EpochMs): boolean {
  return spec.priceFromMs === null || atMs >= spec.priceFromMs;
}

/** Whether a round that starts at `startMs` has an opening price: the mock had a price at its start. */
export function openingPriceKnown(spec: MockMarketSpec, startMs: EpochMs): boolean {
  return hasMockPrice(spec, startMs);
}

/** Whether the mock has closed this market by `nowMs` (`MarketStatus.CLOSED`). */
export function isMockMarketClosed(spec: MockMarketSpec, nowMs: EpochMs): boolean {
  return spec.closesAtMs !== null && nowMs >= spec.closesAtMs;
}

/**
 * How long a closed market stays in `markets()` — "closed within the last 10 minutes (so a card
 * can say why it left)". The mock's clock runs 60× fast, so "10 minutes" is 10 real
 * minutes of a session (10 simulated hours), long enough to build the closed-card state against.
 */
export const CLOSED_LISTING_MS = 10 * HOUR_MS;

/** Whether `markets()` still lists the market at `nowMs`: every active one, and a closed one for {@link CLOSED_LISTING_MS}. */
export function isMockMarketListed(spec: MockMarketSpec, nowMs: EpochMs): boolean {
  return spec.closesAtMs === null || nowMs - spec.closesAtMs <= CLOSED_LISTING_MS;
}
