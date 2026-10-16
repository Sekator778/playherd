import { useSyncExternalStore } from "react";
import type { Direction } from "../api/contract";

/**
 * The bet a signed-out player set up in the preview slip (`BetSlipPreview`), kept across the sign-in so it is not lost
 * (first tester, F4: direction, leverage and stake chosen before "CONNECT WALLET" came back as defaults, and the welcome
 * dialog's "PLACE A BET" only closed itself). A small module-level store — the slip and the sign-in dialog are far apart in
 * the tree — keyed by the round: a draft for another round, or one whose betting window has closed, is not a draft.
 */
export interface BetDraft {
  roundId: string;
  marketId: string;
  /** When betting on this round closes (ms): past it the draft is gone. */
  closesAtMs: number;
  direction: Direction;
  /** The dial as the player left it (`null`: untouched, on the market's default) and the "High leverage" switch. */
  leverageChoice: number | null;
  high: boolean;
  stake: string;
  /** The leverage actually in use (what is sent), and the total debit the preview named for it, if it had one. */
  leverage: number;
  totalDebit: string | null;
}

/** What the sign-in dialog placed for the player: the slip shows it as its own placed bet. */
export type PlacedDraft = Pick<BetDraft, "roundId" | "direction" | "leverage" | "stake">;

let draft: BetDraft | null = null;
const draftListeners = new Set<() => void>();
const placedListeners = new Set<(placed: PlacedDraft) => void>();
const revealListeners = new Set<() => void>();

function emitDraft() {
  draftListeners.forEach((l) => l());
}

export function saveBetDraft(next: BetDraft) {
  draft = next;
  emitDraft();
}

/** Drops the draft — of this round only when one is named (a closed round must not clear the draft of another). */
export function clearBetDraft(roundId?: string) {
  if (!draft || (roundId !== undefined && draft.roundId !== roundId)) return;
  draft = null;
  emitDraft();
}

/** The draft for this round, while its betting window is open; otherwise none. */
export function betDraftFor(roundId: string, nowMs = Date.now()): BetDraft | null {
  return draft && draft.roundId === roundId && nowMs < draft.closesAtMs ? draft : null;
}

/** The draft, whatever room the player is in — for the dialog, which finds the room by its market. */
export function useBetDraft(): BetDraft | null {
  return useSyncExternalStore(
    (listener) => {
      draftListeners.add(listener);
      return () => draftListeners.delete(listener);
    },
    () => draft,
  );
}

/** The dialog has placed the draft: the draft is spent, and the slip is told so it can say "Bet placed." and reload the room. */
export function announceDraftPlaced(placed: PlacedDraft) {
  clearBetDraft();
  placedListeners.forEach((l) => l(placed));
}

export function onDraftPlaced(listener: (placed: PlacedDraft) => void): () => void {
  placedListeners.add(listener);
  return () => placedListeners.delete(listener);
}

/** After the sign-in dialog closes: bring the slip's submit button (or, once there is a bet, the slip) on screen. */
export function revealBetSlip() {
  revealListeners.forEach((l) => l());
}

export function onRevealBetSlip(listener: () => void): () => void {
  revealListeners.add(listener);
  return () => revealListeners.delete(listener);
}
