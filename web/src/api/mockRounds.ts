/**
 * Builds a `RoundDto` (any phase) for a given window, deterministically, from a seeded set of
 * NPC bets plus (optionally) the signed-in demo player's own bet. Pure functions of
 * `(window, ...)` — no mutable state lives here; `mockApi.ts` owns the only piece of real state
 * (the player's own recorded bets) and passes it in.
 *
 * Mock simplification, called out once here rather than at every call site: liquidation for a
 * RUNNING round is computed from the round's full (eventual) OHLC, exactly as it will be at
 * SETTLED — the demo clock's price path is a formula, not a live feed, so there is no real
 * "hasn't happened yet" moment to preserve, and this keeps a position's liquidated flag stable
 * (never flips back) as the round plays out. `payout`/`directionCorrect` still correctly read
 * `null` until the round actually reaches SETTLED, per `PublicPositionDto`'s contract.
 */
import type {
  BetDto,
  CancellationDto,
  CrowdRoundPositionDto,
  CrowdRoundResultDto,
  Direction,
  LiveBetDto,
  PlayerSummaryDto,
  PublicPositionDto,
  RoundBetting,
  RoundCancelled,
  RoundRunning,
  RoundSettled,
  RoundWindowDto,
} from "./contract";
import { CROWD_MARGIN_SHARE_OF_CAPITAL } from "./contract";
import { ohlcFor, prevWindow, roundIdFor, simNowMs, type WindowOhlc } from "./clock";
import { chance, randFloat, randInt, rngFor } from "./rng";
import {
  LONG,
  SHORT,
  isLiquidatedInRange,
  liquidationPrice,
  payoutAtRoundClose,
  payoutOnLiquidation,
  sizeForMargin,
  unrealizedPnl,
  type Side,
} from "./liquidation";
import { NPC_COUNT, npcPlayer } from "./mockPlayers";

export interface OwnBetInfo {
  bet: BetDto;
  player: PlayerSummaryDto;
}

function directionSide(direction: Direction): Side {
  return direction === "UP" ? LONG : SHORT;
}

function generateNpcBets(roundId: string): BetDto[] {
  const rng = rngFor("round-bets", roundId);
  const participantCount = randInt(rng, 12, 32);
  const probUp = randFloat(rng, 0.32, 0.68); // per-round sentiment skew, not always 50/50
  const indices = new Set<number>();
  while (indices.size < participantCount) {
    indices.add(Math.floor(rng() * NPC_COUNT));
  }
  return [...indices].map((i) => {
    const direction: Direction = chance(rng, probUp) ? "UP" : "DOWN";
    const leverage = Math.min(40, Math.max(2, Math.round(randFloat(rng, 2, 22))));
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

function mergedBets(roundId: string, own?: OwnBetInfo | null): BetDto[] {
  const npcBets = generateNpcBets(roundId);
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
  bet: BetDto,
  ohlc: WindowOhlc,
  settled: boolean,
  own?: OwnBetInfo | null,
): PublicPositionDto {
  const side = directionSide(bet.direction);
  const size = sizeForMargin(bet.stake, bet.leverage, ohlc.open);
  const liqPrice = liquidationPrice(size, ohlc.open, bet.leverage, side);
  const liquidated = isLiquidatedInRange(side, liqPrice, ohlc.low, ohlc.high);
  const open = Number(ohlc.open);
  const close = Number(ohlc.close);
  const directionCorrect =
    !settled || close === open ? null : side === LONG ? close > open : close < open;
  const payout = settled
    ? (liquidated ? payoutOnLiquidation() : payoutAtRoundClose(size, ohlc.open, bet.leverage, side, ohlc.close)).toFixed(3)
    : null;
  const pnl = settled ? unrealizedPnl(size, ohlc.open, side, ohlc.close).toFixed(2) : null;
  return {
    playerId: bet.playerId,
    roundId: bet.roundId,
    direction: bet.direction,
    leverage: bet.leverage,
    size: size.toFixed(8),
    entryPrice: ohlc.open,
    liquidationPrice: liqPrice.toFixed(2),
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

/** Р-14: stake-weighted average leverage of the majority side, HALF_UP, clamped 2..40. */
function crowdLeverage(majority: BetDto[]): number {
  const totalStake = majority.reduce((sum, b) => sum + Number(b.stake), 0);
  const weighted = majority.reduce((sum, b) => sum + b.leverage * Number(b.stake), 0) / totalStake;
  return Math.min(40, Math.max(2, Math.round(weighted)));
}

function crowdRunningPosition(
  roundId: string,
  bets: BetDto[],
  ohlc: WindowOhlc,
  capitalBefore: number,
): CrowdRoundPositionDto | null {
  const majority = majoritySide(bets);
  if (!majority) return null;
  const leverage = crowdLeverage(majority.bets);
  const margin = capitalBefore * CROWD_MARGIN_SHARE_OF_CAPITAL;
  const size = sizeForMargin(margin, leverage, ohlc.open);
  const liqPrice = liquidationPrice(size, ohlc.open, leverage, majority.side);
  return {
    roundId,
    direction: majority.side === LONG ? "UP" : "DOWN",
    leverage,
    margin: margin.toFixed(2),
    entryPrice: ohlc.open,
    liquidationPrice: liqPrice.toFixed(2),
  };
}

function crowdSettledResult(
  roundId: string,
  bets: BetDto[],
  ohlc: WindowOhlc,
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
  const leverage = crowdLeverage(majority.bets);
  const margin = capitalBefore * CROWD_MARGIN_SHARE_OF_CAPITAL;
  const size = sizeForMargin(margin, leverage, ohlc.open);
  const liqPrice = liquidationPrice(size, ohlc.open, leverage, majority.side);
  const liquidated = isLiquidatedInRange(majority.side, liqPrice, ohlc.low, ohlc.high);
  const payout = liquidated
    ? payoutOnLiquidation()
    : payoutAtRoundClose(size, ohlc.open, leverage, majority.side, ohlc.close);
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

/**
 * Р-62's live crowd split/feed, computed purely from `bets` (already-merged NPC + own) — the
 * actual betting period is the CURRENTLY RUNNING window, not `window` itself (`window` here is
 * the future round `bets` will open positions in once betting closes, Р-19: "ставки принимаются в
 * течение всего предыдущего раунда"), hence `prevWindow(window)` below. Timestamps are cosmetic
 * (same reasoning as `clock.ts`'s own price-path noise) — seeded by `roundId` so a given round's
 * feed order is stable across polls, except the player's own bet, which always sorts to "just
 * now" the instant it exists.
 */
function liveBetFeed(roundId: string, bets: BetDto[], window: RoundWindowDto, own?: OwnBetInfo | null): LiveBetDto[] {
  const bettingPeriod = prevWindow(window);
  const span = Math.max(1, bettingPeriod.endMs - bettingPeriod.startMs);
  const rng = rngFor("bet-feed-times", roundId);
  const withTimes = bets.map((bet) => {
    const isOwn = Boolean(own) && own!.bet.roundId === roundId && bet.playerId === own!.bet.playerId;
    const atMs = isOwn ? simNowMs() : bettingPeriod.startMs + Math.floor(rng() * span);
    return { bet, atMs };
  });
  withTimes.sort((a, b) => b.atMs - a.atMs);
  return withTimes.slice(0, LIVE_FEED_LIMIT).map(({ bet, atMs }) => ({
    player: playerFor(bet.playerId, own),
    direction: bet.direction,
    leverage: bet.leverage,
    stake: bet.stake,
    atMs,
  }));
}

export function buildBettingRound(window: RoundWindowDto, own?: OwnBetInfo | null): RoundBetting {
  const roundId = roundIdFor(window);
  const bets = mergedBets(roundId, own);
  const upStake = bets.filter((b) => b.direction === "UP").reduce((sum, b) => sum + Number(b.stake), 0);
  const downStake = bets.filter((b) => b.direction === "DOWN").reduce((sum, b) => sum + Number(b.stake), 0);
  return {
    roundId,
    window,
    asset: "BTC",
    phase: "BETTING",
    participantCount: bets.length,
    myBet: own && own.bet.roundId === roundId ? own.bet : null,
    upStake: upStake.toFixed(3),
    downStake: downStake.toFixed(3),
    recentBets: liveBetFeed(roundId, bets, window, own),
  };
}

export function buildRunningRound(
  window: RoundWindowDto,
  capitalBefore: number,
  own?: OwnBetInfo | null,
): RoundRunning {
  const roundId = roundIdFor(window);
  const bets = mergedBets(roundId, own);
  const ohlc = ohlcFor(window);
  return {
    roundId,
    window,
    asset: "BTC",
    phase: "RUNNING",
    openPrice: ohlc.open,
    positions: bets.map((b) => toPublicPosition(b, ohlc, false, own)),
    crowd: crowdRunningPosition(roundId, bets, ohlc, capitalBefore),
  };
}

export function buildSettledRound(
  window: RoundWindowDto,
  capitalBefore: number,
  own?: OwnBetInfo | null,
): RoundSettled {
  const roundId = roundIdFor(window);
  const bets = mergedBets(roundId, own);
  const ohlc = ohlcFor(window);
  return {
    roundId,
    window,
    asset: "BTC",
    phase: "SETTLED",
    openPrice: ohlc.open,
    closePrice: ohlc.close,
    positions: bets.map((b) => toPublicPosition(b, ohlc, true, own)),
    crowd: crowdSettledResult(roundId, bets, ohlc, capitalBefore),
  };
}

export function buildCancelledRound(
  window: RoundWindowDto,
  reason: string,
  refundedBetCount: number,
  cancelledAtMs: number,
): RoundCancelled {
  const roundId = roundIdFor(window);
  const cancellation: CancellationDto = { roundId, reason, cancelledAtMs, refundedBetCount };
  return { roundId, window, asset: "BTC", phase: "CANCELLED", cancellation };
}

/** Own settlement outcome for a round that has fully closed — used by `mockApi.ts` to post the
 *  ledger `SETTLEMENT` entry exactly once, reusing the same math as the public position above. */
export function settleOwnBet(
  bet: BetDto,
  window: RoundWindowDto,
): { liquidated: boolean; payout: string; directionCorrect: boolean | null } {
  const ohlc = ohlcFor(window);
  const side = directionSide(bet.direction);
  const size = sizeForMargin(bet.stake, bet.leverage, ohlc.open);
  const liqPrice = liquidationPrice(size, ohlc.open, bet.leverage, side);
  const liquidated = isLiquidatedInRange(side, liqPrice, ohlc.low, ohlc.high);
  const open = Number(ohlc.open);
  const close = Number(ohlc.close);
  const directionCorrect = close === open ? null : side === LONG ? close > open : close < open;
  const payout = liquidated
    ? payoutOnLiquidation()
    : payoutAtRoundClose(size, ohlc.open, bet.leverage, side, ohlc.close);
  return { liquidated, payout: payout.toFixed(3), directionCorrect };
}
