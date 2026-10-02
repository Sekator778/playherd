/**
 * Builds a `RoundDto` (any phase) of any market for a given window, deterministically, from a
 * seeded set of NPC bets plus (optionally) the signed-in demo player's own bet. Pure functions of
 * `(market, window, ...)` — no mutable state lives here; `mockApi.ts` owns the only piece of real
 * state (the player's own recorded bets) and passes it in.
 *
 * All the numbers of a round — open, close, extremes (`mockPrices.ts`), size, liquidation price,
 * payout (`liquidation.ts`) — are computed per market with the same rule the server uses (Т-3,
 * Т-4): the market's price scale and leverage cap are parameters of every call. A RUNNING round
 * liquidates a position when the price path SO FAR has touched its liquidation price, and the
 * SETTLED round extends the same path to the window's end, so a liquidated flag never flips back.
 * `payout`/`directionCorrect` still correctly read `null` until the round actually reaches
 * SETTLED, per `PublicPositionDto`'s contract.
 */
import type {
  BetDto,
  BetFeedEntryDto,
  CancellationDto,
  CrowdRoundPositionDto,
  CrowdRoundResultDto,
  Direction,
  EpochMs,
  LiveBetDto,
  PlayerSummaryDto,
  PublicPositionDto,
  RoundBetting,
  RoundCancelled,
  RoundId,
  RoundRunning,
  RoundSettled,
  RoundWindowDto,
} from "./contract";
import { CROWD_MARGIN_SHARE_OF_CAPITAL, LEVERAGE_MIN } from "./contract";
import { prevWindow, roundIdForWindow, simNowMs } from "./clock";
import { chance, randFloat, randInt, rngFor } from "./rng";
import {
  LONG,
  SHORT,
  isLiquidatedInRange,
  liquidationPrice,
  payoutAtRoundClose,
  payoutOnLiquidation,
  priceStr,
  sizeForMargin,
  unrealizedPnl,
  type Side,
} from "./liquidation";
import { openingPriceKnown, type MockMarketSpec } from "./mockMarkets";
import { NPC_COUNT, npcPlayer } from "./mockPlayers";
import { ohlcFor, type RoundOhlc } from "./mockPrices";

export interface OwnBetInfo {
  bet: BetDto;
  player: PlayerSummaryDto;
}

function directionSide(direction: Direction): Side {
  return direction === "UP" ? LONG : SHORT;
}

/** One round's bots (Т-8, mock version): a seeded number of NPCs — the market's `bots` range, BTC
 *  12–32 as it always was — each with a direction, leverage within the market's cap, and stake.
 *  Seeded by the round id, so the same round always has the same crowd. */
function generateNpcBets(spec: MockMarketSpec, roundId: RoundId): BetDto[] {
  const rng = rngFor("round-bets", roundId);
  const participantCount = randInt(rng, spec.bots[0], spec.bots[1]);
  const probUp = randFloat(rng, 0.32, 0.68); // per-round sentiment skew, not always 50/50
  const leverageCeiling = Math.min(22, spec.maxLeverage);
  const indices = new Set<number>();
  while (indices.size < participantCount) {
    indices.add(Math.floor(rng() * NPC_COUNT));
  }
  return [...indices].map((i) => {
    const direction: Direction = chance(rng, probUp) ? "UP" : "DOWN";
    const leverage = Math.min(spec.maxLeverage, Math.max(LEVERAGE_MIN, Math.round(randFloat(rng, 2, leverageCeiling))));
    const stake = randInt(rng, 1, 20) * 100;
    return {
      playerId: npcPlayer(i).playerId,
      roundId,
      direction,
      leverage,
      stake: stake.toFixed(3),
    };
  });
}

/** Every bet of a round: the NPCs' plus the signed-in player's own, if it is on this round. */
export function roundBets(spec: MockMarketSpec, roundId: RoundId, own?: OwnBetInfo | null): BetDto[] {
  const npcBets = generateNpcBets(spec, roundId);
  if (!own || own.bet.roundId !== roundId) return npcBets;
  return [...npcBets.filter((b) => b.playerId !== own.bet.playerId), own.bet];
}

function playerFor(playerId: string, own?: OwnBetInfo | null): PlayerSummaryDto {
  if (own && own.player.playerId === playerId) return own.player;
  const match = /^npc-(\d+)$/.exec(playerId);
  if (match) return npcPlayer(Number(match[1]));
  // Should not happen — every bet's playerId is either an NPC or the caller's own player.
  return { playerId, nickname: playerId, walletAddress: "", displayName: playerId };
}

function toPublicPosition(
  spec: MockMarketSpec,
  bet: BetDto,
  ohlc: RoundOhlc,
  settled: boolean,
  own?: OwnBetInfo | null,
): PublicPositionDto {
  const side = directionSide(bet.direction);
  const size = sizeForMargin(bet.stake, bet.leverage, ohlc.open, spec);
  const liqPrice = liquidationPrice(size, ohlc.open, bet.leverage, side, spec);
  const liquidated = isLiquidatedInRange(side, liqPrice, ohlc.low, ohlc.high);
  const open = Number(ohlc.open);
  const close = Number(ohlc.close);
  const directionCorrect =
    !settled || close === open ? null : side === LONG ? close > open : close < open;
  const payout = settled
    ? (liquidated ? payoutOnLiquidation() : payoutAtRoundClose(size, ohlc.open, bet.leverage, side, ohlc.close, spec)).toFixed(3)
    : null;
  const pnl = settled ? unrealizedPnl(size, ohlc.open, side, ohlc.close, spec).toFixed(2) : null;
  return {
    playerId: bet.playerId,
    roundId: bet.roundId,
    direction: bet.direction,
    leverage: bet.leverage,
    size: size.toFixed(8),
    entryPrice: ohlc.open,
    liquidationPrice: priceStr(liqPrice, spec),
    pnl,
    stake: bet.stake, // exact — no need for callers to derive it (contract.ts's PositionDto.stake)
    player: playerFor(bet.playerId, own),
    liquidated,
    payout,
    directionCorrect,
  };
}

function majoritySide(bets: BetDto[]): { side: Side; bets: BetDto[] } | null {
  if (bets.length === 0) return null;
  let up = 0;
  let down = 0;
  for (const b of bets) {
    const stake = Number(b.stake);
    if (b.direction === "UP") up += stake;
    else down += stake;
  }
  if (up === down) return null;
  const side: Side = up > down ? LONG : SHORT;
  const majority = bets.filter((b) => directionSide(b.direction) === side);
  return { side, bets: majority };
}

/** The majority side of a round's bets as a direction (`null` for a tie, 0 vs 0 included) —
 *  `MarketSeriesDto.majorityDirection`. */
export function majorityDirection(bets: BetDto[]): Direction | null {
  const majority = majoritySide(bets);
  return majority ? (majority.side === LONG ? "UP" : "DOWN") : null;
}

/** Р-14: stake-weighted average leverage of the majority side, HALF_UP, clamped to 2..the market's cap. */
function crowdLeverage(majority: BetDto[], spec: MockMarketSpec): number {
  const totalStake = majority.reduce((sum, b) => sum + Number(b.stake), 0);
  const weighted = majority.reduce((sum, b) => sum + b.leverage * Number(b.stake), 0) / totalStake;
  return Math.min(spec.maxLeverage, Math.max(LEVERAGE_MIN, Math.round(weighted)));
}

function crowdRunningPosition(
  spec: MockMarketSpec,
  roundId: RoundId,
  bets: BetDto[],
  ohlc: RoundOhlc,
  capitalBefore: number,
): CrowdRoundPositionDto | null {
  const majority = majoritySide(bets);
  if (!majority) return null;
  const leverage = crowdLeverage(majority.bets, spec);
  const margin = capitalBefore * CROWD_MARGIN_SHARE_OF_CAPITAL;
  const size = sizeForMargin(margin, leverage, ohlc.open, spec);
  const liqPrice = liquidationPrice(size, ohlc.open, leverage, majority.side, spec);
  return {
    roundId,
    direction: majority.side === LONG ? "UP" : "DOWN",
    leverage,
    margin: margin.toFixed(2),
    entryPrice: ohlc.open,
    liquidationPrice: priceStr(liqPrice, spec),
  };
}

function crowdSettledResult(
  spec: MockMarketSpec,
  roundId: RoundId,
  bets: BetDto[],
  ohlc: RoundOhlc,
  capitalBefore: number,
): CrowdRoundResultDto {
  const majority = majoritySide(bets);
  if (!majority) {
    const flat = capitalBefore.toFixed(2);
    return {
      roundId,
      traded: false,
      direction: null,
      leverage: null,
      margin: null,
      entryPrice: null,
      liquidated: false,
      capitalBefore: flat,
      capitalAfter: flat,
    };
  }
  const leverage = crowdLeverage(majority.bets, spec);
  const margin = capitalBefore * CROWD_MARGIN_SHARE_OF_CAPITAL;
  const size = sizeForMargin(margin, leverage, ohlc.open, spec);
  const liqPrice = liquidationPrice(size, ohlc.open, leverage, majority.side, spec);
  const liquidated = isLiquidatedInRange(majority.side, liqPrice, ohlc.low, ohlc.high);
  const payout = liquidated
    ? payoutOnLiquidation()
    : payoutAtRoundClose(size, ohlc.open, leverage, majority.side, ohlc.close, spec);
  const capitalAfter = capitalBefore - margin + payout.toNumber();
  return {
    roundId,
    traded: true,
    direction: majority.side === LONG ? "UP" : "DOWN",
    leverage,
    margin: margin.toFixed(2),
    entryPrice: ohlc.open,
    liquidated,
    capitalBefore: capitalBefore.toFixed(2),
    capitalAfter: capitalAfter.toFixed(2),
  };
}

/** Р-62's live bet feed cap — a demo-only number (no source fixes it), generous enough to feel
 *  "live" without listing every one of the round's ~12-32 NPC bets at once. */
const LIVE_FEED_LIMIT = 14;

/** When each bet of a round was placed, newest first: the NPCs' at seeded points of the betting
 *  period (the window BEFORE `window`, Р-19: "ставки принимаются в течение всего предыдущего
 *  раунда"), the player's own always "just now". Seeded by the round id, so a given round's feed
 *  order is stable across polls. */
function timedBets(
  roundId: RoundId,
  bets: BetDto[],
  window: RoundWindowDto,
  own?: OwnBetInfo | null,
): Array<{ bet: BetDto; atMs: EpochMs }> {
  const bettingPeriod = prevWindow(window);
  const span = Math.max(1, bettingPeriod.endMs - bettingPeriod.startMs);
  const rng = rngFor("bet-feed-times", roundId);
  const withTimes = bets.map((bet) => {
    const isOwn = Boolean(own) && own!.bet.roundId === roundId && bet.playerId === own!.bet.playerId;
    const atMs = isOwn ? simNowMs() : bettingPeriod.startMs + Math.floor(rng() * span);
    return { bet, atMs };
  });
  withTimes.sort((a, b) => b.atMs - a.atMs);
  return withTimes;
}

function liveBetFeed(roundId: RoundId, bets: BetDto[], window: RoundWindowDto, own?: OwnBetInfo | null): LiveBetDto[] {
  return timedBets(roundId, bets, window, own)
    .slice(0, LIVE_FEED_LIMIT)
    .map(({ bet, atMs }) => ({
      player: playerFor(bet.playerId, own),
      direction: bet.direction,
      leverage: bet.leverage,
      stake: bet.stake,
      atMs,
    }));
}

const BET_FEED_PAGE = 50;

/** The full feed of a round (`Api.betFeed`): oldest first, `seq` = the bet's place in time order,
 *  only what has been placed by `nowMs`, `seq > sinceSeq`, one page of {@link BET_FEED_PAGE}. */
export function betFeedPage(
  roundId: RoundId,
  bets: BetDto[],
  window: RoundWindowDto,
  nowMs: EpochMs,
  sinceSeq: number,
  own?: OwnBetInfo | null,
): { entries: BetFeedEntryDto[]; hasMore: boolean } {
  const ordered = timedBets(roundId, bets, window, own).reverse(); // oldest first
  const visible: BetFeedEntryDto[] = [];
  ordered.forEach(({ bet, atMs }, index) => {
    if (atMs > nowMs || index + 1 <= sinceSeq) return;
    const player = playerFor(bet.playerId, own);
    visible.push({
      seq: index + 1,
      atMs,
      playerDisplayName: player.displayName,
      isBot: Boolean(player.isBot),
      direction: bet.direction,
      stake: bet.stake,
      leverage: bet.leverage,
    });
  });
  return { entries: visible.slice(0, BET_FEED_PAGE), hasMore: visible.length > BET_FEED_PAGE };
}

export function buildBettingRound(spec: MockMarketSpec, window: RoundWindowDto, own?: OwnBetInfo | null): RoundBetting {
  const roundId = roundIdForWindow(spec.id, window);
  const bets = roundBets(spec, roundId, own);
  const upBets = bets.filter((b) => b.direction === "UP");
  const downBets = bets.filter((b) => b.direction === "DOWN");
  const upStake = upBets.reduce((sum, b) => sum + Number(b.stake), 0);
  const downStake = downBets.reduce((sum, b) => sum + Number(b.stake), 0);
  const majority = majoritySide(bets);
  return {
    roundId,
    window,
    marketId: spec.id,
    asset: spec.symbol,
    phase: "BETTING",
    participantCount: bets.length,
    myBet: own && own.bet.roundId === roundId ? own.bet : null,
    upStake: upStake.toFixed(3),
    downStake: downStake.toFixed(3),
    upCount: upBets.length,
    downCount: downBets.length,
    majorityDirection: majority ? (majority.side === LONG ? "UP" : "DOWN") : null,
    majorityLeverage: majority ? crowdLeverage(majority.bets, spec) : null,
    recentBets: liveBetFeed(roundId, bets, window, own),
  };
}

export function buildRunningRound(
  spec: MockMarketSpec,
  window: RoundWindowDto,
  capitalBefore: number,
  nowMs: EpochMs,
  own?: OwnBetInfo | null,
): RoundRunning {
  const roundId = roundIdForWindow(spec.id, window);
  if (!openingPriceKnown(spec, window.startMs)) {
    // The round started before the mock had a price for this market (a market listed moments ago): no
    // opening price yet, so — as the contract says — no positions are opened and the crowd has none.
    return { roundId, window, marketId: spec.id, asset: spec.symbol, phase: "RUNNING", openPrice: null, positions: [], crowd: null };
  }
  const bets = roundBets(spec, roundId, own);
  const ohlc = ohlcFor(spec, window, nowMs);
  return {
    roundId,
    window,
    marketId: spec.id,
    asset: spec.symbol,
    phase: "RUNNING",
    openPrice: ohlc.open,
    positions: bets.map((b) => toPublicPosition(spec, b, ohlc, false, own)),
    crowd: crowdRunningPosition(spec, roundId, bets, ohlc, capitalBefore),
  };
}

export function buildSettledRound(
  spec: MockMarketSpec,
  window: RoundWindowDto,
  capitalBefore: number,
  own?: OwnBetInfo | null,
): RoundSettled {
  const roundId = roundIdForWindow(spec.id, window);
  const bets = roundBets(spec, roundId, own);
  const ohlc = ohlcFor(spec, window);
  return {
    roundId,
    window,
    marketId: spec.id,
    asset: spec.symbol,
    phase: "SETTLED",
    openPrice: ohlc.open,
    closePrice: ohlc.close,
    positions: bets.map((b) => toPublicPosition(spec, b, ohlc, true, own)),
    crowd: crowdSettledResult(spec, roundId, bets, ohlc, capitalBefore),
  };
}

export function buildCancelledRound(
  spec: MockMarketSpec,
  window: RoundWindowDto,
  reason: string,
  refundedBetCount: number,
  cancelledAtMs: number,
): RoundCancelled {
  const roundId = roundIdForWindow(spec.id, window);
  const cancellation: CancellationDto = { roundId, reason, cancelledAtMs, refundedBetCount };
  return { roundId, window, marketId: spec.id, asset: spec.symbol, phase: "CANCELLED", cancellation };
}

/** Own settlement outcome for a round that has fully closed — used by `mockApi.ts` to post the
 *  ledger `SETTLEMENT` entry exactly once, reusing the same math as the public position above. */
export function settleOwnBet(
  spec: MockMarketSpec,
  bet: BetDto,
  window: RoundWindowDto,
): { liquidated: boolean; payout: string; directionCorrect: boolean | null } {
  const ohlc = ohlcFor(spec, window);
  const side = directionSide(bet.direction);
  const size = sizeForMargin(bet.stake, bet.leverage, ohlc.open, spec);
  const liqPrice = liquidationPrice(size, ohlc.open, bet.leverage, side, spec);
  const liquidated = isLiquidatedInRange(side, liqPrice, ohlc.low, ohlc.high);
  const open = Number(ohlc.open);
  const close = Number(ohlc.close);
  const directionCorrect = close === open ? null : side === LONG ? close > open : close < open;
  const payout = liquidated
    ? payoutOnLiquidation()
    : payoutAtRoundClose(size, ohlc.open, bet.leverage, side, ohlc.close, spec);
  return { liquidated, payout: payout.toFixed(3), directionCorrect };
}

/** A position of the signed-in player on a RUNNING round, as `MyPositionDto` needs it: the entry and
 *  liquidation price, the size the position was opened with (`PositionDto.size`) and whether the path so far
 *  has already liquidated it. `null` while the round has no opening price yet. */
export function ownRunningPosition(
  spec: MockMarketSpec,
  bet: BetDto,
  window: RoundWindowDto,
  nowMs: EpochMs,
): { entryPrice: string; liquidationPrice: string; size: string; liquidated: boolean } | null {
  if (!openingPriceKnown(spec, window.startMs)) return null;
  const ohlc = ohlcFor(spec, window, nowMs);
  const side = directionSide(bet.direction);
  const size = sizeForMargin(bet.stake, bet.leverage, ohlc.open, spec);
  const liqPrice = liquidationPrice(size, ohlc.open, bet.leverage, side, spec);
  return {
    entryPrice: ohlc.open,
    liquidationPrice: priceStr(liqPrice, spec),
    size: size.toFixed(8),
    liquidated: isLiquidatedInRange(side, liqPrice, ohlc.low, ohlc.high),
  };
}
