/** The positions strip's small non-visual pieces: where a card leads, and the collapsed preference. */
import type { MyPositionDto } from "../api/contract";

export const POSITIONS_STRIP_COLLAPSED_KEY = "herd.positionsStrip.collapsed";

export function readStripCollapsed(): boolean {
  try {
    return window.localStorage.getItem(POSITIONS_STRIP_COLLAPSED_KEY) === "1";
  } catch {
    return false;
  }
}

export function writeStripCollapsed(collapsed: boolean): void {
  try {
    window.localStorage.setItem(POSITIONS_STRIP_COLLAPSED_KEY, collapsed ? "1" : "0");
  } catch {
    // storage unavailable: the strip still collapses for this page
  }
}

/** Where a strip card leads: the market's room, on the series the position is in. */
export function positionRoomPath(position: Pick<MyPositionDto, "marketId" | "interval">): string {
  return `/play/${encodeURIComponent(position.marketId)}?series=${position.interval === "DAILY" ? "daily" : "hourly"}`;
}
