import { describe, expect, it } from "vitest";
import { buildChartLines } from "./priceChartModel";

describe("buildChartLines", () => {
  const own = { direction: "UP" as const, leverage: 10, entryPrice: "100.00", liquidationPrice: "90.50" };
  const crowd = { direction: "DOWN" as const, leverage: 7, entryPrice: "100.00", liquidationPrice: "114.00" };

  it("nothing known, nothing drawn", () => {
    expect(buildChartLines({})).toEqual([]);
    expect(buildChartLines({ openPrice: null })).toEqual([]);
    expect(buildChartLines({ openPrice: "nope" })).toEqual([]);
  });

  it("just the open price for a player with no position", () => {
    expect(buildChartLines({ openPrice: "100.00" })).toEqual([{ id: "open", label: "ROUND START", price: 100, tone: "open", faint: false }]);
  });

  it("every position opens at the round's open price, so the player's entry IS the open line, labelled as such", () => {
    const lines = buildChartLines({ openPrice: "100.00", own });
    expect(lines.map((l) => l.id)).toEqual(["open", "own-liq"]);
    expect(lines[0]).toMatchObject({ label: "ROUND START · YOUR ENTRY ▲ x10", tone: "entry", faint: false });
    expect(lines[1]).toMatchObject({ label: "YOUR WIPEOUT", price: 90.5, tone: "liq", faint: false });
  });

  it("the herd's lines are the same kind, fainter; its entry merges into the open line, its liquidation is its own", () => {
    const lines = buildChartLines({ openPrice: "100.00", own, crowd });
    expect(lines.map((l) => l.id)).toEqual(["open", "own-liq", "crowd-liq"]);
    expect(lines[2]).toMatchObject({ label: "HERD LIQ ▼ x7", price: 114, tone: "liq", faint: true });
  });

  it("an entry that is NOT at the open price (should not happen, but a server can surprise) is drawn as its own line", () => {
    const lines = buildChartLines({ openPrice: "100.00", own: { ...own, entryPrice: "100.50" }, crowd: { ...crowd, entryPrice: "101.00" } });
    expect(lines.map((l) => l.id)).toEqual(["open", "own-entry", "own-liq", "crowd-entry", "crowd-liq"]);
    expect(lines[0]).toMatchObject({ label: "ROUND START", tone: "open" });
    expect(lines.find((l) => l.id === "crowd-entry")).toMatchObject({ faint: true, label: "HERD ENTRY ▼ x7" });
  });

  it("a position with no round open price still draws its own levels", () => {
    const lines = buildChartLines({ own });
    expect(lines.map((l) => l.id)).toEqual(["own-entry", "own-liq"]);
  });
});
