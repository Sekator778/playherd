/**
 * The mock's market catalog (Р-69): 6 categories, 18 markets — the six crypto perps, two stocks,
 * two indices, two commodities (the `herd.markets.*` defaults of `docs/research/markets_architecture.md`
 * §10) and three politics + three sports `ODDS` markets (Т-10). Static data only; the price paths
 * are `mockPrices.ts`, the round/crowd builders `mockRounds.ts`/`mockCrowdHistory.ts`.
 *
 * Every parameter that the server fixes at listing time (Т-3, Т-4) follows the same rule here:
 * `maxLeverage` is `min(40, Hyperliquid's tier-0 maxLeverage)` for a `PERP` and 10 for an `ODDS`
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
  maxLeverage: number;
  priceDecimals: number;
  /** The level the price path is centred on at {@link SIM_BASE_MS} (its own noise moves it a little): USD for a
   *  `PERP`, a probability for an `ODDS` market. */
  startPrice: number;
  /** `PERP`: standard deviation of the price over one simulated hour, as a fraction of it.
   *  `ODDS`: the same in logit space (a fraction of the log-odds). */
  hourlyVol: number;
  /** `ODDS`: when the underlying market is scheduled to end. */
  endsAtMs: EpochMs | null;
  /** Mock only: when this market is closed (`ODDS`: "less than 5 minutes to the end", Т-10). */
  closesAtMs: EpochMs | null;
  /** First day of this market's crowd account (Р-25, Т-6). */
  listedAtMs: EpochMs;
  /** Bots in a round of this market: `[min, max]` (Т-8). */
  bots: readonly [number, number];
}

/** Т-3: `priceDecimals = 2 + lotExp`, `lotExp = clamp(3 − floor(log10(price)), 0, 8)` at listing. */
export function perpPriceDecimals(listingPrice: number): number {
  return 2 + Math.min(8, Math.max(0, 3 - Math.floor(Math.log10(listingPrice))));
}

/** Т-4: an `ODDS` market's leverage cap (`herd.markets.odds.max-leverage`). */
const ODDS_MAX_LEVERAGE = 10;
/** Т-3: an `ODDS` price (0..1) always has `lotExp = 4`. */
const ODDS_PRICE_DECIMALS = 6;

const DAY_ONE = Date.UTC(2026, 0, 1);

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
): MockMarketSpec {
  return {
    id,
    kind: "PERP",
    category,
    symbol,
    title,
    subtitle: null,
    iconUrl: null,
    source: "hyperliquid",
    maxLeverage: Math.min(40, hlMaxLeverage),
    priceDecimals,
    startPrice,
    hourlyVol,
    endsAtMs: null,
    closesAtMs: null,
    listedAtMs,
    bots,
  };
}

function odds(
  id: MarketId,
  category: CategoryId,
  symbol: string,
  title: string,
  subtitle: string,
  startPrice: number,
  hourlyVol: number,
  endsAtMs: EpochMs,
  listedAtMs: EpochMs,
  bots: readonly [number, number],
  closesAtMs: EpochMs | null = null,
): MockMarketSpec {
  return {
    id,
    kind: "ODDS",
    category,
    symbol,
    title,
    subtitle,
    iconUrl: null,
    source: "polymarket",
    maxLeverage: ODDS_MAX_LEVERAGE,
    priceDecimals: ODDS_PRICE_DECIMALS,
    startPrice,
    hourlyVol,
    endsAtMs,
    closesAtMs,
    listedAtMs,
    bots,
  };
}

/** When the closing `ODDS` market closes: 90 simulated minutes (= 90 real seconds) after the demo
 *  starts, so a session sees it open first and then watch it go (Т-10, "до конца меньше 5 минут"). */
export const MOCK_CLOSING_AT_MS = SIM_BASE_MS + 90 * 60_000;

/** The politics markets were listed a week before the demo anchor. */
const POLITICS_LISTED_AT = Date.UTC(2026, 8, 18);

/** Listing order = the order `markets()` returns them in. */
export const MOCK_MARKETS: readonly MockMarketSpec[] = [
  // crypto (`herd.markets.crypto` default)
  perp("BTC", "crypto", "BTC", "Bitcoin", 83_451, 40, 0.0045, DAY_ONE, [12, 32], 2),
  perp("ETH", "crypto", "ETH", "Ethereum", 2_696.8, 25, 0.007, DAY_ONE, [10, 28]),
  perp("SOL", "crypto", "SOL", "Solana", 117.84, 20, 0.01, DAY_ONE, [8, 22]),
  perp("XRP", "crypto", "XRP", "XRP", 1.4964, 20, 0.009, Date.UTC(2026, 1, 15), [6, 18]),
  perp("DOGE", "crypto", "DOGE", "Dogecoin", 0.094534, 10, 0.012, Date.UTC(2026, 2, 1), [6, 18]),
  perp("HYPE", "crypto", "HYPE", "Hyperliquid", 87.235, 10, 0.014, Date.UTC(2026, 3, 10), [6, 18]),
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
  // sports (Polymarket tag 1) — the first one is the market that closes during a session
  odds(
    "PM900104",
    "sports",
    "GER",
    "Will Germany win against Serbia?",
    "Germany · UEFA Nations League",
    0.64,
    0.016,
    MOCK_CLOSING_AT_MS + 5 * 60_000,
    Date.UTC(2026, 8, 22),
    [4, 14],
    MOCK_CLOSING_AT_MS,
  ),
  odds(
    "PM900105",
    "sports",
    "POR",
    "Will Portugal win against Denmark?",
    "Portugal · UEFA Nations League",
    0.46,
    0.016,
    SIM_BASE_MS + 30 * HOUR_MS,
    Date.UTC(2026, 8, 22),
    [4, 14],
  ),
  odds(
    "PM900106",
    "sports",
    "NOR",
    "Will Norway win against Wales?",
    "Norway · UEFA Nations League",
    0.41,
    0.016,
    SIM_BASE_MS + 52 * HOUR_MS,
    Date.UTC(2026, 8, 23),
    [4, 14],
  ),
];

const BY_ID = new Map(MOCK_MARKETS.map((m) => [m.id, m] as const));

export function mockMarketById(id: MarketId): MockMarketSpec | undefined {
  return BY_ID.get(id);
}

/** The legacy BTC market — what every call that omits a `marketId` means (`rounds`, `crowdAccount`, …). */
export const MOCK_BTC: MockMarketSpec = BY_ID.get("BTC")!;

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
