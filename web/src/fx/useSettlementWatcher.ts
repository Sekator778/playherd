import { useCallback, useRef } from "react";
import { useApi, useSession } from "../api/ApiContext";
import { usePoll } from "../api/usePoll";
import type { Interval, RoundDto, RoundId } from "../api/contract";
import { detectResultEvent, roundHasPlayerStake } from "./resultEvent";
import { publish } from "./resultEventBus";

const POLL_MS = 2000;
const INTERVALS: Interval[] = ["HOURLY", "DAILY"];

/**
 * Global settlement-event detector (`implementation-plan.md` §3) — its own `api.home()` poll,
 * independent of whichever page is mounted, so a moment fires "видно с любой страницы"
 * (`game-designer.md`) even from `/wallet` or `/ratings`.
 *
 * Deviates from `implementation-plan.md` §3's literal algorithm in one place, corrected against
 * `mockApi.ts`'s actual behavior (this project's "правила фактов" — checked against the source,
 * not assumed): that plan reads `home()`'s `.running` round straight through
 * BETTING→RUNNING→SETTLED. In the mock, `.running` is `windowFor(interval, now)` — "the window
 * containing now" — so it is always phase `"RUNNING"` and never itself reaches `"SETTLED"`; the
 * round's identity simply rolls over to the next window once `now` passes its end. A round only
 * ever reports `"SETTLED"` through a direct `api.round(roundId)` call (`RoundPage`'s own poll
 * already relies on exactly this). So this hook also tracks, per interval, any roundId that just
 * rolled out of `.running` while the player still had a stake in it, and follows up with a direct
 * `round()` call each tick until it resolves. This is a strict superset of the plan's assumption:
 * if a real backend DOES show `.running` reaching `"SETTLED"` before rolling over, the primary
 * path below fires first and the fallback never triggers for that round (its dedup key is already
 * handled by the time the fallback would look at it).
 *
 * Wave2 fix (Alex, 2026-09-26): the moment fired only sometimes for a desktop player who stayed
 * on a round page through settlement. Root cause: the fallback's `catch` block treated *any*
 * failure of the direct `api.round(roundId)` chase — including a transient network hiccup or a
 * momentary server error — the same as "this round doesn't exist any more", and gave up on it
 * permanently after a single failed attempt, deleting it from `pending` so it was never retried.
 * A rolled-over round the player had a stake in is never actually gone (the frontend only ever
 * learned its id from the server's own `home()` response, and the real backend's atomic
 * resolve-then-advance step — `RoundSchedulerService.resolveOverdueRounds` — guarantees it has
 * already settled by the time it rolls out of `.running`), so one failed fetch is not a reason to
 * stop looking for it. Fixed by retrying a failed chase on later ticks instead of abandoning it
 * on the first error, bounded (`MAX_CHASE_ATTEMPTS`) only to avoid chasing a truly bogus id
 * forever, and logging every failure (`console.error`) instead of swallowing it — this file's own
 * `catch (() => {})` had no visibility at all before this fix, so a real failure here was
 * indistinguishable from "nothing happened yet".
 */

/** How many consecutive failed `api.round(roundId)` attempts the fallback tolerates for one
 *  pending round before giving up on it — generous (≈20s at `POLL_MS`) because a rolled-over
 *  round the player had a stake in always does resolve eventually; this only guards against
 *  chasing a genuinely bogus id forever. */
const MAX_CHASE_ATTEMPTS = 10;

export function useSettlementWatcher(): void {
  const api = useApi();
  const { player } = useSession();
  const prevRunning = useRef<Map<Interval, RoundDto>>(new Map());
  const handled = useRef<Set<string>>(new Set());
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
    if (!player) return;
    const playerId = player.playerId;

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
            alreadyEmitted: handled.current.has(`${curr.roundId}:${playerId}`),
            currentBalance,
            nowMs,
          });
          if (primaryEvent) {
            handled.current.add(primaryEvent.id);
            publish(primaryEvent);
          }

          // The running round rolled over to a new window — if the previous one still had our
          // stake in it and hasn't resolved yet, track it for a direct follow-up below.
          if (prev && prev.roundId !== curr.roundId) {
            const rolledKey = `${prev.roundId}:${playerId}`;
            if (roundHasPlayerStake(prev, playerId) && !handled.current.has(rolledKey)) {
              pending.current.get(interval)?.add(prev.roundId);
            }
          }
          prevRunning.current.set(interval, curr);

          const pendingSet = pending.current.get(interval);
          if (!pendingSet || pendingSet.size === 0) continue;
          for (const roundId of Array.from(pendingSet)) {
            const key = `${roundId}:${playerId}`;
            if (handled.current.has(key)) {
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
                  alreadyEmitted: handled.current.has(key),
                  currentBalance,
                  nowMs,
                });
                if (event) {
                  handled.current.add(event.id);
                  publish(event);
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
  }, [api, player]);

  usePoll(tick, POLL_MS, [tick]);
}
