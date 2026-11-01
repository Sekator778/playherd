/**
 * The "heat" ramp shared by the leverage dial, the pre-bet liquidation bar and the live distance meter
 * (direction.md §1, live-round.md §1–§2): five tiers from calm to flame, and the zone table of how much
 * room is left before liquidation. Pure data and functions — the components that draw them
 * (`LeverageDial.tsx`, `LiquidationBar.tsx`) only export components.
 */
import { LEVERAGE_MAX } from "../api/contract";

export type HeatTier = 1 | 2 | 3 | 4 | 5;

/** The CSS custom property of each tier (index.css `--heat-1..5`). */
export const HEAT_VAR: Record<HeatTier, string> = {
  1: "var(--heat-1)",
  2: "var(--heat-2)",
  3: "var(--heat-3)",
  4: "var(--heat-4)",
  5: "var(--heat-5)",
};

/**
 * live-round.md §1's zone table, RELATIVE to the market's own maximum (D-69): the zones are fractions of
 * the cap — Calm up to 20 %, Warm up to 37.5 %, Hot up to 60 %, Danger up to 82.5 %, Flame above — so
 * "all-in on the dial" is Flame on every market. At a cap of x40 that is exactly the
 * table's x8 / x15 / x24 / x33 thresholds; on a market capped at x10 (an odds market) x2 is Calm and
 * x9–x10 is Flame, where absolute thresholds would have kept a x10 bet in "Warm" — the dial telling a
 * player a market's hottest setting is mild.
 */
export function leverageHeatTier(leverage: number, max: number = LEVERAGE_MAX): HeatTier {
  const fraction = leverage / max;
  if (fraction <= 0.2) return 1; // Calm
  if (fraction <= 0.375) return 2; // Warm
  if (fraction <= 0.6) return 3; // Hot
  if (fraction <= 0.825) return 4; // Danger
  return 5; // Flame
}

/** The zone of a 0..1 "room left" fraction — the same table for the pre-bet bar (room normalised
 *  against `LiquidationBar`'s own reference) and for a live position (room as a share of the distance it
 *  had at entry, `livePosition.ts`'s `liquidationRoomFraction`): Safe ≥ 50 %, Watch ≥ 25 %, Close ≥ 10 %,
 *  Critical below. */
export function liquidationZone(fraction: number): { tier: HeatTier; className: string } {
  if (fraction >= 0.5) return { tier: 2, className: "" };
  if (fraction >= 0.25) return { tier: 3, className: "liq-bar--watch" };
  if (fraction >= 0.1) return { tier: 4, className: "liq-bar--close" };
  return { tier: 5, className: "liq-bar--critical" };
}

/** Same zone table, as the class that pulses the whole card hosting a LIVE (RUNNING-phase) position —
 *  `live-round.md` §2: "the card gets... a pulse". Takes the room left as a 0..1 share of the distance the
 *  position had at ENTRY (not the raw distance in % of the price: a x40 position (an example; caps differ per market) starts only ~2.4 % from
 *  liquidation, which read as "critical" from the first second). `""` for the Safe zone (no card-level
 *  pulse there, matching the bar's own "static" row). */
export function liquidationLiveCardClass(roomFraction: number): string {
  const { className } = liquidationZone(Math.max(0, Math.min(1, roomFraction)));
  if (className === "liq-bar--watch") return "card--pulse-watch";
  if (className === "liq-bar--close") return "card--pulse-close";
  if (className === "liq-bar--critical") return "card--pulse-critical";
  return "";
}
