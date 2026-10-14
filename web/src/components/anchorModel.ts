/**
 * How a settled round's oracle record is worded (Р-40, Р-41, S6): `RoundSettled.oracleAnchor` is
 * `{ seq, state, height }` with `state` the way chainsvc reports it (`docs/research/chain_service.md`):
 * `unknown` until the record is queued, then `queued`, `in_flight`, `confirmed` — or `conflict` when the
 * chain already holds a DIFFERENT record at that `seq` (the record is then held back and flagged, never
 * silently dropped). The wording says only what each state means: nothing claims a record is on chain
 * before it is, and the pending states do not promise a time.
 */
import type { OracleAnchorDto } from "../api/contract";
import { tEn, type TFn } from "../i18n";

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

export function describeAnchor(anchor: OracleAnchorDto, t: TFn = tEn): AnchorWording {
  const seq = anchor.seq;
  switch (anchor.state) {
    case "confirmed":
      return {
        chip: t("anchor.confirmedChip"),
        tone: "ok",
        title: t("anchor.confirmedTitle"),
        detail:
          anchor.height !== null
            ? t("anchor.confirmedAtBlock", { seq, height: anchor.height.toLocaleString("en-US") })
            : t("anchor.confirmed", { seq }),
      };
    case "in_flight":
      return {
        chip: t("anchor.sentChip"),
        tone: "wait",
        title: t("anchor.sentTitle"),
        detail: t("anchor.sent", { seq }),
      };
    case "queued":
      return {
        chip: t("anchor.queuedChip"),
        tone: "wait",
        title: t("anchor.queuedTitle"),
        detail: t("anchor.queued", { seq }),
      };
    case "unknown":
      return {
        chip: t("anchor.pendingChip"),
        tone: "idle",
        title: t("anchor.pendingTitle"),
        detail: t("anchor.pending"),
      };
    case "conflict":
      return {
        chip: t("anchor.conflictChip"),
        tone: "bad",
        title: t("anchor.conflictTitle"),
        detail: t("anchor.conflict", { seq }),
      };
    default:
      // A state a newer server invented: say what we know instead of guessing a meaning.
      return {
        chip: anchor.state.toUpperCase(),
        tone: "idle",
        title: t("anchor.otherTitle"),
        detail: t("anchor.other", { seq, state: anchor.state }),
      };
  }
}
