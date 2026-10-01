/**
 * How a settled round's oracle record is worded (Р-40, Р-41, S6): `RoundSettled.oracleAnchor` is
 * `{ seq, state, height }` with `state` the way chainsvc reports it (`docs/research/chain_service.md`):
 * `unknown` until the record is queued, then `queued`, `in_flight`, `confirmed` — or `conflict` when the
 * chain already holds a DIFFERENT record at that `seq` (the record is then held back and flagged, never
 * silently dropped). The wording says only what each state means: nothing claims a record is on chain
 * before it is, and the pending states do not promise a time.
 */
import type { OracleAnchorDto } from "../api/contract";

export type AnchorTone = "ok" | "wait" | "bad" | "idle";

export interface AnchorWording {
  /** The state as a short chip ("CONFIRMED"). */
  chip: string;
  tone: AnchorTone;
  /** What to call it ("Anchored on Chia"). */
  title: string;
  /** One honest sentence about it. */
  detail: string;
}

export function describeAnchor(anchor: OracleAnchorDto): AnchorWording {
  const record = `oracle record #${anchor.seq}`;
  switch (anchor.state) {
    case "confirmed":
      return {
        chip: "CONFIRMED",
        tone: "ok",
        title: "Anchored on Chia",
        detail:
          anchor.height !== null
            ? `${capitalize(record)}, confirmed at block ${anchor.height.toLocaleString("en-US")} on testnet11.`
            : `${capitalize(record)} is confirmed on testnet11.`,
      };
    case "in_flight":
      return {
        chip: "SENT",
        tone: "wait",
        title: "Being anchored on Chia",
        detail: `${capitalize(record)} was sent to testnet11 and is waiting for confirmation.`,
      };
    case "queued":
      return {
        chip: "QUEUED",
        tone: "wait",
        title: "Waiting to be anchored on Chia",
        detail: `${capitalize(record)} is queued and goes out with the next batch.`,
      };
    case "unknown":
      return {
        chip: "PENDING",
        tone: "idle",
        title: "Not anchored yet",
        detail: "This round's oracle record hasn't reached the publishing queue.",
      };
    case "conflict":
      return {
        chip: "CONFLICT",
        tone: "bad",
        title: "Not anchored — conflict",
        detail: `The chain already holds a different record at position #${anchor.seq}, so this round's record was held back and flagged.`,
      };
    default:
      // A state a newer server invented: say what we know instead of guessing a meaning.
      return {
        chip: anchor.state.toUpperCase(),
        tone: "idle",
        title: "Oracle record",
        detail: `${capitalize(record)} — state "${anchor.state}".`,
      };
  }
}

function capitalize(text: string): string {
  return text.charAt(0).toUpperCase() + text.slice(1);
}
