/**
 * The betting lines drawn over the room's price chart: the round's open price, the player's entry and
 * liquidation levels, and the herd's. Pure functions, no React. (The chart's own maths lives in `chart/`:
 * `series.ts` data, `frame.ts` geometry, `move.ts` the move since open.)
 */
import { tEn, type TFn } from "../i18n";

// ---------------------------------------------------------------------------
// The horizontal lines: round open, the player's entry/liquidation, the herd's
// ---------------------------------------------------------------------------

export interface PositionLinesInput {
  direction: "UP" | "DOWN";
  leverage: number;
  entryPrice: string;
  /** Null for a fixed-payout position ("Win or lose", D-106): it has no wipeout line. */
  liquidationPrice: string | null;
  mode?: "PERCENTAGE" | "FIXED" | null;
}

export interface ChartLine {
  id: "open" | "own-entry" | "own-liq" | "crowd-entry" | "crowd-liq";
  /** Short text printed on the line (the price follows it). */
  label: string;
  price: number;
  tone: "open" | "entry" | "liq";
  /** The herd's lines are drawn fainter than the player's own. */
  faint: boolean;
}

const SAME_PRICE = 1e-9;
const sameLevel = (a: number, b: number) => Math.abs(a - b) <= SAME_PRICE * Math.max(1, Math.abs(a));
const glyph = (direction: "UP" | "DOWN") => (direction === "UP" ? "▲" : "▼");

/**
 * The lines to draw. Every position opens at the round's open price (D-8), so the open line and the
 * player's entry — and the herd's — are normally one and the same level: drawn once, the label saying
 * what it is for the player. The liquidation lines are the informative ones: the player's own (solid)
 * and the herd's (the same colour, fainter).
 */
export function buildChartLines(
  input: { openPrice?: string | null; own?: PositionLinesInput | null; crowd?: PositionLinesInput | null },
  t: TFn = tEn,
): ChartLine[] {
  const lines: ChartLine[] = [];
  const open = input.openPrice != null ? Number(input.openPrice) : null;
  const openOk = open !== null && Number.isFinite(open);
  const own = input.own ?? null;
  const crowd = input.crowd ?? null;
  const ownEntry = own ? Number(own.entryPrice) : null;
  const crowdEntry = crowd ? Number(crowd.entryPrice) : null;

  if (openOk) {
    const entryIsOpen = own !== null && ownEntry !== null && sameLevel(ownEntry, open);
    lines.push({
      id: "open",
      label: entryIsOpen ? (own.mode === "FIXED" ? t("mode.openEntry", { glyph: glyph(own.direction) }) : t("lines.openEntry", { glyph: glyph(own.direction), leverage: own.leverage })) : t("lines.open"),
      price: open,
      tone: entryIsOpen ? "entry" : "open",
      faint: false,
    });
  }
  if (own && ownEntry !== null && Number.isFinite(ownEntry) && !(openOk && sameLevel(ownEntry, open))) {
    lines.push({ id: "own-entry", label: own.mode === "FIXED" ? t("mode.yourEntry", { glyph: glyph(own.direction) }) : t("lines.yourEntry", { glyph: glyph(own.direction), leverage: own.leverage }), price: ownEntry, tone: "entry", faint: false });
  }
  if (own && own.liquidationPrice !== null) {
    const liq = Number(own.liquidationPrice);
    if (Number.isFinite(liq)) lines.push({ id: "own-liq", label: t("lines.yourLiq"), price: liq, tone: "liq", faint: false });
  }
  if (crowd && crowdEntry !== null && Number.isFinite(crowdEntry)) {
    const mergedIntoOpen = openOk && sameLevel(crowdEntry, open);
    const mergedIntoOwn = ownEntry !== null && sameLevel(crowdEntry, ownEntry);
    if (!mergedIntoOpen && !mergedIntoOwn) {
      lines.push({ id: "crowd-entry", label: t("lines.herdEntry", { glyph: glyph(crowd.direction), leverage: crowd.leverage }), price: crowdEntry, tone: "entry", faint: true });
    }
  }
  if (crowd) {
    const liq = Number(crowd.liquidationPrice);
    if (Number.isFinite(liq)) lines.push({ id: "crowd-liq", label: t("lines.herdLiq", { glyph: glyph(crowd.direction), leverage: crowd.leverage }), price: liq, tone: "liq", faint: true });
  }
  return lines;
}
