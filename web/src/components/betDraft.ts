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
  direction: Direction;
  /** The dial as the player left it (`null`: untouched, on the market's default) and the "High leverage" switch. */
  leverageChoice: number | null;
  high: boolean;
  stake: string;
  /** The leverage actually in use (what is sent), and the total debit the preview named for it, if it had one. */
  leverage: number;
  totalDebit: string | null;
  /** Set by the bet sheet (Р-98): it can be opened from the lobby, where there is no room whose path would tell the sign-in dialog the draft is
   *  the player's to place. */
  fromSheet?: boolean;
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

/** The draft for this round, if there is one. */
export function betDraftFor(roundId: string): BetDraft | null {
  return draft && draft.roundId === roundId ? draft : null;
}

/** What the slip of a room says about the draft of its market: that round is over (betting closed, or the room is on the next round)
 *  and the draft goes. The slip's clock is the room's own (server time), so the draft carries no clock of its own. */
export function dropStaleBetDraft(marketId: string, roundId: string, bettingClosed: boolean) {
  if (draft && draft.marketId === marketId && (draft.roundId !== roundId || bettingClosed)) clearBetDraft();
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
