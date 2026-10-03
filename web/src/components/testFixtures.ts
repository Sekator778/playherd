/**
 * Test-only builders for the market-shaped DTOs the lobby, room and positions-strip tests share.
 * Nothing in the app imports this file. `NOW` is the "server time" every fixture is relative to.
 */
import type {
  CategoryDto,
  CategoryId,
  CrowdRoundPositionDto,
  Interval,
  MarketDto,
  MarketsDto,
  MarketSeriesDto,
  PlayerSummaryDto,
  PriceTickDto,
  PublicPositionDto,
  RoundBetting,
  RoundRunning,
} from "../api/contract";
import { marketDto } from "../api/apiTestUtils";
import { categoryTitle } from "../api/marketMeta";

export const NOW = 1_000_000;

export function seriesDto(over: Partial<MarketSeriesDto> = {}): MarketSeriesDto {
  return {
    interval: "HOURLY",
    durationSec: 180,
    bettingRoundId: "BTC-1h-1180000",
    bettingClosesAtMs: NOW + 75_000,
    runningRoundId: "BTC-1h-1000000",
    runningEndsAtMs: NOW + 75_000,
    participantCount: 0,
    upStake: "0.000",
    downStake: "0.000",
    majorityDirection: null,
    ...over,
  };
}

/** A `MarketDto` with a lead series (3 MIN) and a 15 MIN one, an open market unless told otherwise. */
export function market(id: string, over: Partial<MarketDto> = {}): MarketDto {
  return marketDto(id, {
    series: [seriesDto({ bettingRoundId: `${id}-1h-1180000`, runningRoundId: `${id}-1h-1000000` }), seriesDto({ interval: "DAILY", durationSec: 900 })],
    price: { price: "100.00", asOfMs: NOW - 1_000, change24h: 0.0123, stale: false },
    ...over,
  });
}

/** `markets()` as the server answers it: the categories are the ones with an ACTIVE market, in Т-1 order. */
export function marketsOf(markets: MarketDto[], serverTimeMs = NOW): MarketsDto {
  const order: CategoryId[] = ["crypto", "politics", "sports", "stocks", "indices", "commodities"];
  const categories: CategoryDto[] = order
    .map((id) => ({ id, title: categoryTitle(id), marketCount: markets.filter((m) => m.category === id && m.status === "ACTIVE").length }))
    .filter((c) => c.marketCount > 0);
  return { serverTimeMs, categories, markets };
}

/** A small, varied catalogue: two crypto perps, a stock, a politics and a sports odds market, and a closed one. */
export function catalogue(): MarketDto[] {
  return [
    market("BTC", {
      symbol: "BTC",
      title: "Bitcoin",
      price: { price: "83451.00", asOfMs: NOW - 500, change24h: 0.0098, stale: false },
      series: [
        seriesDto({ participantCount: 12, upStake: "3000.000", downStake: "1000.000", majorityDirection: "UP" }),
        seriesDto({ interval: "DAILY", durationSec: 900 }),
      ],
      crowd: { capital: "103400.00", returnPct: 0.034, roundsTraded: 20, roundsWon: 12, lastOutcome: "WIN" },
      maxLeverage: 40,
    }),
    market("ETH", {
      symbol: "ETH",
      title: "Ethereum",
      priceDecimals: 2,
      price: { price: "2696.80", asOfMs: NOW - 500, change24h: -0.0141, stale: false },
      series: [
        seriesDto({ participantCount: 5, upStake: "500.000", downStake: "1500.000", majorityDirection: "DOWN", bettingClosesAtMs: NOW + 60_000 }),
        seriesDto({ interval: "DAILY", durationSec: 900 }),
      ],
      crowd: { capital: "97000.00", returnPct: -0.03, roundsTraded: 10, roundsWon: 4, lastOutcome: "LOSS" },
      maxLeverage: 25,
    }),
    market("xyz_NVDA", {
      symbol: "NVDA",
      title: "NVIDIA",
      category: "stocks",
      price: { price: "231.70", asOfMs: NOW - 90_000, change24h: 0.0021, stale: true },
      series: [seriesDto({ participantCount: 0 }), seriesDto({ interval: "DAILY", durationSec: 900 })],
      crowd: { capital: "100000.00", returnPct: 0, roundsTraded: 0, roundsWon: 0, lastOutcome: null },
      maxLeverage: 20,
    }),
    market("PM900101", {
      kind: "ODDS",
      category: "politics",
      symbol: "RUNOFF",
      title: "Will Brazil's 2026 presidential election go to a runoff?",
      subtitle: "Yes · Brazil election",
      source: "polymarket",
      priceDecimals: 6,
      maxLeverage: 10,
      endsAtMs: NOW + 5.5 * 86_400_000, // "5d 11h": half a day of slack, the mock clock runs 60× real time
      price: { price: "0.580000", asOfMs: NOW - 500, change24h: 0.0312, stale: false },
      series: [
        seriesDto({ participantCount: 3, upStake: "200.000", downStake: "200.000", majorityDirection: null }),
        seriesDto({ interval: "DAILY", durationSec: 900 }),
      ],
      crowd: { capital: "100800.00", returnPct: 0.008, roundsTraded: 6, roundsWon: 4, lastOutcome: "WIN" },
    }),
    market("PM900104", {
      kind: "ODDS",
      category: "sports",
      symbol: "GER",
      title: "Will Germany win against Serbia?",
      subtitle: "Germany · UEFA Nations League",
      source: "polymarket",
      priceDecimals: 6,
      maxLeverage: 10,
      // A sports game has no end date; its source's date is the kick-off, a little over two hours away
      // (the 40 s keep "2h 05m" steady while the mock clock runs 60× real time under a slow test).
      endsAtMs: null,
      eventStartsAtMs: NOW + 125 * 60_000 + 40_000,
      price: { price: "0.640000", asOfMs: NOW - 500, change24h: null, stale: false },
      series: [
        seriesDto({ participantCount: 8, upStake: "800.000", downStake: "100.000", majorityDirection: "UP", bettingClosesAtMs: NOW + 30_000 }),
        seriesDto({ interval: "DAILY", durationSec: 900 }),
      ],
      crowd: { capital: "100000.00", returnPct: 0, roundsTraded: 0, roundsWon: 0, lastOutcome: null },
    }),
    market("PM900199", {
      kind: "ODDS",
      category: "sports",
      symbol: "OLD",
      title: "Will the old match end in a draw?",
      subtitle: "Draw · Friendly",
      source: "polymarket",
      status: "CLOSED",
      priceDecimals: 6,
      maxLeverage: 10,
      price: { price: "0.310000", asOfMs: NOW - 600_000, change24h: -0.05, stale: false },
      series: [seriesDto({ participantCount: 0 }), seriesDto({ interval: "DAILY", durationSec: 900 })],
      crowd: { capital: "99000.00", returnPct: -0.01, roundsTraded: 3, roundsWon: 1, lastOutcome: "LIQUIDATED" },
    }),
  ];
}

/**
 * The crypto markets the way a server lists them since Р-76 (ids `spot_*`): priced by the exchange of the asset itself with a
 * backup exchange behind it — BTC, ETH and DOGE on Coinbase with Kraken behind them, HYPE on Hyperliquid's spot market with
 * Coinbase behind it — and the legacy BTC perpetual that the new market replaced, closed with the server's reason. `spot_ETH`
 * shows Kraken's price (its main exchange has no fresh price, `MarketPriceDto.venue`), the others their own.
 */
export function spotCatalogue(): MarketDto[] {
  return [
    market("spot_BTC", {
      symbol: "BTC",
      title: "Bitcoin",
      source: "coinbase",
      backupSource: "kraken",
      price: { price: "84010.50", asOfMs: NOW - 400, change24h: 0.0098, stale: false, venue: null },
      maxLeverage: 40,
      oracleIndex: 20,
    }),
    market("spot_ETH", {
      symbol: "ETH",
      title: "Ethereum",
      source: "coinbase",
      backupSource: "kraken",
      price: { price: "2696.80", asOfMs: NOW - 700, change24h: -0.0141, stale: false, venue: "kraken" },
      maxLeverage: 25,
      oracleIndex: 21,
    }),
    market("spot_DOGE", {
      symbol: "DOGE",
      title: "Dogecoin",
      source: "coinbase",
      backupSource: "kraken",
      priceDecimals: 7,
      price: { price: "0.0945340", asOfMs: NOW - 300, change24h: 0.0211, stale: false },
      maxLeverage: 10,
      oracleIndex: 22,
    }),
    market("spot_HYPE", {
      symbol: "HYPE",
      title: "Hyperliquid",
      source: "hyperliquid",
      backupSource: "coinbase",
      price: { price: "87.235", asOfMs: NOW - 900, change24h: 0.0305, stale: false, venue: null },
      priceDecimals: 3,
      maxLeverage: 10,
      oracleIndex: 23,
    }),
    market("BTC", {
      symbol: "BTC",
      title: "Bitcoin",
      status: "CLOSED",
      source: "hyperliquid",
      price: { price: "83990.00", asOfMs: NOW - 180_000, change24h: 0.0091, stale: false },
      closedAtMs: NOW - 180_000,
      closeReason: "Moved to a new price source: Coinbase",
      oracleIndex: 0,
    }),
  ];
}

// ---------------------------------------------------------------------------
// Rounds, ticks and positions (room / chart / strip tests)
// ---------------------------------------------------------------------------

const HOUR = 3_600_000;

/** An open-for-betting round whose window starts far enough ahead that a test never sees it close. */
export function bettingRoundOf(marketId: string, asset: string, over: Partial<RoundBetting> = {}): RoundBetting {
  return {
    phase: "BETTING",
    roundId: `${marketId}-1h-${NOW + 10 * HOUR}`,
    window: { interval: "HOURLY", startMs: NOW + 10 * HOUR, endMs: NOW + 11 * HOUR },
    marketId,
    asset,
    kind: "PERP",
    priceDecimals: 2,
    source: "hyperliquid",
    participantCount: 0,
    myBet: null,
    upStake: "0.000",
    downStake: "0.000",
    majorityDirection: null,
    ...over,
  };
}

export const OTHER_PLAYER: PlayerSummaryDto = { playerId: "npc-1", nickname: "rival", walletAddress: "txch1rival", displayName: "rival (txch1r…ival)", isBot: true };

export function publicPosition(over: Partial<PublicPositionDto> = {}): PublicPositionDto {
  return {
    playerId: "npc-1",
    roundId: "BTC-1h-0",
    direction: "UP",
    leverage: 10,
    size: "0.01200000",
    entryPrice: "100.00",
    liquidationPrice: "90.50",
    pnl: null,
    stake: "100.000",
    player: OTHER_PLAYER,
    liquidated: false,
    payout: null,
    directionCorrect: null,
    ...over,
  };
}

export function crowdPosition(over: Partial<CrowdRoundPositionDto> = {}): CrowdRoundPositionDto {
  return {
    roundId: "BTC-1h-0",
    direction: "DOWN",
    leverage: 7,
    margin: "10000.00",
    entryPrice: "100.00",
    liquidationPrice: "114.00",
    ...over,
  };
}

/** A running round that opened at 100.00. */
export function runningRoundOf(marketId: string, asset: string, over: Partial<RoundRunning> = {}): RoundRunning {
  return {
    phase: "RUNNING",
    roundId: `${marketId}-1h-0`,
    window: { interval: "HOURLY", startMs: NOW - HOUR, endMs: NOW + HOUR },
    marketId,
    asset,
    kind: "PERP",
    priceDecimals: 2,
    source: "hyperliquid",
    openPrice: "100.00",
    positions: [],
    crowd: null,
    ...over,
  };
}

/** `count` ticks one every `stepMs` ending at `endMs`, on a gentle wave around `base`. */
export function ticksOf(count: number, endMs: number, base = 100, stepMs = 3_000, decimals = 2): PriceTickDto[] {
  return Array.from({ length: count }, (_, i) => ({
    atMs: endMs - (count - 1 - i) * stepMs,
    price: (base + Math.sin(i / 6) * base * 0.002).toFixed(decimals),
  }));
}

export function pairOf(interval: Interval, running: RoundRunning, betting: RoundBetting) {
  return { interval, running, betting };
}
