import { useCallback, useState } from "react";
import { useApi } from "./ApiContext";
import { usePoll } from "./usePoll";
import type { MarketReferenceDto } from "./contract";

/** Polls `btcReferencePrice()` (already used for the bet slip's liquidation preview) so a
 *  RUNNING round can show live unrealized P&L / distance-to-liquidation for the own position and
 *  the crowd account, updating on every poll tick like the rest of the round page. */
export function useReferencePrice(intervalMs: number): MarketReferenceDto | null {
  const api = useApi();
  const [reference, setReference] = useState<MarketReferenceDto | null>(null);

  const load = useCallback(() => {
    api.btcReferencePrice().then(setReference).catch(() => {});
  }, [api]);

  usePoll(load, intervalMs, [load]);
  return reference;
}
