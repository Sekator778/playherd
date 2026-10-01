import { useEffect, useState } from "react";
import { API_MODE } from "./apiMode";
import { simNowMs } from "./clock";
import type { EpochMs } from "./contract";

/** `MockApi` plays back a simulated, accelerated clock (`simNowMs`); `RealApi` talks to an
 *  actual server whose round windows already move at real wall-clock speed (accelerated test
 *  durations, Р-46, are the *server's* doing, not the client's) — so in real mode this is just
 *  `Date.now()`. Exported (non-hook) for code that has to compare the client clock with a
 *  server timestamp: `useMarkets().serverOffsetMs` is `serverTimeMs` minus this at receipt, so
 *  the server's "now" is `clientNowMs() + serverOffsetMs`. */
export function clientNowMs(): EpochMs {
  return API_MODE === "real" ? Date.now() : simNowMs();
}

/** Re-renders the calling component every `tickMs` (default 1s of real time) and returns the
 *  current time — purely for local, no-network display (countdown timers): it never triggers a
 *  data refetch by itself. Data polling (rounds/home/serverInfo) is `usePoll`'s job, on its own,
 *  slower cadence — see `usePoll.ts`. */
export function useClock(tickMs = 1000): EpochMs {
  const [now, setNow] = useState<EpochMs>(() => clientNowMs());

  useEffect(() => {
    const id = window.setInterval(() => setNow(clientNowMs()), tickMs);
    return () => window.clearInterval(id);
  }, [tickMs]);

  return now;
}
