/**
 * Live reading of a RUNNING position — unrealized P&L and how much room is left before liquidation —
 * from what the DTOs carry. Pure functions over `api/liquidation.ts`'s exact math, so the room, the round
 * page and the positions strip all say the same number for the same position.
 */
import type { Direction, MyPositionDto } from "../api/contract";
import { LONG, SHORT, percentToLiquidation, sizeForMargin, unrealizedPnl, type MarketParams } from "../api/liquidation";

/**
 * The room left before liquidation as a 0..1 share of the distance the position had at entry: 1 at (or
 * past, in the player's favour) the entry price, 0 at the liquidation price — what live-round.md §2's
 * zones (Safe / Watch / Close / Critical) are defined on. A degenerate position (no distance between entry
 * and liquidation) reads as 1 rather than dividing by zero.
 */
export function liquidationRoomFraction(
  entryPrice: string | number,
  liquidationPrice: string | number,
  price: string | number,
  direction: Direction,
): number {
  const entry = Number(entryPrice);
  const liq = Number(liquidationPrice);
  const now = Number(price);
  const distance = direction === "UP" ? entry - liq : liq - entry;
  if (!Number.isFinite(distance) || distance <= 0 || !Number.isFinite(now)) return 1;
  const room = direction === "UP" ? now - liq : liq - now;
  return Math.max(0, Math.min(1, room / distance));
}

export interface LiveRead {
  /** Unrealized P&L in PLAY (money scale, signed). */
  pnl: number;
  /** See {@link liquidationRoomFraction}. */
  roomFraction: number;
  /** How far the price still has to move against the position to liquidate it, as a fraction of the price. */
  pctToLiquidation: number;
}

/** The live numbers of a position whose `size` is known (`PublicPositionDto`, a round's own position). */
export function liveRead(
  position: { size: string; entryPrice: string; liquidationPrice: string; direction: Direction },
  currentPrice: string,
  market: MarketParams,
): LiveRead {
  const side = position.direction === "UP" ? LONG : SHORT;
  return {
    pnl: unrealizedPnl(position.size, position.entryPrice, side, currentPrice, market).toNumber(),
    roomFraction: liquidationRoomFraction(position.entryPrice, position.liquidationPrice, currentPrice, position.direction),
    pctToLiquidation: percentToLiquidation(currentPrice, position.liquidationPrice),
  };
}

/** A RUNNING position whose round has no opening price yet: `myPositions()` sends `entryPrice` and
 *  `liquidationPrice` as null. Nothing can be read of it — not a P&L, not a distance to liquidation. */
export function isWaitingForOpen(position: Pick<MyPositionDto, "phase" | "entryPrice">): boolean {
  return position.phase === "RUNNING" && position.entryPrice === null;
}

/** The size the SERVER opened the position with (`MyPositionDto.size`, lots at 8 dp), when it sent a usable
 *  one: a positive decimal. Absent / null / garbage → `null`, and the caller derives it from the stake. */
export function serverSizeOf(position: Pick<MyPositionDto, "size">): string | null {
  const size = position.size;
  if (typeof size !== "string" || size.trim() === "") return null;
  const n = Number(size);
  return Number.isFinite(n) && n > 0 ? size : null;
}

/**
 * The live numbers of one of the player's own RUNNING positions as `myPositions()` reports it. The size is the
 * server's (`MyPositionDto.size`) when it sends one — the position it really opened; for a server that does not,
 * the client recomputes it from the stake the way the server sizes a position (`sizeForMargin`). `null` when
 * there is nothing to read yet — a bet still waiting for its round to start, a round with no opening price yet
 * (no entry price: {@link isWaitingForOpen}), a liquidated position, no current price, or a market whose
 * parameters have not arrived.
 */
export function liveReadOfPosition(position: MyPositionDto, currentPrice: string | null | undefined, market: MarketParams | undefined): LiveRead | null {
  if (position.phase !== "RUNNING" || position.liquidated || !market || !currentPrice) return null;
  if (position.entryPrice === null || position.liquidationPrice === null) return null;
  const size = serverSizeOf(position) ?? sizeForMargin(position.stake, position.leverage, position.entryPrice, market).toFixed(8);
  return liveRead({ size, entryPrice: position.entryPrice, liquidationPrice: position.liquidationPrice, direction: position.direction }, currentPrice, market);
}
