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
 */
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
              }
            } catch {
              pendingSet.delete(roundId); // round vanished/errored — stop chasing it
            }
          }
        }
      })
      .catch(() => {});
  }, [api, player]);

  usePoll(tick, POLL_MS, [tick]);
}
