/**
 * Small display helpers for market cards and the positions strip: how long until / since something,
 * in the words a card has room for. Pure; nothing here is a source of truth for a number
 * (`format.ts` holds the shared formatters, this file only the card-sized ones).
 */

/** `"5d 3h"`, `"1h 35m"`, `"12m"`, `"45s"` — the two most significant units of a span, never negative. */
export function formatTimeLeft(ms: number): string {
  const total = Math.max(0, Math.floor(ms / 1000));
  const days = Math.floor(total / 86_400);
  const hours = Math.floor((total % 86_400) / 3_600);
  const minutes = Math.floor((total % 3_600) / 60);
  const seconds = total % 60;
  if (days > 0) return `${days}d ${hours}h`;
  if (hours > 0) return `${hours}h ${minutes}m`;
  if (minutes > 0) return `${minutes}m`;
  return `${seconds}s`;
}

/** How long ago, for a stale price: `"just now"`, `"40 s ago"`, `"3 min ago"`, `"2 h ago"`. */
export function formatAgo(ms: number): string {
  const total = Math.max(0, Math.floor(ms / 1000));
  if (total < 10) return "just now";
  if (total < 60) return `${total} s ago`;
  if (total < 3_600) return `${Math.floor(total / 60)} min ago`;
  return `${Math.floor(total / 3_600)} h ago`;
}

/** Direction of a signed number as a word for assistive technology and a glyph for the eye; zero and
 *  unknown are their own state, never painted as a gain. */
export function trend(value: number | null): "up" | "down" | "flat" {
  if (value === null || !Number.isFinite(value) || value === 0) return "flat";
  return value > 0 ? "up" : "down";
}

export const TREND_GLYPH = { up: "▲", down: "▼", flat: "•" } as const;
