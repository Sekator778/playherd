/**
 * What became of a liquidation the player was told about (`liquidationMoment.ts`). The moment says "your whole stake is
 * lost"; the one rule that can still change it is a cancellation — every bet in a cancelled round is refunded, liquidated
 * ones too (Р-23). A cancelled round leaves no history row (`unseenResults()` never lists it), so the position simply
 * disappears from `myPositions()` and the balance rises by the stake with nothing said. This asks the round once its
 * position is gone: CANCELLED → one quiet note, "round cancelled — stake refunded"; SETTLED → nothing to say here (the
 * settlement's own quiet summary comes from `unseenResults.ts`); anything else is asked again later.
 */
import { isNotFound } from "../api/apiErrors";
import type { Api } from "../api/contract";
import { closeFollowUp, refundedNoteEvent, type PendingFollowUp } from "./liquidationMoment";
import { publish } from "./resultEventBus";

/** `done`: nothing more to ask about this round. `later`: its fate is not known yet — ask again. */
export type FollowUpOutcome = "done" | "later";

export async function followUpLiquidation(api: Api, playerId: string, pending: PendingFollowUp, nowMs: number = Date.now()): Promise<FollowUpOutcome> {
  let round;
  try {
    round = await api.round(pending.roundId);
  } catch (err) {
    // A round the server has never heard of (a database that was reset) will never answer: closed. A server that is
    // restarting or unreachable will — later.
    if (isNotFound(err)) {
      await closeFollowUp(playerId, pending.roundId);
      return "done";
    }
    return "later";
  }
  if (round.phase === "CANCELLED") {
    // Across tabs and reloads exactly one caller closes it — and that one says it.
    if (await closeFollowUp(playerId, pending.roundId)) publish(refundedNoteEvent(round, playerId, pending.info, nowMs));
    return "done";
  }
  if (round.phase === "SETTLED") {
    await closeFollowUp(playerId, pending.roundId);
    return "done";
  }
  return "later"; // BETTING / RUNNING: the position left `myPositions()` for a reason this poll does not see yet
}
