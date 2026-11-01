import { useEffect, useState } from "react";
import { useApi } from "../api/ApiContext";
import { apiErrorCode } from "../api/apiErrors";
import { LEVERAGE_MIN, type Direction, type Interval, type LiquidationPreviewDto, type MarketId } from "../api/contract";

export interface LiquidationPreviewState {
  /** The answer for the bet as composed; `null` while there is none (an invalid stake, a leverage the stake does not allow, a failure). */
  preview: LiquidationPreviewDto | null;
  /** The highest leverage the market allows for this stake and round length, once the server has said (`null` before it, and for a
   *  server or mock that does not say). The caller keeps its leverage within it. */
  stakeMax: number | null;
}

/** What the hook gives back: the state, and the leverage the question was asked at (the one given, lowered to `stakeMax`). */
export interface LiquidationPreviewResult extends LiquidationPreviewState {
  leverage: number;
}

/**
 * The liquidation preview of the bet being composed (D-7, D-33: shown BEFORE the bet), with the economy's numbers when it names the
 * round length (`interval`): the fees, the total debit and the highest leverage for this stake (economy stage C, C1). Debounced, and
 * an answer that arrives after a newer question was asked is dropped, not shown.
 *
 * The server refuses a leverage the stake does not allow (`INVALID_LEVERAGE`) instead of answering: then the question is asked
 * once more at the minimum leverage, which is always allowed, only to learn `stakeMax` — the caller lowers its leverage to it, and
 * the bet is previewed again at the new leverage.
 *
 * `enabled: false` asks nothing (the high-leverage confirmation is not open).
 */
export function useLiquidationPreview(
  marketId: MarketId,
  direction: Direction,
  leverage: number,
  stake: string,
  interval?: Interval,
  enabled = true,
): LiquidationPreviewResult {
  const api = useApi();
  const [state, setState] = useState<LiquidationPreviewState>({ preview: null, stakeMax: null });
  // What the server last said the stake allows. Part of the request (never ask for more), so a smaller stake that raises it re-asks.
  const stakeMax = state.stakeMax;
  const asked = stakeMax === null ? leverage : Math.min(leverage, stakeMax);
  useEffect(() => {
    if (!enabled) return;
    let cancelled = false;
    const stakeNum = Number(stake);
    const validStake = stake !== "" && Number.isFinite(stakeNum) && stakeNum > 0;
    const timer = window.setTimeout(() => {
      if (!validStake) {
        setState((s) => ({ ...s, preview: null }));
        return;
      }
      const request = (lev: number) => api.previewLiquidation({ marketId, direction, leverage: lev, stake: stakeNum.toFixed(3), interval });
      request(asked)
        .then((p) => {
          if (!cancelled) setState({ preview: p, stakeMax: p.maxLeverageForStake ?? null });
        })
        .catch(async (err: unknown) => {
          if (apiErrorCode(err) === "INVALID_LEVERAGE" && interval && asked > LEVERAGE_MIN) {
            try {
              const floor = await request(LEVERAGE_MIN);
              if (!cancelled) setState({ preview: null, stakeMax: floor.maxLeverageForStake ?? null });
              return;
            } catch {
              // fall through: no preview
            }
          }
          if (!cancelled) setState((s) => ({ ...s, preview: null }));
        });
    }, 150);
    return () => {
      cancelled = true;
      window.clearTimeout(timer);
    };
  }, [api, marketId, direction, asked, stake, interval, enabled]);
  return { ...state, leverage: asked };
}
