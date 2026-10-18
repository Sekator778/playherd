import { describe, expect, it } from "vitest";
import type { BetDto, LiveBetDto, PlayerSummaryDto } from "../api/contract";
import { liveBetRows, topWithOwn } from "./liveBetsModel";

const player = (id: string): PlayerSummaryDto => ({ playerId: id, nickname: id, walletAddress: `txch1${id}`, displayName: id });
const row = (id: string, stake: string, atMs: number, over: Partial<LiveBetDto> = {}): LiveBetDto => ({
  player: player(id),
  direction: "UP",
  leverage: 10,
  stake,
  atMs,
  ...over,
});
const bet = (id: string, stake: string): BetDto => ({ playerId: id, roundId: "r1", direction: "UP", leverage: 10, stake });

describe("liveBetRows — one row per bet, the current one (first tester pass, D4)", () => {
  it("a bet edited three times is one row — its newest version — not four (the feed arrives newest first)", () => {
    const feed = [row("me", "300.000", 4), row("me", "120.000", 3), row("me", "150.000", 2), row("me", "110.000", 1)];
    expect(liveBetRows(feed, "me", bet("me", "300.000")).map((r) => r.stake)).toEqual(["300.000"]);
  });

  it("every other player keeps their own newest row, in the feed's order", () => {
    const feed = [row("ann", "500.000", 6), row("me", "300.000", 5), row("bob", "200.000", 4), row("ann", "100.000", 3), row("bob", "250.000", 2)];
    expect(liveBetRows(feed, "me", bet("me", "300.000")).map((r) => `${r.player.playerId}:${r.stake}`)).toEqual([
      "ann:500.000",
      "me:300.000",
      "bob:200.000",
    ]);
  });

  it("the viewer's cancelled bet leaves the list, whatever versions of it the feed still holds", () => {
    const feed = [row("ann", "500.000", 4), row("me", "300.000", 3), row("me", "110.000", 1)];
    expect(liveBetRows(feed, "me", null).map((r) => r.player.playerId)).toEqual(["ann"]);
    expect(liveBetRows(feed, "me", undefined).map((r) => r.player.playerId)).toEqual(["ann"]);
  });

  it("a signed-out visitor has no bet of their own to settle anything: the feed is just deduplicated", () => {
    const feed = [row("ann", "500.000", 3), row("ann", "100.000", 1), row("bob", "200.000", 2)];
    expect(liveBetRows(feed, null, null).map((r) => `${r.player.playerId}:${r.stake}`)).toEqual(["ann:500.000", "bob:200.000"]);
  });

  it("no rows, no rows", () => {
    expect(liveBetRows([], "me", null)).toEqual([]);
  });
});

describe("own bet always in the list, marked (Alex 2026-10-18)", () => {
  const me = player("me");
  const crowd = Array.from({ length: 14 }, (_, i) => row(`bot${i}`, "100.000", 100 - i));

  it("an own bet outside the newest 14 is appended at the end, marked; the list grows by that one row", () => {
    const rows = liveBetRows(crowd, "me", bet("me", "300.000"), me);
    expect(rows).toHaveLength(15);
    expect(rows.slice(0, 14).every((r) => !r.own)).toBe(true);
    expect(rows[14]).toMatchObject({ own: true, stake: "300.000", player: me });
  });

  it("an own bet among the newest is marked in place, with no duplicate", () => {
    const feed = [crowd[0], row("me", "300.000", 90), ...crowd.slice(1, 13)];
    const rows = liveBetRows(feed, "me", bet("me", "300.000"), me);
    expect(rows).toHaveLength(14);
    expect(rows.filter((r) => r.own).map((r) => rows.indexOf(r))).toEqual([1]);
  });

  it("an edited own bet shows the bet's current stake", () => {
    const rows = liveBetRows([row("me", "110.000", 5)], "me", bet("me", "300.000"), me);
    expect(rows.map((r) => r.stake)).toEqual(["300.000"]);
  });

  it("no session: nothing is marked or added", () => {
    const rows = liveBetRows(crowd, null, null, null);
    expect(rows).toHaveLength(14);
    expect(rows.some((r) => r.own)).toBe(false);
  });

  it("a cancelled own bet adds no row", () => {
    expect(liveBetRows(crowd, "me", null, me)).toHaveLength(14);
  });
});

describe("topWithOwn — the running round's movers", () => {
  const ranked = [{ id: "a" }, { id: "b" }, { id: "c" }, { id: "d" }, { id: "me" }, { id: "e" }];
  const isMe = (x: { id: string }) => x.id === "me";

  it("own entry below the top 4 is added after them, keeping rank order", () => {
    expect(topWithOwn(ranked, 4, isMe).map((x) => x.id)).toEqual(["a", "b", "c", "d", "me"]);
  });
  it("own entry inside the top 4 stays in place, no duplicate", () => {
    const r = [{ id: "a" }, { id: "me" }, { id: "c" }, { id: "d" }, { id: "e" }];
    expect(topWithOwn(r, 4, isMe).map((x) => x.id)).toEqual(["a", "me", "c", "d"]);
  });
  it("no own entry (no session): just the top 4", () => {
    expect(topWithOwn(ranked, 4, () => false).map((x) => x.id)).toEqual(["a", "b", "c", "d"]);
  });
  it("own entry absent from the round: just the top 4", () => {
    expect(topWithOwn(ranked.filter((x) => x.id !== "me"), 4, isMe)).toHaveLength(4);
  });
});
