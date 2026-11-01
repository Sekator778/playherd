/**
 * The pinned "My bet" card's pure rules (D-98): which of the player's open bets it shows, and what state that bet is in. Everything is read from
 * what `myPositions()` and the market list already carry — no new data.
 */
import type { MyPositionDto } from "../api/contract";
import { isWaitingForOpen, liveReadOfPosition, type LiveRead } from "./livePosition";
import type { MarketParams } from "../api/liquidation";
import { isFixedMode } from "./betModeModel";

/** Where the price stands against the round's open for a "Win or lose" bet: ahead when it moved the bet's way, behind when against, level when it has not moved. */
export function fixedStanding(
  position: Pick<MyPositionDto, "direction" | "entryPrice">,
  currentPrice: string | null | undefined,
): "ahead" | "behind" | "level" | null {
  if (position.entryPrice === null || !currentPrice) return null;
  const move = Math.sign(Number(currentPrice) - Number(position.entryPrice));
  if (!Number.isFinite(move)) return null;
  if (move === 0) return "level";
  return (move > 0) === (position.direction === "UP") ? "ahead" : "behind";
}

/** When the bet's round starts (or started): for a round still open for bets the moment it starts, for a running one its end less its length. */
function startOf(position: MyPositionDto): number {
  return position.phase === "BETTING" ? position.untilMs : position.untilMs - Math.max(0, position.durationSec) * 1000;
}

/**
 * The newest open bet. `myPositions()` carries no placement time, so "newest" is inferred from the round's start: the bet whose round starts
 * (or started) last. A bet placed now is always for a round that starts later than any running one, which is what this is for.
 */
export function newestPosition(positions: readonly MyPositionDto[]): MyPositionDto | null {
  let best: MyPositionDto | null = null;
  for (const p of positions) if (best === null || startOf(p) > startOf(best)) best = p;
  return best;
}

/** Every open bet but `shown`, newest first — the dock's way to the others. */
export function otherPositions(positions: readonly MyPositionDto[], shown: MyPositionDto): MyPositionDto[] {
  return positions.filter((p) => p.roundId !== shown.roundId).sort((a, b) => startOf(b) - startOf(a));
}

/** The room-left share below which the card says "close to the wipeout line" in words (the Close zone of `heat.ts`). */
export const NEAR_WIPEOUT_FRACTION = 0.25;

export type MyBetState =
  | { kind: "waiting" }
  | { kind: "waitingOpen" }
  | { kind: "wiped" }
  | { kind: "noPrice" }
  /** D-106, "Win or lose": no live P&L and no wipeout line - only where the price stands against the open (`null`: no price yet). */
  | { kind: "fixed"; standing: "ahead" | "behind" | "level" | null }
  | { kind: "live"; read: LiveRead; mood: "winning" | "losing" | "even"; near: boolean };

export function myBetState(position: MyPositionDto, currentPrice: string | null | undefined, market: MarketParams | undefined): MyBetState {
  if (position.phase === "BETTING") return { kind: "waiting" };
  if (position.liquidated) return { kind: "wiped" };
  if (isWaitingForOpen(position)) return { kind: "waitingOpen" };
  if (isFixedMode(position)) return { kind: "fixed", standing: fixedStanding(position, currentPrice) };
  const read = liveReadOfPosition(position, currentPrice, market);
  if (!read) return { kind: "noPrice" };
  const rounded = Math.round(read.pnl * 100) / 100;
  return { kind: "live", read, mood: rounded > 0 ? "winning" : rounded < 0 ? "losing" : "even", near: read.roomFraction < NEAR_WIPEOUT_FRACTION };
}
