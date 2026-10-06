import { describe, expect, it } from "vitest";
import type { BetDto, LiveBetDto, PlayerSummaryDto } from "../api/contract";
import { liveBetRows } from "./liveBetsModel";

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
