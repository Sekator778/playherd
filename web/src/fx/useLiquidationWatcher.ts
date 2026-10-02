import { useEffect, useRef } from "react";
import { useApi, useSession } from "../api/ApiContext";
import { useMyPositions } from "../api/useMyPositions";
import { claimLiquidationMoment, liquidationMomentEvent } from "./liquidationMoment";
import { publish } from "./resultEventBus";

/**
 * The mid-round LIQUIDATION moment, back (Р-50). It was lost when result moments moved to
 * `unseenResults()` (`useSettlementWatcher.ts`): that source lists SETTLED positions, so a liquidated one was
 * only announced when its whole round ended — minutes later on a 15-minute series, a day later on a long one.
 * `myPositions()` already says it at once (`MyPositionDto.liquidated`, which the "Your bets" strip shows), so
 * this watches that: the first time a RUNNING position of the signed-in player is reported liquidated, the
 * moment fires — the stake is lost, the round is still running — and never again for that round
 * (`liquidationMoment.ts`: remembered across reloads and tabs). When the round settles, its settlement is a
 * quiet summary (`unseenResults.ts`).
 *
 * It reads the shared positions poll (`useMyPositions`, one request per 2 s however many components read it), so
 * it adds no request of its own, and while signed out that poll does not run at all. An `Api` without
 * `unseenResults` is the legacy, BTC-only server whose `home()`-based detector (`useSettlementWatcher.ts`)
 * already fires the mid-round moment itself; this stays out of its way rather than doubling it.
 */
export function useLiquidationWatcher(): void {
  const api = useApi();
  const { player } = useSession();
  const { positions } = useMyPositions();
  const playerId = player?.playerId ?? null;
  const marketAware = typeof api.unseenResults === "function";
  /** `${playerId}:${roundId}` this page has already looked at — one claim attempt per round per page. */
  const handled = useRef(new Set<string>());

  useEffect(() => {
    if (!playerId || !marketAware) return;
    for (const position of positions) {
      if (position.phase !== "RUNNING" || !position.liquidated) continue;
      const key = `${playerId}:${position.roundId}`;
      if (handled.current.has(key)) continue;
      handled.current.add(key);
      // Another tab, or this one before a reload, may already have told the player.
      if (!claimLiquidationMoment(playerId, position.roundId)) continue;
      publish(liquidationMomentEvent(position, playerId, Date.now()));
    }
  }, [playerId, marketAware, positions]);
}
