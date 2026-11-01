/**
 * Every market's crowd account (D-5, D-17, D-25, D-26, T-6): "one continuous graph from day one"
 * plus the two comparison lines — "hold the asset" (`holdCapital`) and "anti-crowd"
 * (`antiCrowdCapital`), `CrowdHistoryPointDto`'s "proposal" shape, contract.ts. Resolution here
 * is one point per DAILY round (a mock simplification: the real series would also gain a point per
 * settled HOURLY round — daily is enough to draw a readable multi-month chart without generating
 * thousands of points).
 *
 * The crowd's own direction/leverage/margin per day are internal to this generator (not part of
 * `CrowdHistoryPointDto`) — only used to walk the capital forward with the exact same isolated
 * margin math as everywhere else (`api/liquidation.ts`, per market), so the chart's numbers are at
 * least internally consistent with how a leveraged paper position actually behaves. A market's
 * account starts on its own listing day (`MockMarketSpec.listedAtMs`) at $100 000 and compounds from
 * there; the BTC account is the mock's original one (same seeds, same days).
 */
import type {
  CrowdAccountDto,
  CrowdHistoryPointDto,
  EpochMs,
  MarketCrowdBriefDto,
  SeasonReturnDto,
} from "./contract";
import { CROWD_STARTING_CAPITAL_USD, CROWD_MARGIN_SHARE_OF_CAPITAL } from "./contract";
import { DAY_MS, roundIdForWindow, windowFor } from "./clock";
import { rngFor, chance, randInt } from "./rng";
import {
  LONG,
  SHORT,
  isLiquidatedInRange,
  liquidationPrice,
  payoutAtRoundClose,
  payoutOnLiquidation,
  sizeForMargin,
  type Side,
} from "./liquidation";
import type { MockMarketSpec } from "./mockMarkets";
import { ohlcFor, priceAt } from "./mockPrices";
import { seasonIdOf } from "./season";

interface DailyStep {
  atMs: number;
  crowdCapital: number;
  holdCapital: number;
  antiCrowdCapital: number;
  /** The crowd opened a position (false on a tie / empty round, D-5). */
  traded: boolean;
  /** Ended with more capital than it started with. */
  won: boolean;
  /** Closed where it opened: traded, neither won nor lost (`MarketCrowdBriefDto.roundsWon`). */
  flat: boolean;
  liquidated: boolean;
}

/** `MarketCrowdBriefDto.lastOutcome` of a traded round: a liquidation first, then flat (capital unchanged to the
 *  cent — NOT a loss), then won, else lost. */
export function crowdOutcomeOf(step: Pick<DailyStep, "liquidated" | "flat" | "won">): NonNullable<MarketCrowdBriefDto["lastOutcome"]> {
  if (step.liquidated) return "LIQUIDATED";
  if (step.flat) return "FLAT";
  return step.won ? "WIN" : "LOSS";
}

/** Capital to whole cents, so "unchanged" is not at the mercy of float noise (`capital − margin + payout`). */
function cents(capital: number): number {
  return Math.round(capital * 100);
}

function stepAccount(
  spec: MockMarketSpec,
  capitalBefore: number,
  side: Side,
  leverage: number,
  window: ReturnType<typeof windowFor>,
): { capital: number; liquidated: boolean } {
  const ohlc = ohlcFor(spec, window);
  const margin = capitalBefore * CROWD_MARGIN_SHARE_OF_CAPITAL;
  const size = sizeForMargin(margin, leverage, ohlc.open, spec);
  const liqPrice = liquidationPrice(size, ohlc.open, leverage, side, spec);
  const liquidated = isLiquidatedInRange(side, liqPrice, ohlc.low, ohlc.high);
  const payout = liquidated
    ? payoutOnLiquidation()
    : payoutAtRoundClose(size, ohlc.open, leverage, side, ohlc.close, spec);
  return { capital: capitalBefore - margin + payout.toNumber(), liquidated };
}

/** One market's chain of daily steps, extended lazily as the simulated clock advances. Step `i` is
 *  the DAILY round that closes `i + 1` days after the market's day one. */
const chains = new Map<string, DailyStep[]>();

function stepsUntil(spec: MockMarketSpec, asOfMs: EpochMs): DailyStep[] {
  let chain = chains.get(spec.id);
  if (!chain) {
    chain = [];
    chains.set(spec.id, chain);
  }
  const dayOneMs = spec.listedAtMs;
  const count = Math.max(0, Math.floor((asOfMs - dayOneMs) / DAY_MS));
  const startingCapital = Number(CROWD_STARTING_CAPITAL_USD);
  const holdBase = priceAt(spec, dayOneMs, "coarse");

  while (chain.length < count) {
    const i = chain.length;
    const previous = chain[i - 1];
    const atMs = dayOneMs + (i + 1) * DAY_MS;
    const window = windowFor("DAILY", atMs - 1); // the daily round that just closed at `atMs`
    const roundId = roundIdForWindow(spec.id, window);
    const rng = rngFor("crowd-daily", roundId);

    // Momentum-flavored majority: the crowd tends to chase the previous day's move (a plausible
    // narrative for why "anti-crowd" sometimes wins; a proposal, not a modeled fact).
    const prevClose = priceAt(spec, window.startMs - DAY_MS, "coarse");
    const momentumUp = priceAt(spec, window.startMs, "coarse") >= prevClose;
    const crowdSide: Side = chance(rng, momentumUp ? 0.64 : 0.36) ? LONG : SHORT;
    const antiSide: Side = crowdSide === LONG ? SHORT : LONG;
    // D-14: weighted-avg leverage of the winning side, clamped 2..the market's cap
    const leverage = Math.min(spec.maxLeverage, randInt(rng, 3, 16));

    const crowdBefore = previous ? previous.crowdCapital : startingCapital;
    const antiBefore = previous ? previous.antiCrowdCapital : startingCapital;
    // A tie / empty round opens no position (D-5). BTC's original series never had one; the other
    // markets do now and then (a separate seed, so BTC's stream is untouched).
    const tie = spec.id !== "BTC" && chance(rngFor("crowd-tie", roundId), 0.06);

    const holdCapital = (priceAt(spec, atMs, "coarse") / holdBase) * startingCapital;
    if (tie) {
      chain.push({
        atMs: window.endMs - 1,
        crowdCapital: crowdBefore,
        holdCapital,
        antiCrowdCapital: antiBefore,
        traded: false,
        won: false,
        flat: false,
        liquidated: false,
      });
    } else {
      const crowd = stepAccount(spec, crowdBefore, crowdSide, leverage, window);
      const anti = stepAccount(spec, antiBefore, antiSide, leverage, window);
      chain.push({
        atMs: window.endMs - 1,
        crowdCapital: crowd.capital,
        holdCapital,
        antiCrowdCapital: anti.capital,
        traded: true,
        won: cents(crowd.capital) > cents(crowdBefore),
        flat: !crowd.liquidated && cents(crowd.capital) === cents(crowdBefore),
        liquidated: crowd.liquidated,
      });
    }
  }
  return chain.slice(0, count);
}

/** The crowd account's capital right after the last daily round that closed by `asOfMs`. */
export function crowdCapitalAt(spec: MockMarketSpec, asOfMs: EpochMs): number {
  const steps = stepsUntil(spec, asOfMs);
  return steps.length > 0 ? steps[steps.length - 1].crowdCapital : Number(CROWD_STARTING_CAPITAL_USD);
}

export function crowdAccount(spec: MockMarketSpec, asOfMs: EpochMs): CrowdAccountDto {
  const steps = stepsUntil(spec, asOfMs);
  let lastSeason: string | null = null;
  const history: CrowdHistoryPointDto[] = steps.map((step, i) => {
    const season = seasonIdOf(step.atMs);
    const roundId = roundIdForWindow(spec.id, windowFor("DAILY", step.atMs));
    const boundary = season !== lastSeason ? season : null;
    lastSeason = season;
    return {
      roundId,
      atMs: step.atMs,
      seasonBoundary: i === 0 ? season : boundary,
      crowdCapital: step.crowdCapital.toFixed(2),
      holdCapital: step.holdCapital.toFixed(2),
      antiCrowdCapital: step.antiCrowdCapital.toFixed(2),
    };
  });

  // Each season's return is against the crowd's capital at the end of the PREVIOUS season —
  // capital itself is never reset (D-25), only sliced per season for this one figure.
  const seasonReturns: SeasonReturnDto[] = [];
  const seasonIds = [...new Set(steps.map((step) => seasonIdOf(step.atMs)))];
  let runningStart = Number(CROWD_STARTING_CAPITAL_USD);
  for (const season of seasonIds) {
    const stepsInSeason = steps.filter((step) => seasonIdOf(step.atMs) === season);
    const endCapital = stepsInSeason[stepsInSeason.length - 1].crowdCapital;
    seasonReturns.push({ season, returnPct: runningStart > 0 ? endCapital / runningStart - 1 : 0 });
    runningStart = endCapital;
  }

  return {
    marketId: spec.id,
    symbol: spec.symbol,
    capital: (steps.length > 0 ? steps[steps.length - 1].crowdCapital : Number(CROWD_STARTING_CAPITAL_USD)).toFixed(2),
    history,
    seasonReturns,
  };
}

/** `MarketDto.crowd`: how the herd is doing in this market, from the same steps as the history. */
export function crowdBrief(spec: MockMarketSpec, asOfMs: EpochMs): MarketCrowdBriefDto {
  const steps = stepsUntil(spec, asOfMs);
  const capital = steps.length > 0 ? steps[steps.length - 1].crowdCapital : Number(CROWD_STARTING_CAPITAL_USD);
  const traded = steps.filter((s) => s.traded);
  const last = traded[traded.length - 1];
  return {
    capital: capital.toFixed(2),
    returnPct: capital / Number(CROWD_STARTING_CAPITAL_USD) - 1,
    roundsTraded: traded.length,
    roundsWon: traded.filter((s) => s.won).length,
    lastOutcome: last ? crowdOutcomeOf(last) : null,
  };
}
