/**
 * "While you were away" (task item, 2026-09-26 IA follow-up): on app open, catch up on any
 * settled positions the player never saw a result moment for — because the tab/app was closed
 * through settlement, not just backgrounded while `useSettlementWatcher.ts`'s poll kept running.
 * Uses the additive `Api.unseenResults()`/`acknowledgeResults()` RPCs (`contract.ts`'s own doc
 * comment) and falls back to doing nothing at all when either is absent — a `RealApi` build
 * talking to a server that predates them — rather than guessing which rounds were "away" from
 * client-only state.
 *
 * Since Р-69 the watcher polls the very same source for every market, so this one-shot is only the
 * fast first pass at sign-in (it does not wait for the watcher's first tick) and the two share
 * their dedupe set and their in-flight call (`unseenResults.ts`): whichever gets there first shows
 * the moments, the other finds nothing left to show.
 *
 * Runs once per sign-in (resets when `player` goes back to `null` on sign-out), not on every
 * render or poll tick.
 */
import { useEffect, useRef } from "react";
import { useApi, useSession } from "../api/ApiContext";
import { publishUnseenResults } from "./unseenResults";

export function useAwayReplay(): void {
  const api = useApi();
  const { player } = useSession();
  const hasRunRef = useRef(false);
  const playerId = player?.playerId ?? null;
  const currentPlayerId = useRef<string | null>(playerId);
  useEffect(() => {
    currentPlayerId.current = playerId;
  }, [playerId]);

  useEffect(() => {
    if (!playerId) {
      hasRunRef.current = false; // signed out — a later sign-in gets its own fresh catch-up
      return;
    }
    if (hasRunRef.current) return;
    hasRunRef.current = true;
    if (!api.unseenResults) return; // graceful fallback — this build/server doesn't have the RPC yet

    publishUnseenResults(api, playerId, () => currentPlayerId.current === playerId).catch((err) => {
      console.error("[useAwayReplay] unseenResults() failed", err);
    });
  }, [api, playerId]);
}
