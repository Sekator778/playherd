/**
 * Small pieces shared by all three /lab/concepts/{a,b,c} prototypes — kept here instead of
 * duplicated three times, but each concept still writes its own lobby/room/stats layout and CSS
 * (`ConceptA.tsx`/`ConceptB.tsx`/`ConceptC.tsx` + their `.css` files) so the three stay "genuinely
 * different" per the task, not the same screen re-skinned.
 */
import { useEffect, useRef } from "react";
import { useSearchParams } from "react-router-dom";
import { publish } from "../../../fx/resultEventBus";
import type { ResultEvent, ResultKind, Tier } from "../../../fx/resultEvent";

/** `?fire=WIN3` / `LOSS1` / `LIQ4` / `EVEN` on a room route fires that result moment on mount,
 *  through the exact same `resultEventBus` → `ResultMomentHost` pipeline the real app uses
 *  (`ResultMomentHost` is already mounted once in `Layout.tsx`, which every /lab/concepts/* route
 *  renders inside — task: "reuse fx for result moments"). Navigation-only: no click needed, so a
 *  screenshot tool can reach every tier by URL alone, the same way the phase/room screenshots are
 *  reached by URL. Fires at most once per mount (StrictMode-safe, same guard pattern
 *  `ResultCard.tsx`'s `playedRef` uses). */
const FIRE_PRESETS: Record<string, { kind: ResultKind; tier: Tier; stake: number; net: number; leverage: number }> = {
  WIN1: { kind: "WIN", tier: 1, stake: 100, net: 20, leverage: 8 },
  WIN2: { kind: "WIN", tier: 2, stake: 100, net: 100, leverage: 12 },
  WIN3: { kind: "WIN", tier: 3, stake: 100, net: 400, leverage: 18 },
  WIN4: { kind: "WIN", tier: 4, stake: 10345, net: 166.2, leverage: 14 },
  LOSS1: { kind: "LOSS", tier: 1, stake: 100, net: -3, leverage: 6 },
  LOSS2: { kind: "LOSS", tier: 2, stake: 100, net: -12, leverage: 10 },
  LOSS3: { kind: "LOSS", tier: 3, stake: 100, net: -30, leverage: 14 },
  LOSS4: { kind: "LOSS", tier: 4, stake: 9968, net: -114.39, leverage: 10 },
  LIQ1: { kind: "LIQUIDATION", tier: 1, stake: 100, net: -100, leverage: 5 },
  LIQ2: { kind: "LIQUIDATION", tier: 2, stake: 100, net: -100, leverage: 15 },
  LIQ3: { kind: "LIQUIDATION", tier: 3, stake: 100, net: -100, leverage: 25 },
  LIQ4: { kind: "LIQUIDATION", tier: 4, stake: 500, net: -500, leverage: 38 },
  EVEN: { kind: "EVEN", tier: 1, stake: 100, net: 0, leverage: 10 },
};

let fireSeq = 0;

export function useFireFromQuery(roundId: string): void {
  const [params] = useSearchParams();
  const firedRef = useRef(false);
  const code = params.get("fire");

  useEffect(() => {
    if (!code || firedRef.current) return;
    const preset = FIRE_PRESETS[code.toUpperCase()];
    if (!preset) return;
    firedRef.current = true;
    // `ResultMomentHost` (Layout.tsx) subscribes to `resultEventBus` in its OWN mount effect, and
    // on this route's very first render both it and this room component mount in the same commit
    // — `<Outlet/>` (this room) sits before `ResultMomentHost` in `Layout.tsx`'s JSX, so a
    // same-tick `publish()` here would fire before the host has subscribed and be silently
    // dropped (`resultEventBus.ts`'s `publish` is fire-and-forget, no buffering). A macrotask
    // delay sidesteps the mount-order race entirely — the real app never hits this because
    // `useSettlementWatcher`'s first `publish()` only ever happens after its own poll interval
    // elapses, long after every mount effect has already run.
    const t = window.setTimeout(() => {
      fireSeq += 1;
      const balanceAfter = 10_000;
      const event: ResultEvent = {
        id: `concept-${code}-${fireSeq}`,
        kind: preset.kind,
        roundId,
        interval: "HOURLY",
        netPlay: preset.net,
        stakePlay: preset.stake,
        leverage: preset.leverage,
        balanceBefore: balanceAfter - preset.net,
        balanceAfter,
        atMs: Date.now(),
        tier: preset.tier,
      };
      publish(event);
    }, 150);
    return () => window.clearTimeout(t);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [code]);
}

/** The one honest line every concept's room repeats verbatim next to the price (Р-33/`ux_spec.md`
 *  §2) — copied text, not a new disclosure. */
export function OracleLine() {
  return <p className="concept-oracle-line">Price from Hyperliquid, published by our team's key — not a decentralized oracle.</p>;
}

export function fmtStake(n: number): string {
  return n.toLocaleString("en-US", { maximumFractionDigits: 0 });
}

export function fmtPrice(n: number): string {
  return n.toLocaleString("en-US", { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}

export function fmtSigned(n: number, decimals = 2): string {
  const sign = n > 0 ? "+" : n < 0 ? "-" : "";
  return `${sign}${Math.abs(n).toLocaleString("en-US", { minimumFractionDigits: decimals, maximumFractionDigits: decimals })}`;
}
