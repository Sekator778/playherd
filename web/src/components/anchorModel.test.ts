import { describe, expect, it } from "vitest";
import { describeAnchor } from "./anchorModel";

describe("how a round's oracle record is worded", () => {
  it("confirmed: on chain, with the block", () => {
    expect(describeAnchor({ seq: 412, state: "confirmed", height: 4_738_927 })).toEqual({
      chip: "CONFIRMED",
      tone: "ok",
      title: "Anchored on Chia",
      detail: "Oracle record #412, confirmed at block 4,738,927 on testnet11.",
    });
  });

  it("confirmed without a height still says confirmed, never a made-up block", () => {
    const said = describeAnchor({ seq: 7, state: "confirmed", height: null });
    expect(said.detail).toBe("Oracle record #7 is confirmed on testnet11.");
    expect(said.detail).not.toMatch(/block/);
  });

  it("in flight: sent, waiting — not yet anchored", () => {
    const said = describeAnchor({ seq: 413, state: "in_flight", height: null });
    expect(said).toMatchObject({ chip: "SENT", tone: "wait", title: "Being anchored on Chia" });
    expect(said.detail).toBe("Oracle record #413 was sent to testnet11 and is waiting for confirmation.");
  });

  it("queued: waiting for the next batch — and promises no time", () => {
    const said = describeAnchor({ seq: 414, state: "queued", height: null });
    expect(said).toMatchObject({ chip: "QUEUED", tone: "wait", title: "Waiting to be anchored on Chia" });
    expect(said.detail).not.toMatch(/minute|second|soon/i);
  });

  it("unknown: not in the publishing queue yet", () => {
    expect(describeAnchor({ seq: 0, state: "unknown", height: null })).toMatchObject({ chip: "PENDING", tone: "idle", title: "Not anchored yet" });
  });

  it("conflict: not anchored, and the reason in plain words", () => {
    const said = describeAnchor({ seq: 415, state: "conflict", height: null });
    expect(said).toMatchObject({ chip: "CONFLICT", tone: "bad", title: "Not anchored — conflict" });
    expect(said.detail).toBe("The chain already holds a different record at position #415, so this round's record was held back and flagged.");
  });

  it("a state a newer server invented is shown as it is, not guessed at", () => {
    const said = describeAnchor({ seq: 9, state: "orphaned", height: null });
    expect(said).toMatchObject({ chip: "ORPHANED", tone: "idle" });
    expect(said.detail).toContain('state "orphaned"');
  });

  it("none of the pending or failed states claims the record is on chain", () => {
    for (const state of ["unknown", "queued", "in_flight", "conflict"]) {
      const said = describeAnchor({ seq: 1, state, height: null });
      expect(`${said.title} ${said.detail}`).not.toMatch(/confirmed at|is confirmed/);
    }
  });
});
