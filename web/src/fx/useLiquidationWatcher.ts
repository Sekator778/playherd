import { useEffect, useRef, useState } from "react";
import { useApi, useSession } from "../api/ApiContext";
import { useMyPositions } from "../api/useMyPositions";
import { followUpLiquidation } from "./liquidationFollowUp";
import { claimLiquidationMoment, liquidationInfoOf, liquidationMomentEvent, pendingFollowUps } from "./liquidationMoment";
import { publish } from "./resultEventBus";

/** How long to wait before asking again about a round whose fate was not known yet. */
export const FOLLOW_UP_RETRY_MS = 10_000;
/** How many times this page asks about one round before leaving it to the next page load. */
const MAX_FOLLOW_UP_ATTEMPTS = 6;

/**
 * The mid-round LIQUIDATION moment, back (D-50). It was lost when result moments moved to
 * `unseenResults()` (`useSettlementWatcher.ts`): that source lists SETTLED positions, so a liquidated one was
 * only announced when its whole round ended — minutes later on a 15-minute series, a day later on a long one.
 * `myPositions()` already says it at once (`MyPositionDto.liquidated`, which the "Your bets" strip shows), so
 * this watches that: the first time a RUNNING position of the signed-in player is reported liquidated, the
 * moment fires — the stake is lost, the round is still running — and never again for that round
 * (`liquidationMoment.ts`: remembered across reloads and tabs). When the round settles, its settlement is a
 * quiet summary (`unseenResults.ts`).
 *
 * And when the round is CANCELLED instead, the stake comes back (D-23) — which the moment said might happen, but a
 * cancelled round leaves no history row and no other trace on screen: once an announced liquidation's position has
 * left `myPositions()`, the round is asked what became of it, and a cancellation gets one quiet note
 * (`liquidationFollowUp.ts`) — also for a player who was away when it happened (the claim outlives the page).
 *
 * It reads the shared positions poll (`useMyPositions`, one request per 2 s however many components read it), so
 * it adds no request of its own, and while signed out that poll does not run at all. An `Api` without
 * `unseenResults` is the legacy, BTC-only server whose `home()`-based detector (`useSettlementWatcher.ts`)
 * already fires the mid-round moment itself; this stays out of its way rather than doubling it.
 */
export function useLiquidationWatcher(): void {
  const api = useApi();
  const { player } = useSession();
  const { positions, loading } = useMyPositions();
  const playerId = player?.playerId ?? null;
  const marketAware = typeof api.unseenResults === "function";
  /** `${playerId}:${roundId}` this page has already looked at — one claim attempt per round per page. */
  const handled = useRef(new Set<string>());
  /** The player the watcher is for NOW: a claim that resolves after a sign-out must not announce to the next player. */
  const currentPlayer = useRef(playerId);
  useEffect(() => {
    currentPlayer.current = playerId;
  });

  useEffect(() => {
    if (!playerId || !marketAware) return;
    for (const position of positions) {
      if (position.phase !== "RUNNING" || !position.liquidated) continue;
      const key = `${playerId}:${position.roundId}`;
      if (handled.current.has(key)) continue;
      handled.current.add(key);
      // Another tab, or this one before a reload, may already have told the player — the claim is the one to decide.
      void claimLiquidationMoment(playerId, position.roundId, liquidationInfoOf(position)).then((won) => {
        if (won && currentPlayer.current === playerId) publish(liquidationMomentEvent(position, playerId, Date.now()));
      });
    }
  }, [playerId, marketAware, positions]);

  // The follow-up: announced liquidations whose position is no longer among the player's own.
  const [recheck, setRecheck] = useState(0);
  const attempts = useRef(new Map<string, number>());
  const inFlight = useRef(new Set<string>());
  useEffect(() => {
    // Only once the positions are KNOWN: before that every position looks gone.
    if (!playerId || !marketAware || loading) return;
    const stillOpen = new Set(positions.map((p) => p.roundId));
    const pending = pendingFollowUps(playerId).filter(
      (p) => !stillOpen.has(p.roundId) && !inFlight.current.has(p.roundId) && (attempts.current.get(p.roundId) ?? 0) < MAX_FOLLOW_UP_ATTEMPTS,
    );
    if (pending.length === 0) return;
    let unmounted = false;
    let retry: number | undefined;
    for (const item of pending) {
      inFlight.current.add(item.roundId);
      void followUpLiquidation(api, playerId, item)
        .then((outcome) => {
          if (outcome === "later") {
            attempts.current.set(item.roundId, (attempts.current.get(item.roundId) ?? 0) + 1);
            if (!unmounted && retry === undefined) retry = window.setTimeout(() => setRecheck((n) => n + 1), FOLLOW_UP_RETRY_MS);
          }
        })
        .finally(() => {
          inFlight.current.delete(item.roundId);
        });
    }
    return () => {
      unmounted = true;
      window.clearTimeout(retry);
    };
  }, [api, playerId, marketAware, loading, positions, recheck]);
}
