/**
 * Small display helpers for market cards and the positions strip: how long until / since something,
 * in the words a card has room for. Pure; nothing here is a source of truth for a number
 * (`format.ts` holds the shared formatters, this file only the card-sized ones).
 */
import { tEn, type TFn } from "../i18n";

/** `"5d 3h"`, `"1h 35m"`, `"12m"`, `"45s"` — the two most significant units of a span, never negative. */
export function formatTimeLeft(ms: number, t: TFn = tEn): string {
  const total = Math.max(0, Math.floor(ms / 1000));
  const days = Math.floor(total / 86_400);
  const hours = Math.floor((total % 86_400) / 3_600);
  const minutes = Math.floor((total % 3_600) / 60);
  const seconds = total % 60;
  if (days > 0) return `${t("time.d", { count: days })} ${t("time.h", { count: hours })}`;
  if (hours > 0) return `${t("time.h", { count: hours })} ${t("time.m", { count: minutes })}`;
  if (minutes > 0) return t("time.m", { count: minutes });
  return t("time.s", { count: seconds });
}

/** How long until a kick-off, for "Starts in …": `"2h 05m"`, `"1d 5h"`, `"45m"`, `"40s"` — hours with the minutes
 *  padded (a clock reads 2:05, not 2:5), days with the hours. Never negative. */
export function formatStartsIn(ms: number, t: TFn = tEn): string {
  const total = Math.max(0, Math.floor(ms / 1000));
  const days = Math.floor(total / 86_400);
  const hours = Math.floor((total % 86_400) / 3_600);
  const minutes = Math.floor((total % 3_600) / 60);
  const seconds = total % 60;
  if (days > 0) return `${t("time.d", { count: days })} ${t("time.h", { count: hours })}`;
  if (hours > 0) return `${t("time.h", { count: hours })} ${t("time.m", { count: String(minutes).padStart(2, "0") })}`;
  if (minutes > 0) return t("time.m", { count: minutes });
  return t("time.s", { count: seconds });
}

/** How long ago, for a stale price: `"just now"`, `"40 s ago"`, `"3 min ago"`, `"2 h ago"`. */
export function formatAgo(ms: number, t: TFn = tEn): string {
  const total = Math.max(0, Math.floor(ms / 1000));
  if (total < 10) return t("time.justNow");
  if (total < 60) return t("time.secAgo", { count: total });
  if (total < 3_600) return t("time.minAgo", { count: Math.floor(total / 60) });
  return t("time.hAgo", { count: Math.floor(total / 3_600) });
}

/** Direction of a signed number as a word for assistive technology and a glyph for the eye; zero and
 *  unknown are their own state, never painted as a gain. */
export function trend(value: number | null): "up" | "down" | "flat" {
  if (value === null || !Number.isFinite(value) || value === 0) return "flat";
  return value > 0 ? "up" : "down";
}

export const TREND_GLYPH = { up: "▲", down: "▼", flat: "•" } as const;
