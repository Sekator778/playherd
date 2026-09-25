import { useEffect, useState } from "react";
import { simNowMs } from "./clock";
import type { EpochMs } from "./contract";

/** Re-renders the calling component every `tickMs` (default 1s of real time — with `SIM_SPEED`
 *  applied, that is enough for a countdown to visibly move) and returns the current simulated
 *  time. This is the only thing that makes round phases/timers advance on screen — everything
 *  else in the mock is a pure function of the timestamp this hook hands out. */
export function useClock(tickMs = 1000): EpochMs {
  const [now, setNow] = useState<EpochMs>(() => simNowMs());

  useEffect(() => {
    const id = window.setInterval(() => setNow(simNowMs()), tickMs);
    return () => window.clearInterval(id);
  }, [tickMs]);

  return now;
}
