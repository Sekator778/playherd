/** The move since the round opened, as the chart's toolbar prints it: pure functions, no React. */

/** How far the price has moved since the round opened: relative for a price, in points for odds. */
export function moveSinceOpen(open: number, now: number, odds: boolean): { value: number; unit: "pct" | "pts" } {
  if (odds) return { value: (now - open) * 100, unit: "pts" };
  return { value: open === 0 ? 0 : now / open - 1, unit: "pct" };
}

/**
 * The move since the round opened as the chart's header prints it, and the tone that colours and arrows it: a
 * percentage with two decimals (three for a tiny move) for a price, percentage POINTS with one decimal for odds
 * (`+0.4 pt` — the same spelling as the 24 h figure on the card). A move that prints as zero is `0.0 pt` / `0.00%`,
 * flat, with neither sign nor arrow: "−0.0 pt ▼" would say the price fell when nothing did.
 */
export function describeMove(move: { value: number; unit: "pct" | "pts" }): { text: string; tone: "up" | "down" | "flat" } {
  const digits = move.unit === "pts" ? 1 : Math.abs(move.value) < 0.001 ? 3 : 2;
  const shown = Number((move.unit === "pts" ? move.value : move.value * 100).toFixed(digits));
  const unit = move.unit === "pts" ? " pt" : "%";
  if (shown === 0) return { text: `${(0).toFixed(digits)}${unit}`, tone: "flat" };
  return { text: `${shown > 0 ? "+" : ""}${shown.toFixed(digits)}${unit}`, tone: shown > 0 ? "up" : "down" };
}

/**
 * The distance from the round's open in money (a price market only; odds already read in points): `+$5.80` /
 * `-$5.80`, the amount formatted by the room's own price formatter (`format`, given the absolute difference).
 * `null` when the difference prints as zero — no sign, no claim of a direction, same as {@link describeMove}.
 */
export function describeMoneyMove(open: number, now: number, format: (value: number) => string): string | null {
  const diff = now - open;
  if (!Number.isFinite(diff)) return null;
  const shown = format(Math.abs(diff));
  if (shown === format(0)) return null;
  return `${diff > 0 ? "+" : "-"}${shown}`;
}
