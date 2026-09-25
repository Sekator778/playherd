/**
 * The crowd account's long-running capital series (Р-17, Р-25, Р-26) — "one continuous graph
 * from day one" plus the two comparison lines (`CrowdHistoryPointDto`'s "предложение" shape,
 * contract.ts). Resolution here is one point per DAILY round (a mock simplification: the real
 * series would also gain a point per settled HOURLY round — daily is enough to draw a readable
 * multi-month chart without generating thousands of points).
 *
 * The crowd's own direction/leverage/margin per day are internal to this generator (not part of
 * `CrowdHistoryPointDto`) — only used to walk the capital forward with the exact same isolated
 * margin math as everywhere else (`api/liquidation.ts`), so the chart's numbers are at least
 * internally consistent with how a leveraged paper position actually behaves.
 */
import type { CrowdAccountDto, CrowdHistoryPointDto, SeasonReturnDto } from "./contract";
import { CROWD_STARTING_CAPITAL_USD, CROWD_MARGIN_SHARE_OF_CAPITAL } from "./contract";
import { DAY_MS, ohlcFor, roundIdFor, windowFor, btcPriceAt } from "./clock";
import { rngFor, chance, randInt } from "./rng";
import { LONG, SHORT, isLiquidatedInRange, liquidationPrice, payoutAtRoundClose, payoutOnLiquidation, sizeForMargin, type Side } from "./liquidation";
import { seasonIdOf } from "./season";

/** "Day one" of the crowd account (Р-25: capital never resets across seasons) — fixed so the
 *  chart always spans several full seasons plus the current partial one. предложение: no source
 *  fixes an actual launch date yet. */
export const CROWD_DAY_ONE_MS = Date.UTC(2026, 0, 1);

interface DailyStep {
  atMs: number;
  crowdCapital: number;
  btcHoldCapital: number;
  antiCrowdCapital: number;
}

function stepAccount(
  capitalBefore: number,
  side: Side,
  leverage: number,
  window: ReturnType<typeof windowFor>,
): number {
  const ohlc = ohlcFor(window);
  const margin = capitalBefore * CROWD_MARGIN_SHARE_OF_CAPITAL;
  const size = sizeForMargin(margin, leverage, ohlc.open);
  const liqPrice = liquidationPrice(size, ohlc.open, leverage, side);
  const liquidated = isLiquidatedInRange(side, liqPrice, ohlc.low, ohlc.high);
  const payout = liquidated
    ? payoutOnLiquidation()
    : payoutAtRoundClose(size, ohlc.open, leverage, side, ohlc.close);
  return capitalBefore - margin + payout.toNumber();
}

function buildSeries(asOfMs: number): DailyStep[] {
  const startingCapital = Number(CROWD_STARTING_CAPITAL_USD);
  const steps: DailyStep[] = [];
  let crowdCapital = startingCapital;
  let antiCrowdCapital = startingCapital;
  const btcAtDayOne = btcPriceAt(CROWD_DAY_ONE_MS);

  let atMs = CROWD_DAY_ONE_MS + DAY_MS;
  while (atMs <= asOfMs) {
    const window = windowFor("DAILY", atMs - 1); // the daily round that just closed at `atMs`
    const roundId = roundIdFor(window);
    const rng = rngFor("crowd-daily", roundId);

    // Momentum-flavored majority: the crowd tends to chase the previous day's move (a plausible
    // narrative for why "anti-crowd" sometimes wins — предложение, not a modeled fact).
    const prevClose = btcPriceAt(window.startMs - DAY_MS);
    const momentumUp = ohlcOpen(window) >= prevClose;
    const crowdSide: Side = chance(rng, momentumUp ? 0.64 : 0.36) ? LONG : SHORT;
    const antiSide: Side = crowdSide === LONG ? SHORT : LONG;
    const leverage = randInt(rng, 3, 16); // Р-14: weighted-avg leverage of the winning side, clamped 2..40

    crowdCapital = stepAccount(crowdCapital, crowdSide, leverage, window);
    antiCrowdCapital = stepAccount(antiCrowdCapital, antiSide, leverage, window);
    const btcHoldCapital = (btcPriceAt(atMs) / btcAtDayOne) * startingCapital;

    steps.push({ atMs: window.endMs - 1, crowdCapital, btcHoldCapital, antiCrowdCapital });
    atMs += DAY_MS;
  }
  return steps;
}

function ohlcOpen(window: ReturnType<typeof windowFor>): number {
  return Number(ohlcFor(window).open);
}

const seriesCache = new Map<number, DailyStep[]>();

function cachedSeries(asOfMs: number): DailyStep[] {
  const dayBucket = Math.floor((asOfMs - CROWD_DAY_ONE_MS) / DAY_MS);
  let cached = seriesCache.get(dayBucket);
  if (!cached) {
    cached = buildSeries(asOfMs);
    seriesCache.set(dayBucket, cached);
  }
  return cached;
}

export function crowdAccount(asOfMs: number): CrowdAccountDto {
  const steps = cachedSeries(asOfMs);
  let lastSeason: string | null = null;
  const history: CrowdHistoryPointDto[] = steps.map((step, i) => {
    const season = seasonIdOf(step.atMs);
    const roundId = roundIdFor(windowFor("DAILY", step.atMs));
    const boundary = season !== lastSeason ? season : null;
    lastSeason = season;
    return {
      roundId,
      atMs: step.atMs,
      seasonBoundary: i === 0 ? season : boundary,
      crowdCapital: step.crowdCapital.toFixed(2),
      btcHoldCapital: step.btcHoldCapital.toFixed(2),
      antiCrowdCapital: step.antiCrowdCapital.toFixed(2),
    };
  });

  // Each season's return is against the crowd's capital at the end of the PREVIOUS season —
  // capital itself is never reset (Р-25), only sliced per season for this one figure.
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
    capital: (steps.length > 0 ? steps[steps.length - 1].crowdCapital : Number(CROWD_STARTING_CAPITAL_USD)).toFixed(2),
    history,
    seasonReturns,
  };
}
