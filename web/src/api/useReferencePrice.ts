import { useCallback, useState } from "react";
import { useApi } from "./ApiContext";
import { usePoll } from "./usePoll";
import type { MarketId, MarketReferenceDto } from "./contract";

/** Polls `referencePrice(marketId)` (the same number the bet slip's liquidation preview starts
 *  from) so a RUNNING round can show live unrealized P&L / distance-to-liquidation for the own
 *  position and the crowd account, updating on every poll tick like the rest of the round page.
 *  Replaces the BTC-only `btcReferencePrice` hook; `null`/`undefined` (a screen that does not know
 *  its market yet, e.g. a round that is still loading) polls nothing and returns `null`. The
 *  price of a market the screen has since moved away from is never returned. */
export function useReferencePrice(marketId: MarketId | null | undefined, intervalMs: number): MarketReferenceDto | null {
  const api = useApi();
  const [state, setState] = useState<{ marketId: MarketId; reference: MarketReferenceDto } | null>(null);

  const load = useCallback(() => {
    if (!marketId) return;
    api
      .referencePrice(marketId)
      .then((reference) => setState({ marketId, reference }))
      .catch(() => {});
  }, [api, marketId]);

  usePoll(load, intervalMs, [load]);
  return state !== null && state.marketId === marketId ? state.reference : null;
}
