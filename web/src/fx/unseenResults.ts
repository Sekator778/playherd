/**
 * Result moments from `Api.unseenResults()` — the single source for "a round of mine settled" in
 * ANY market (Р-69: a player has positions in many pairs at once, so no per-pair, per-series poll can
 * see them all). `useSettlementWatcher` calls {@link publishUnseenResults} every tick and
 * `useAwayReplay` once at sign-in; both go through here so that:
 *
 * - a result is put on screen once per `Api` instance, however many callers race for it
 *   ({@link shownResults} is the one dedupe set, `${roundId}:${playerId}` like `ResultEvent.id`; two
 *   calls in flight at the same time share one promise);
 * - it is acknowledged (`acknowledgeResults`) once shown — what makes it exactly once across
 *   reloads and devices: the server stops listing an acked round. A failed ack costs at most one
 *   repeat on the next open, never a lost moment, and is retried on the next tick while the server
 *   still lists the round.
 *
 * Several results in one poll (the same boundary settling three markets, or a catch-up after being
 * away) are published oldest first with their balances chained backwards from the current balance,
 * so the queue plays them in the order they happened and every WIN's coin animation stays
 * internally consistent.
 *
 * A liquidated position's settlement is the one exception to "one full moment per result": when the
 * player has already been shown that round's liquidation mid-round (`liquidationMoment.ts`), the settlement
 * goes out as a quiet summary (`ResultEvent.quiet`) — still acknowledged, still signed, not repeated loudly.
 */
import type { Api, ProfileRoundEntryDto, RoundId } from "../api/contract";
import { netFromPayoutAndStake } from "../api/outcome";
import { closeFollowUp, liquidationMomentShown, quietSummaryOf } from "./liquidationMoment";
import { resultEventFromHistoryEntry, type ResultEvent } from "./resultEvent";
import { publish } from "./resultEventBus";

const shownByApi = new WeakMap<Api, Set<string>>();

/** `${roundId}:${playerId}` of every result already shown with this `Api` instance (one set per
 *  instance, so a fake `Api` in a test starts clean). */
export function shownResults(api: Api): Set<string> {
  let shown = shownByApi.get(api);
  if (!shown) {
    shown = new Set();
    shownByApi.set(api, shown);
  }
  return shown;
}

/** Marks results as shown. Fire-and-forget: `Api.acknowledgeResults` is optional, and a failure is
 *  logged, not thrown (see the module header). */
export function ackResults(api: Api, roundIds: RoundId[]): void {
  if (roundIds.length === 0) return;
  api.acknowledgeResults?.(roundIds).catch((err) => {
    console.error(`[fx] acknowledgeResults(${roundIds.join(", ")}) failed — may replay on next open`, err);
  });
}

/**
 * Events for `unseen` (newest first, like `PublicProfileDto.history`), oldest first. Each entry's
 * `balanceAfter` is the running balance walked backwards from `currentBalance`; a liquidation never
 * moves the balance (the stake was debited at `placeBet`, not at settlement — result-moment.md §2),
 * so it consumes none of the chain.
 */
export function eventsFromUnseen(unseen: ProfileRoundEntryDto[], playerId: string, currentBalance: number): ResultEvent[] {
  let cursor = currentBalance;
  const events = unseen.map((entry) => {
    const net = netFromPayoutAndStake(entry.payout, entry.stake);
    const balanceAfter = cursor;
    const balanceBefore = entry.liquidated ? balanceAfter : balanceAfter - net;
    cursor = balanceBefore;
    return resultEventFromHistoryEntry(entry, playerId, { balanceBefore, balanceAfter });
  });
  return events.reverse();
}

const inFlight = new WeakMap<Api, Promise<void>>();

/**
 * One round of the single source: fetch the player's unseen results, publish what has not been shown
 * yet, acknowledge. Resolves (never rejects on a missing `unseenResults`; rejects if the fetch itself
 * fails — the caller logs). While a call is in flight for this `Api`, another call returns the same
 * promise instead of starting a second, racing one. `isCurrent` lets a caller drop the publish if
 * the player signed out or changed meanwhile.
 */
export function publishUnseenResults(api: Api, playerId: string, isCurrent: () => boolean = () => true): Promise<void> {
  const running = inFlight.get(api);
  if (running) return running;
  const run = doPublish(api, playerId, isCurrent).finally(() => {
    inFlight.delete(api);
  });
  inFlight.set(api, run);
  return run;
}

async function doPublish(api: Api, playerId: string, isCurrent: () => boolean): Promise<void> {
  if (!api.unseenResults) return;
  const unseen = await api.unseenResults();
  if (!unseen || unseen.length === 0 || !isCurrent()) return;

  const shown = shownResults(api);
  const key = (entry: ProfileRoundEntryDto) => `${entry.roundId}:${playerId}`;
  if (unseen.some((entry) => !shown.has(key(entry)))) {
    let balance = 0;
    try {
      balance = Number((await api.myAccount()).balance);
    } catch (err) {
      // Balance unavailable — WIN's coin-fly still runs, just without a meaningful before/after
      // number to animate between; LOSS/EVEN/LIQUIDATION don't read balance at all
      // (`ResultCard.tsx`), so this never blocks the moment itself from showing.
      console.error("[fx] myAccount() failed, showing results without exact balances", err);
    }
    if (!isCurrent()) return;
    // Re-check after the await: nothing else can have shown them (one call at a time per Api), but
    // the cost of being wrong is a duplicated moment.
    const fresh = unseen.filter((entry) => !shown.has(key(entry)));
    for (const event of eventsFromUnseen(fresh, playerId, balance)) {
      shown.add(event.id);
      // A liquidation the player was already shown while its round was running (`useLiquidationWatcher`) is not
      // flashed, stamped and shaken a second time at settlement: a quiet summary says it ended, the loss signed.
      if (event.kind === "LIQUIDATION" && liquidationMomentShown(playerId, event.roundId)) {
        publish(quietSummaryOf(event));
        // The round settled — the liquidation stood, so there is no cancellation left to follow up (`liquidationFollowUp.ts`).
        void closeFollowUp(playerId, event.roundId);
      } else {
        publish(event);
      }
    }
  }
  // Everything the server still lists is on screen now (or was, in an earlier tick whose ack failed).
  ackResults(
    api,
    unseen.map((entry) => entry.roundId),
  );
}
