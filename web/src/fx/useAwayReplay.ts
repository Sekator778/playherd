/**
 * "While you were away" (task item, 2026-09-26 IA follow-up): on app open, catch up on any
 * settled positions the player never saw a result moment for — because the tab/app was closed
 * through settlement, not just backgrounded while `useSettlementWatcher.ts`'s poll kept running.
 * Uses the additive `Api.unseenResults()`/`acknowledgeResults()` RPCs (`contract.ts`'s own doc
 * comment) and falls back to doing nothing at all when either is absent — a `RealApi` build
 * talking to a server that predates them, or (before that lands) `MockApi` itself if it's ever
 * rolled back — rather than guessing which rounds were "away" from client-only state.
 *
 * Runs once per sign-in (resets when `player` goes back to `null` on sign-out), not on every
 * render or poll tick — this is a one-shot catch-up, not a recurring watcher (that's
 * `useSettlementWatcher`'s job, which keeps firing moments for anything that settles while the
 * app stays open).
 */
import { useEffect, useRef } from "react";
import { useApi, useSession } from "../api/ApiContext";
import { netFromPayoutAndStake } from "../api/outcome";
import { publish } from "./resultEventBus";
import { resultEventFromHistoryEntry } from "./resultEvent";

export function useAwayReplay(): void {
  const api = useApi();
  const { player } = useSession();
  const hasRunRef = useRef(false);

  useEffect(() => {
    if (!player) {
      hasRunRef.current = false; // signed out — a later sign-in gets its own fresh catch-up
      return;
    }
    if (hasRunRef.current) return;
    hasRunRef.current = true;

    const fetchUnseen = api.unseenResults;
    if (!fetchUnseen) return; // graceful fallback — this build/server doesn't have the RPC yet

    let cancelled = false;
    const playerId = player.playerId;

    (async () => {
      let unseen;
      try {
        unseen = await fetchUnseen.call(api);
      } catch (err) {
        console.error("[useAwayReplay] unseenResults() failed", err);
        return;
      }
      if (cancelled || !unseen || unseen.length === 0) return;

      let finalBalance = 0;
      try {
        finalBalance = Number((await api.myAccount()).balance);
      } catch (err) {
        // Balance unavailable — WIN's coin-fly still runs, just without a meaningful before/after
        // number to animate between; LOSS/EVEN/LIQUIDATION don't read balance at all
        // (`ResultCard.tsx`), so this never blocks the moment itself from showing.
        console.error("[useAwayReplay] myAccount() failed, replaying without exact balances", err);
      }
      if (cancelled) return;

      // `unseen` is newest-first (same convention as `myProfile().history`) — chain
      // balanceAfter/Before backwards from the player's CURRENT real balance so the queue plays
      // oldest-first while every WIN's balance animation stays internally consistent (nothing
      // fabricated: this is the same total that already happened, just replayed in order).
      // Liquidation never moves the running balance (the stake was already debited at placeBet,
      // not at settlement — result-moment.md §2), so it doesn't consume any of the chain.
      let cursor = finalBalance;
      const events = unseen.map((entry) => {
        const net = netFromPayoutAndStake(entry.payout, entry.stake);
        const balanceAfter = cursor;
        const balanceBefore = entry.liquidated ? balanceAfter : balanceAfter - net;
        const event = resultEventFromHistoryEntry(entry, playerId, { balanceBefore, balanceAfter });
        cursor = balanceBefore;
        return event;
      });
      events.reverse(); // oldest -> newest, FIFO order for ResultQueue (result-moment.md §1)

      for (const event of events) publish(event);

      api.acknowledgeResults?.(unseen.map((e) => e.roundId)).catch((err) => {
        console.error("[useAwayReplay] acknowledgeResults() failed — may replay again next open", err);
      });
    })();

    return () => {
      cancelled = true;
    };
  }, [api, player]);
}
