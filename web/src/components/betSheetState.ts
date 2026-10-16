import { useSyncExternalStore } from "react";
import type { Direction, Interval, MarketId } from "../api/contract";

/**
 * The bet sheet's open/closed state (Р-98): a tap on UP or DOWN — a lobby card's, the room's arena — opens a sheet over the screen instead of
 * taking the player down a page to a form. The sheet is mounted once, in the layout (`BetSheetHost`), so a small module-level store connects it
 * to the buttons that open it, the same way `betDraft.ts` connects the slip to the sign-in dialog.
 */
export interface BetSheetRequest {
  marketId: MarketId;
  /** The round series the bet is for (the lobby card's lead series; the room's chosen one). */
  interval: Interval;
  direction: Direction;
}

let request: BetSheetRequest | null = null;
const listeners = new Set<() => void>();
const emit = () => listeners.forEach((l) => l());

export function openBetSheet(next: BetSheetRequest): void {
  request = next;
  emit();
}

export function closeBetSheet(): void {
  if (request === null) return;
  request = null;
  emit();
}

export function useBetSheetRequest(): BetSheetRequest | null {
  return useSyncExternalStore(
    (listener) => {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },
    () => request,
    () => null,
  );
}

// ---------------------------------------------------------------------------
// "Customize (Pro)": the sheet hands its choice to the full slip
// ---------------------------------------------------------------------------

let customize: { nonce: number; direction: Direction } = { nonce: 0, direction: "UP" };
const customizeListeners = new Set<() => void>();

/** The sheet's "Customize (Pro)": a room that is already open learns of it (a new `nonce`) and starts its slip over from the draft. */
export function announceCustomize(direction: Direction): void {
  customize = { nonce: customize.nonce + 1, direction };
  customizeListeners.forEach((l) => l());
}

export function useCustomizeRequest(): { nonce: number; direction: Direction } {
  return useSyncExternalStore(
    (listener) => {
      customizeListeners.add(listener);
      return () => customizeListeners.delete(listener);
    },
    () => customize,
    () => customize,
  );
}

// ---------------------------------------------------------------------------
// Signing in from the sheet: the test wallet straight away
// ---------------------------------------------------------------------------

let autoTestWallet = false;

/** The sheet's "Continue with test wallet": the sign-in dialog, once it has opened, takes that road at once instead of asking again. */
export function requestAutoTestWallet(): void {
  autoTestWallet = true;
}

/** Whether the dialog was opened that way — and forgets it, so a later opening asks as usual. */
export function consumeAutoTestWallet(): boolean {
  const was = autoTestWallet;
  autoTestWallet = false;
  return was;
}
