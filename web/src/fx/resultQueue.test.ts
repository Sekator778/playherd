import { describe, expect, it } from "vitest";
import type { ResultEvent } from "./resultEvent";
import { ResultQueue } from "./resultQueue";

function makeEvent(id: string, net = 10): ResultEvent {
  return {
    id,
    kind: net >= 0 ? "WIN" : "LOSS",
    roundId: id,
    interval: "HOURLY",
    netPlay: net,
    stakePlay: 100,
    leverage: 10,
    balanceBefore: 10000 - net,
    balanceAfter: 10000,
    atMs: 0,
    tier: 1,
  };
}

describe("ResultQueue", () => {
  it("is empty with no current display item", () => {
    const q = new ResultQueue();
    expect(q.current()).toBeNull();
  });

  it("shows a single pushed event individually, FIFO order for more than one", () => {
    const q = new ResultQueue();
    q.push(makeEvent("a"));
    q.push(makeEvent("b"));
    expect(q.current()).toEqual({ kind: "single", event: expect.objectContaining({ id: "a" }) });
    q.advance();
    expect(q.current()).toEqual({ kind: "single", event: expect.objectContaining({ id: "b" }) });
    q.advance();
    expect(q.current()).toBeNull();
  });

  it("collapses everything from the 3rd pending item into one overflow card", () => {
    const q = new ResultQueue();
    q.push(makeEvent("a"));
    q.push(makeEvent("b"));
    q.push(makeEvent("c"));
    q.push(makeEvent("d"));

    // First two still shown individually, in order.
    expect(q.current()).toEqual({ kind: "single", event: expect.objectContaining({ id: "a" }) });
    q.advance();
    expect(q.current()).toEqual({ kind: "single", event: expect.objectContaining({ id: "b" }) });
    q.advance();

    // From here on, everything still pending collapses into ONE overflow card, not dropped.
    const overflow = q.current();
    expect(overflow?.kind).toBe("overflow");
    if (overflow?.kind === "overflow") {
      expect(overflow.events.map((e) => e.id)).toEqual(["c", "d"]);
    }
    q.advance();
    expect(q.current()).toBeNull();
  });

  it("a late-arriving 3rd item does not retroactively collapse an already-shown individual", () => {
    const q = new ResultQueue();
    q.push(makeEvent("a"));
    q.push(makeEvent("b"));
    expect(q.current()).toEqual({ kind: "single", event: expect.objectContaining({ id: "a" }) });
    q.advance(); // "a" was shown individually and is gone

    q.push(makeEvent("c")); // arrives while "b" is still queued
    // "b" is still within the individual budget (only 1 shown so far), stays individual.
    expect(q.current()).toEqual({ kind: "single", event: expect.objectContaining({ id: "b" }) });
    q.advance();
    // "c" alone is now the 3rd shown overall -> still collapses (budget of 2 already used).
    const overflow = q.current();
    expect(overflow?.kind).toBe("overflow");
  });

  it("tap-to-skip (advance) moves to the next item immediately, one moment on screen at a time", () => {
    const q = new ResultQueue();
    q.push(makeEvent("a"));
    q.push(makeEvent("b"));
    expect(q.size()).toBe(2);
    q.advance();
    expect(q.size()).toBe(1);
    expect(q.current()?.kind).toBe("single");
  });

  it("resets the individual budget once the queue drains, so a later batch starts fresh", () => {
    const q = new ResultQueue();
    q.push(makeEvent("a"));
    q.advance();
    expect(q.current()).toBeNull();

    q.push(makeEvent("x"));
    q.push(makeEvent("y"));
    expect(q.current()).toEqual({ kind: "single", event: expect.objectContaining({ id: "x" }) });
  });
});
