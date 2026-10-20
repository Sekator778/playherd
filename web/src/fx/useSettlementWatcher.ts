import { useCallback, useEffect, useRef } from "react";
import { useApi, useSession } from "../api/ApiContext";
import { usePoll } from "../api/usePoll";
import type { Api, Interval, RoundDto, RoundId } from "../api/contract";
import { detectResultEvent, roundHasPlayerStake } from "./resultEvent";
import { publish } from "./resultEventBus";
import { ackResults, publishUnseenResults, shownResults } from "./unseenResults";

const POLL_MS = 2000;
const INTERVALS: Interval[] = ["HOURLY", "DAILY"];

/**
 * Global settlement-event detector (`implementation-plan.md` §3) — its own poll, independent of
 * whichever page is mounted, so a moment fires "видно с любой страницы" (`game-designer.md`) even
 * from `/wallet` or `/ratings`.
 *
 * Market-agnostic since Р-69: a player has bets in many pairs at once, so the watcher no longer
 * reads `home()`/`rounds()` of one pair. It polls `Api.unseenResults()` — the player's settled
 * positions in ANY market that no result moment has been shown for yet — publishes a moment for
 * each (the event carries the market's symbol), and acknowledges them (`acknowledgeResults`), which
 * is also what makes a result fire exactly once across reloads and devices. That path lives in
 * `unseenResults.ts` and is shared with `useAwayReplay.ts`, so the two never double-fire.
 *
 * Moments therefore arrive at SETTLEMENT, for liquidated positions too (a liquidated round has a
 * history row like any other, payout 0). The mid-round LIQUIDATION moment — the instant a running position
 * is reported liquidated — is not this hook's any more: it needs the player's running positions, not the
 * settled ones, and lives in `useLiquidationWatcher.ts` (from `myPositions()`, once, across reloads and
 * tabs). When that moment was already shown, this path turns the liquidated round's settlement into a quiet
 * summary instead of a second flash (`unseenResults.ts`, `liquidationMoment.ts`).
 *
 * Legacy fallback — only for an `Api` that lacks `unseenResults` (a server or build that predates
 * it): the original `home()`-based detector, BTC only, kept as it was. Deviates from
 * `implementation-plan.md` §3's literal algorithm in one place, corrected against `mockApi.ts`'s
 * behavior (this project's "правила фактов" — checked against the source, not assumed): that plan
 * reads `home()`'s `.running` round straight through BETTING→RUNNING→SETTLED. In the mock,
 * `.running` is "the window containing now" — so it is always phase `"RUNNING"` and never itself
 * reaches `"SETTLED"`; the round's identity simply rolls over to the next window once `now` passes
 * its end. A round only ever reports `"SETTLED"` through a direct `api.round(roundId)` call
 * (`RoundPage`'s own poll already relies on exactly this). So the fallback also tracks, per
 * interval, any roundId that just rolled out of `.running` while the player still had a stake in
 * it, and follows up with a direct `round()` call each tick until it resolves. This is a strict
 * superset of the plan's assumption: if a real backend DOES show `.running` reaching `"SETTLED"`
 * before rolling over, the primary path fires first and the follow-up never triggers for that
 * round (its dedup key is already handled by the time it would look at it).
 *
 * Wave2 fix (Alex, 2026-09-26, fallback path): the moment fired only sometimes for a desktop player
 * who stayed on a round page through settlement. Root cause: the fallback's `catch` block treated
 * *any* failure of the direct `api.round(roundId)` chase — including a transient network hiccup or
 * a momentary server error — the same as "this round doesn't exist any more", and gave up on it
 * permanently after a single failed attempt, deleting it from `pending` so it was never retried. A
 * rolled-over round the player had a stake in is never actually gone (the real backend's atomic
 * resolve-then-advance step — `RoundSchedulerService.resolveOverdueRounds` — guarantees it has
 * already settled by the time it rolls out of `.running`), so one failed fetch is not a reason to
 * stop looking for it. Fixed by retrying a failed chase on later ticks instead of abandoning it on
 * the first error, bounded (`MAX_CHASE_ATTEMPTS`) only to avoid chasing a truly bogus id forever,
 * and logging every failure (`console.error`) instead of swallowing it.
 */

/** How many consecutive failed `api.round(roundId)` attempts the fallback tolerates for one
 *  pending round before giving up on it — generous (≈20s at `POLL_MS`) because a rolled-over
 *  round the player had a stake in always does resolve eventually; this only guards against
 *  chasing a genuinely bogus id forever. */
const MAX_CHASE_ATTEMPTS = 10;

export function useSettlementWatcher(): void {
  const api = useApi();
  const { player } = useSession();
  const playerId = player?.playerId ?? null;
  // The player a tick was started for may sign out before its requests come back; its results must
  // not then be shown to whoever is (not) signed in. Refs may not be written during render
  // (react-hooks/refs), so it is kept current by an effect.
  const currentPlayerId = useRef<string | null>(playerId);
  useEffect(() => {
    currentPlayerId.current = playerId;
  }, [playerId]);

  // Legacy fallback state (unused while `api.unseenResults` exists).
  const prevRunning = useRef<Map<Interval, RoundDto>>(new Map());
  const pending = useRef<Map<Interval, Set<RoundId>>>(
    new Map([
      ["HOURLY", new Set<RoundId>()],
      ["DAILY", new Set<RoundId>()],
    ]),
  );
  /** Consecutive failed `api.round()` attempts per pending `roundId`, so a transient error costs
   *  one retry, not the whole round's result moment. */
  const failedAttempts = useRef<Map<RoundId, number>>(new Map());

  const tick = useCallback(() => {
    if (!playerId) return;
    if (api.unseenResults) {
      publishUnseenResults(api, playerId, () => currentPlayerId.current === playerId).catch((err) => {
        // A single failed poll costs one tick, not the moment itself — `usePoll` calls this again
        // in POLL_MS regardless — but it must not be invisible.
        console.error("[useSettlementWatcher] unseenResults poll failed", err);
      });
      return;
    }
    legacyTick(api, playerId, { prevRunning, pending, failedAttempts });
  }, [api, playerId]);

  usePoll(tick, POLL_MS, [tick], playerId ? ["me"] : undefined);
}

interface LegacyState {
  prevRunning: { current: Map<Interval, RoundDto> };
  pending: { current: Map<Interval, Set<RoundId>> };
  failedAttempts: { current: Map<RoundId, number> };
}

/** The pre-markets detector: BTC only, from `home()` + `myAccount()` (see the hook's header). */
function legacyTick(api: Api, playerId: string, state: LegacyState): void {
  const handled = shownResults(api);
  const { prevRunning, pending, failedAttempts } = state;

  Promise.all([api.home(), api.myAccount()])
    .then(async ([home, account]) => {
      const currentBalance = Number(account.balance);
      const nowMs = Date.now();

      for (const interval of INTERVALS) {
        const pair = interval === "HOURLY" ? home.hourly : home.daily;
        const curr = pair.running;
        const prev = prevRunning.current.get(interval);

        const primaryEvent = detectResultEvent({
          prev,
          curr,
          playerId,
          interval,
          alreadyEmitted: handled.has(`${curr.roundId}:${playerId}`),
          currentBalance,
          nowMs,
        });
        if (primaryEvent) {
          handled.add(primaryEvent.id);
          publish(primaryEvent);
          ackResults(api, [primaryEvent.roundId]);
        }

        // The running round rolled over to a new window — if the previous one still had our
        // stake in it and hasn't resolved yet, track it for a direct follow-up below.
        if (prev && prev.roundId !== curr.roundId) {
          const rolledKey = `${prev.roundId}:${playerId}`;
          if (roundHasPlayerStake(prev, playerId) && !handled.has(rolledKey)) {
            pending.current.get(interval)?.add(prev.roundId);
          }
        }
        prevRunning.current.set(interval, curr);

        const pendingSet = pending.current.get(interval);
        if (!pendingSet || pendingSet.size === 0) continue;
        for (const roundId of Array.from(pendingSet)) {
          const key = `${roundId}:${playerId}`;
          if (handled.has(key)) {
            pendingSet.delete(roundId);
            failedAttempts.current.delete(roundId);
            continue;
          }
          try {
            const resolved = await api.round(roundId);
            if (resolved.phase === "SETTLED" || resolved.phase === "CANCELLED") {
              const event = detectResultEvent({
                prev: undefined,
                curr: resolved,
                playerId,
                interval,
                alreadyEmitted: handled.has(key),
                currentBalance,
                nowMs,
              });
              if (event) {
                handled.add(event.id);
                publish(event);
                ackResults(api, [roundId]);
              }
              pendingSet.delete(roundId); // resolved either way — stop chasing it
              failedAttempts.current.delete(roundId);
            }
            // Still RUNNING/BETTING (a genuine, if unexpected, momentary state) — leave it in
            // `pendingSet` and try again next tick; not an error, so it doesn't count against
            // MAX_CHASE_ATTEMPTS.
          } catch (err) {
            const attempts = (failedAttempts.current.get(roundId) ?? 0) + 1;
            failedAttempts.current.set(roundId, attempts);
            console.error(
              `[useSettlementWatcher] api.round(${roundId}) failed (attempt ${attempts}/${MAX_CHASE_ATTEMPTS}) while chasing a settlement for ${interval}`,
              err,
            );
            if (attempts >= MAX_CHASE_ATTEMPTS) {
              pendingSet.delete(roundId); // genuinely gone (or a persistent bug) — stop chasing it
              failedAttempts.current.delete(roundId);
            }
            // Otherwise leave it in `pendingSet` — retried on the next tick instead of losing
            // this result moment to one bad request.
          }
        }
      }
    })
    .catch((err) => {
      // A single failed `home()`/`myAccount()` poll costs one tick, not the moment itself —
      // `usePoll` calls this again in POLL_MS regardless — but it must not be invisible.
      console.error("[useSettlementWatcher] poll tick failed", err);
    });
}
