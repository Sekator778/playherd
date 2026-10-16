import { useSyncExternalStore } from "react";

/**
 * The "Pro view" switch (Р-98, variant A): the default screens are the game — two buttons, a sheet, one pinned card — and Pro keeps
 * everything the terminal-like screens had (the boost slider, the crowd shortcuts, the fee rows, every chart line, the old
 * "Your bets" strip). It is remembered per browser; a browser that will not store it still switches for the page.
 */
export const PRO_MODE_KEY = "herd.pro";

function read(): boolean {
  try {
    return window.localStorage.getItem(PRO_MODE_KEY) === "1";
  } catch {
    return false;
  }
}

let pro = read();
const listeners = new Set<() => void>();

export function isProMode(): boolean {
  return pro;
}

export function setProMode(on: boolean): void {
  if (pro === on) return;
  pro = on;
  try {
    window.localStorage.setItem(PRO_MODE_KEY, on ? "1" : "0");
  } catch {
    // storage unavailable: Pro holds for this page only
  }
  listeners.forEach((l) => l());
}

export function useProMode(): boolean {
  return useSyncExternalStore(
    (listener) => {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },
    () => pro,
    () => false,
  );
}
