import { useClock } from "../api/useClock";
import { useMarkets } from "../api/useMarkets";
import type { EpochMs } from "../api/contract";

/** The estimate of the offset is `serverTimeMs` minus the client clock at the middle of one round trip,
 *  so it wobbles by a fraction of the round-trip time from poll to poll; rounding it to a quarter of a
 *  second keeps a countdown from skipping or repeating a second every time a new `markets()` lands. */
const OFFSET_STEP_MS = 250;

/**
 * "Now" on the SERVER's clock, ticking every `tickMs` — what every countdown to a server timestamp
 * (`MarketSeriesDto.bettingClosesAtMs`, `RoundWindowDto`, `MyPositionDto.untilMs`) must be read
 * against: a phone whose own clock is a minute off must not show "Closes in 02:41" for a round that
 * closes in 01:41 (`useMarkets().serverOffsetMs`, `api/useMarkets.ts`). 0 offset before the first
 * `markets()` response, and ~0 against the mock, whose clock is the client's.
 */
export function useServerNow(tickMs = 1000): EpochMs {
  const nowMs = useClock(tickMs);
  const { serverOffsetMs } = useMarkets();
  return nowMs + Math.round(serverOffsetMs / OFFSET_STEP_MS) * OFFSET_STEP_MS;
}
